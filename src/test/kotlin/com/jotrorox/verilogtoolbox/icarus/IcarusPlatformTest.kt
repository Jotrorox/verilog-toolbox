package com.jotrorox.verilogtoolbox.icarus

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.ProjectDirectoryState
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path

class IcarusPlatformTest : BasePlatformTestCase() {
    private lateinit var projectDirectory: ProjectDirectoryState

    override fun setUp() {
        super.setUp()
        projectDirectory = ProjectDirectoryState(project)
    }

    override fun tearDown() {
        try {
            projectDirectory.restore()
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

    /** Runs [steps] as one test and returns the standard output and exit code of the run. */
    private fun run(steps: () -> List<GeneralCommandLine>): Pair<String, Int> {
        val handler = IcarusTestsProcessHandler { listOf(IcarusTestbench("tb", null, null, null, { steps() })) }
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
            try {
                return run { IcarusCommandLines.create(configuration) }
            } finally {
                configuration.simulationDirectory?.let { NioFiles.deleteRecursively(it) }
            }
        } finally {
            NioFiles.deleteRecursively(directory)
        }
    }

    /**
     * Simulates a testbench that dumps a waveform and reads `data.hex` by relative paths, and returns its
     * output with the directory it ran in. The data file is put where the simulation is expected to run.
     */
    private fun simulateWithFiles(configure: (IcarusRunConfiguration, Path) -> Unit): Triple<String, Path, Path>? {
        if (ToolchainSettings.getInstance().findIverilog() == null) return null // Icarus is not installed here.
        val directory = Files.createTempDirectory("icarus dump")
        val file = Files.writeString(
            directory.resolve("dump_tb.v"),
            "module dump_tb;\n  reg [7:0] data [0:0];\n  initial begin\n    \$dumpfile(\"waves.vcd\"); \$dumpvars;\n" +
                "    \$readmemh(\"data.hex\", data); #1 \$display(\"data=%0d\", data[0]); \$finish;\n  end\nendmodule\n",
        )
        val type = ConfigurationTypeUtil.findConfigurationType(IcarusConfigurationType::class.java)
        val configuration = type.createTemplateConfiguration(project) as IcarusRunConfiguration
        configuration.name = "dump"
        configuration.settings.testbench = file.toString()
        configure(configuration, directory)
        configuration.checkConfiguration()
        val simulation = configuration.simulationDirectory!!
        Files.createDirectories(simulation)
        Files.writeString(simulation.resolve("data.hex"), "2a\n")
        val (output, exitCode) = run { IcarusCommandLines.create(configuration) }
        assertEquals(output, 0, exitCode)
        return Triple(output, directory, simulation)
    }

    fun testSimulationRunsInTheBuildDirectory() {
        val (output, directory, simulation) = simulateWithFiles { _, _ -> } ?: return
        try {
            assertEquals(Path.of(project.basePath!!).resolve("build/sim/dump_tb"), simulation)
            assertTrue(output, "data=42" in output)
            assertTrue(Files.isRegularFile(simulation.resolve("waves.vcd")))
            assertFalse(Files.exists(directory.resolve("waves.vcd")))
        } finally {
            NioFiles.deleteRecursively(simulation)
            NioFiles.deleteRecursively(directory)
        }
    }

    fun testConfiguredWorkingDirectoryIsWhereTheSimulationRuns() {
        val (output, directory, simulation) =
            simulateWithFiles { configuration, directory -> configuration.settings.workingDirectory = directory.toString() } ?: return
        try {
            assertEquals(directory, simulation)
            assertTrue(output, "data=42" in output)
            assertTrue(Files.isRegularFile(directory.resolve("waves.vcd")))
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

    fun testFileListDefinesTheDesign() {
        if (ToolchainSettings.getInstance().findIverilog() == null) return // Icarus is not installed here.
        val directory = Files.createTempDirectory("icarus list")
        try {
            // Neither file is in the testbench's directory, where a run without a file list looks.
            Files.createDirectories(directory.resolve("rtl"))
            Files.createDirectories(directory.resolve("tb"))
            Files.writeString(directory.resolve("rtl/one.sv"), "module one(output logic [7:0] q);\n  assign q = `VALUE;\nendmodule\n")
            val file = Files.writeString(
                directory.resolve("tb/tb.v"),
                "module tb;\n  wire [7:0] q;\n  one dut(q);\n  initial begin #1 \$display(\"q=%0d\", q); \$finish; end\nendmodule\n",
            )
            val list = Files.writeString(
                directory.resolve("design.f"),
                "\"${directory.resolve("rtl/one.sv")}\" // the design\n+define+VALUE=42\n",
            )
            val type = ConfigurationTypeUtil.findConfigurationType(IcarusConfigurationType::class.java)
            val configuration = type.createTemplateConfiguration(project) as IcarusRunConfiguration
            configuration.name = "list"
            configuration.settings.testbench = file.toString()
            configuration.settings.fileList = list.toString()
            configuration.checkConfiguration()
            val (output, exitCode) = run { IcarusCommandLines.create(configuration) }
            assertEquals(output, 0, exitCode)
            assertTrue(output, "q=42" in output)
        } finally {
            NioFiles.deleteRecursively(directory)
        }
    }
}
