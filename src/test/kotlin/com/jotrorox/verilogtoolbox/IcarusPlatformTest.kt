package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class IcarusPlatformTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            // CLion switches this on at startup, which the test framework reports as a setting the test damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testRunIconIsShownOnTestbenchesOnly() {
        myFixture.configureByText(
            "design.v",
            "module counter(input clk);\nendmodule\nmodule tb;\n  counter dut(1'b0);\nendmodule\n",
        )
        val lines = myFixture.findAllGutters()
            .filter { it.tooltipText?.contains("Simulate testbench") == true }
            .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .map { myFixture.editor.document.getLineNumber(it.lineMarkerInfo.startOffset) }
        assertEquals(listOf(2), lines)
    }

    /** Runs [scripts] as a chain and returns its standard output and exit code. */
    private fun runSteps(vararg scripts: String): Pair<String, Int> =
        run(StepsProcessHandler { scripts.map { GeneralCommandLine("/bin/sh", "-c", it) } })

    private fun run(handler: StepsProcessHandler): Pair<String, Int> {
        val output = StringBuilder()
        var exitCode = -1
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDOUT) synchronized(output) { output.append(event.text) }
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCode = event.exitCode
            }
        })
        handler.startNotify()
        assertTrue("the steps did not finish", handler.waitFor(30_000))
        return output.toString() to exitCode
    }

    fun testStepsRunInOrderAndReportTheLastExitCode() {
        if (SystemInfo.isWindows) return
        assertEquals("one\ntwo\n" to 3, runSteps("echo one", "echo two; exit 3"))
    }

    fun testFailingStepStopsTheChain() {
        if (SystemInfo.isWindows) return
        assertEquals("one\n" to 2, runSteps("echo one; exit 2", "echo two"))
    }

    fun testStepsThatCannotBeBuiltFailTheRun() {
        val handler = StepsProcessHandler { throw ExecutionException("no compiler") }
        handler.startNotify()
        assertTrue(handler.waitFor(30_000))
        assertEquals(1, handler.exitCode)
    }

    /** Simulates [testbench] next to a counter module, returning the console output and exit code. */
    private fun simulate(testbench: String): Pair<String, Int>? {
        if (ToolchainSettings.getInstance().findIverilog() == null) return null // Icarus is not installed here.
        val directory = Files.createTempDirectory("icarus test")
        try {
            Files.writeString(
                directory.resolve("design.sv"),
                "module counter(input logic clk, output logic [3:0] q);\n" +
                    "  initial q = 0;\n  always_ff @(posedge clk) q <= q + 1;\nendmodule\n",
            )
            val file = Files.writeString(directory.resolve("tb.v"), testbench)
            val type = ConfigurationTypeUtil.findConfigurationType(IcarusConfigurationType::class.java)
            val configuration = type.createTemplateConfiguration(project) as IcarusRunConfiguration
            configuration.name = "tb"
            configuration.settings.testbench = file.toString()
            configuration.checkConfiguration()
            return run(StepsProcessHandler { IcarusCommandLines.create(configuration) })
        } finally {
            NioFiles.deleteRecursively(directory)
        }
    }

    fun testTestbenchIsCompiledAndSimulated() {
        val (output, exitCode) = simulate(
            "module tb;\n  reg clk = 0; wire [3:0] q;\n  counter dut(clk, q);\n  always #1 clk = ~clk;\n" +
                "  initial begin #10 \$display(\"q=%0d\", q); \$finish; end\nendmodule\n"
        ) ?: return
        assertEquals(0, exitCode)
        assertTrue(output, "q=5" in output)
    }

    fun testCompileErrorSkipsTheSimulation() {
        val (output, exitCode) = simulate("module tb;\n  initial \$display(\"ran\")\nendmodule\n") ?: return
        assertTrue("exit code $exitCode", exitCode != 0)
        assertFalse(output, "ran" in output)
    }
}
