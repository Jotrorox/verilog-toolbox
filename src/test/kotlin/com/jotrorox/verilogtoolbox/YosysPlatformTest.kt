package com.jotrorox.verilogtoolbox

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class YosysPlatformTest : BasePlatformTestCase() {
    /** Synthesizes [top] of [text] for [target] and returns the console output and exit code; null without Yosys. */
    private fun synthesize(text: String, top: String, target: SynthesisTarget = SynthesisTarget.GENERIC): Pair<String, Int>? {
        val yosys = ToolchainSettings.getInstance().findYosys() ?: return null // Yosys is not installed here.
        val sources = Files.createTempDirectory("yosys test")
        val run = Files.createTempDirectory("yosys run")
        try {
            Files.writeString(
                sources.resolve("counter.sv"),
                "module counter(input logic clk, input logic rst, output logic [3:0] q);\n" +
                    "  always_ff @(posedge clk) if (rst) q <= '0; else q <= q + 1'b1;\nendmodule\n",
            )
            val file = Files.writeString(sources.resolve("$top.sv"), text)
            val design = VerilogDesign.standalone(file, listOf(sources))
            val handler = SynthesisProcessHandler(yosys, design, top, target, run)
            val output = StringBuilder()
            var exitCode = -1
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    synchronized(output) { output.append(event.text) }
                }

                override fun processTerminated(event: ProcessEvent) {
                    exitCode = event.exitCode
                }
            })
            handler.startNotify()
            assertTrue("synthesis did not finish", handler.waitFor(120_000))
            return synchronized(output) { output.toString() } to exitCode
        } finally {
            NioFiles.deleteRecursively(sources)
            NioFiles.deleteRecursively(run)
        }
    }

    fun testResourcesAreSummedUp() {
        val (output, exitCode) = synthesize(
            "module top(input logic clk, input logic rst, output logic [3:0] q);\n  counter c(.clk, .rst, .q);\nendmodule\n",
            "top",
        ) ?: return
        assertEquals(output, 0, exitCode)
        assertTrue(output, "top synthesizes for Generic gates to " in output)
        assertTrue(output, "4 flip-flops" in output)
        assertTrue(output, "Full log: " in output)
    }

    fun testFpgaFamilyIsCounted() {
        val (output, exitCode) = synthesize(
            "module top(input logic clk, input logic rst, output logic [3:0] q);\n  counter c(.clk, .rst, .q);\nendmodule\n",
            "top", SynthesisTarget.ICE40,
        ) ?: return
        assertEquals(output, 0, exitCode)
        assertTrue(output, Regex("""top synthesizes for Lattice iCE40 to \d+ cells: \d+ LUTs, 4 flip-flops""").containsMatchIn(output))
    }

    fun testProblemsAreReported() {
        val (output, exitCode) = synthesize(
            "module top(input logic [1:0] a, output logic q);\n  assign q = a[0];\n  assign q = a[1];\nendmodule\n",
            "top",
        ) ?: return
        assertEquals(output, 0, exitCode)
        assertTrue(output, "multiple conflicting drivers" in output)
        assertTrue(output, Regex("""\d+ warnings?, see above""").containsMatchIn(output))
    }

    fun testMissingModuleFails() {
        val (output, exitCode) = synthesize("module top(input logic a);\n  missing m(.a);\nendmodule\n", "top") ?: return
        assertTrue(output, exitCode != 0)
        assertTrue(output, "top does not synthesize for Generic gates." in output)
    }

    fun testRunDirectoryIsInTheBuildDirectory() {
        assertEquals(Path.of(project.basePath!!).resolve("build/synth/top"), YosysSynthesis.runDirectory(project, "top"))
    }
}
