package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.gowin.GowinBoards
import com.jotrorox.verilogtoolbox.quartus.QuartusNewProject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds a project with the real GOWIN EDA, as the plugin creates and runs it, and reads what it wrote. Only runs
 * where GOWIN EDA is installed: in the settings, on PATH, where it installs by default, or given as
 * `VERILOG_TOOLBOX_GOWIN_EDA`.
 */
class GowinEdaPlatformTest : BasePlatformTestCase() {
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("gowin-eda-test").toRealPath()
    }

    override fun tearDown() {
        try {
            FileUtil.delete(directory)
            // As in GowinBuildPlatformTest: the platform's check of the settings trips over this one otherwise.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun installation(): GowinEdaInstallation? {
        val asked = System.getenv("VERILOG_TOOLBOX_GOWIN_EDA")
        val found = if (asked != null) GowinEda.find(asked) else GowinEda.find()
        if (found == null && asked != null) fail("No GOWIN EDA in $asked")
        return found
    }

    /** A Tang Nano 9K blinky with a module nothing instantiates, which GOWIN EDA alone would take for the top. */
    private fun create(installation: GowinEdaInstallation): GowinEdaProject {
        val source = Files.createDirectories(directory.resolve("src"))
        Files.writeString(
            source.resolve("blinky.v"),
            """
            module blinky(input clk, input rst_n, output reg [5:0] led);
                reg [23:0] count;
                always @(posedge clk or negedge rst_n)
                    if (!rst_n) begin count <= 0; led <= 6'b111110; end
                    else begin count <= count + 1'b1; if (&count) led <= {led[4:0], led[5]}; end
            endmodule
            """.trimIndent() + "\n",
        )
        Files.writeString(source.resolve("spare.v"), "module spare(input a, output b); assign b = a; endmodule\n")
        val catalog = GowinEdaDevices.catalog(installation)
        val part = GowinEdaDevices.forBoard(catalog, GowinBoards.byId("tangnano9k")!!) ?: error("The catalog has no Tang Nano 9K part")
        val files = GowinEdaNewProject.files(
            GowinEdaNewProjectSettings(
                name = "blinky", directory = directory, device = part.toDevice(), topModule = "blinky",
                sources = QuartusNewProject.sources(QuartusNewProject.scan(directory), directory),
                constraints = true, clocks = listOf("clk"), clockMegahertz = 27.0,
            ),
        )
        for ((path, text) in files) Files.writeString(Files.createDirectories(directory.resolve(path).parent).resolve(directory.resolve(path).fileName), text)
        // Pins as the Pins tab assigns them.
        val cst = directory.resolve("src/blinky.cst")
        var constraints = Files.readString(cst)
        constraints = GowinEdaCstText.setLocation(constraints, "clk", "52")
        constraints = GowinEdaCstText.setAttribute(constraints, "clk", "IO_TYPE", "LVCMOS33")
        constraints = GowinEdaCstText.setLocation(constraints, "rst_n", "3")
        for ((bit, pin) in listOf("10", "11", "13", "14", "15", "16").withIndex()) {
            constraints = GowinEdaCstText.setLocation(constraints, "led[$bit]", pin)
            constraints = GowinEdaCstText.setAttribute(constraints, "led[$bit]", "IO_TYPE", "LVCMOS18")
        }
        Files.writeString(cst, constraints)
        return GowinEdaProject.of(directory.resolve("blinky.gprj"))!!
    }

    private fun run(installation: GowinEdaInstallation, project: GowinEdaProject, flow: GowinEdaFlow): String {
        val output = StringBuilder()
        for (step in GowinEdaSteps.create(installation, project, flow, GowinEdaProgramming())) {
            val result = CapturingProcessHandler(step.commandLine { output.appendLine(it) }).runProcess(600_000)
            output.append(result.stdout).append(result.stderr)
            assertEquals("${step.title} failed:\n$output", 0, result.exitCode)
        }
        return output.toString()
    }

    fun testBuildsAndReadsTheReports() {
        val installation = installation() ?: return
        val project = create(installation)
        assertEquals(listOf("blinky.v", "spare.v"), project.verilogSources.map { it.fileName.toString() })
        assertEquals("blinky", project.topModule)
        val output = run(installation, project, GowinEdaFlow.BUILD)
        assertTrue(output, output.contains("Current top module is \"blinky\""))

        val compilation = GowinEdaCompilation(project)
        assertEquals(GowinEdaCompilation.Status.SUCCESSFUL, compilation.status)
        assertEquals(listOf("Synthesis", "Place & Route", "Bitstream"), compilation.steps.map { it.name })
        assertNotNull(compilation.bitstream)
        assertFalse(compilation.estimated)
        assertTrue(compilation.resources.map { it.name }.toString(), compilation.resources.any { it.name == "Logic" })
        val timing = compilation.timing!!
        assertTrue(timing.isMet)
        assertEquals("clk", timing.fmax.single().clock)
        assertEquals(27.0, timing.fmax.single().constraint!!, 0.001)
        val clk = compilation.placedPorts.single { it.port == "clk" }
        assertEquals("52", clk.location)
        assertEquals("LVCMOS33", clk.ioType)
        assertEquals(part(project), compilation.placedPart)
        assertTrue(compilation.reports.map { it.first }.containsAll(listOf("Place & Route Report", "Timing Report", "Synthesis Log")))
        assertTrue(compilation.messages.none { it.severity == GowinEdaSeverity.ERROR })

        // The rows of the Pins tab, with where place and route put each port.
        val module = com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.modules(Files.readString(project.verilogSources.first())).first()
        val rows = GowinEdaPins.rows(module, GowinEdaCstText.statements(Files.readString(project.constraintFile!!)), compilation.placedPorts)
        assertEquals(8, rows.size)
        assertTrue(rows.joinToString("\n"), rows.all { it.placed != null && it.location == it.placed!!.location })
        assertTrue(GowinEdaDevices.packagePins(installation, project.device).any { it.index == "52" && it.bank == 1 })
        assertTrue("LVCMOS33" in GowinEdaDevices.ioStandards(installation, project.device?.name).getValue("input"))
    }

    fun testReportsTheErrorsOfAFailedBuild() {
        val installation = installation() ?: return
        val project = create(installation)
        Files.writeString(project.verilogSources.first(), "module blinky(input clk, output led);\n  wire = ;\nendmodule\n")
        val step = GowinEdaSteps.create(installation, project, GowinEdaFlow.SYNTHESIS, GowinEdaProgramming()).single()
        val result = CapturingProcessHandler(step.commandLine { }).runProcess(600_000)
        assertFalse(result.exitCode == 0)
        val compilation = GowinEdaCompilation(project)
        assertEquals(GowinEdaCompilation.Status.FAILED, compilation.status)
        val error = compilation.messages.first { it.severity == GowinEdaSeverity.ERROR }
        assertEquals(project.verilogSources.first(), Path.of(error.file!!).toRealPath())
        assertEquals(2, error.line)
    }

    fun testActionsAreRegistered() {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val ids = listOf(
            "NewProject", "Synthesize", "PlaceAndRoute", "Build", "Program", "BuildAndProgram", "ShowOverview", "ShowPins",
            "ShowMessages", "Reports", "ChangeDevice", "OpenConstraints", "OpenProjectFile", "OpenInGowin", "Refresh",
            "Toolbar", "AddFile", "RemoveFile", "SetTopModule",
        )
        for (id in ids) assertNotNull(id, manager.getAction("VerilogToolbox.GowinEda.$id"))
        assertTrue(manager.getAction("VerilogToolbox.GowinEda.Build") is GowinEdaBuildAction)
        assertNotNull(GowinEdaConfigurationType.getInstance())
    }

    private fun part(project: GowinEdaProject): String = project.device!!.partNumber
}
