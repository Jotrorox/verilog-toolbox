package com.jotrorox.verilogtoolbox.gowin.eda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class GowinEdaRunTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    private fun project(options: String? = null): GowinEdaProject {
        val directory = Files.createDirectories(root.resolve("my project"))
        Files.createDirectories(directory.resolve("src"))
        Files.writeString(directory.resolve("src/top.v"), "module top(input clk, output led); endmodule\nmodule leftover(input a, output b); endmodule\n")
        Files.writeString(directory.resolve("src/top.cst"), "IO_LOC \"clk\" 52;\nIO_LOC \"led\" 10;\n")
        val text = GowinEdaProjectText.projectFile(
            GowinEdaDevice("GW1NR-9C", "GW1NR-LV9QN88PC6/I5", "gw1nr9c-004"),
            listOf(GowinEdaFileKind.VERILOG to "src/top.v", GowinEdaFileKind.CST to "src/top.cst"),
        )
        return GowinEdaProject.parse(directory.resolve("top.gprj"), text, options)
    }

    @Test
    fun scriptSetsTheTopModuleTheOptionsLeaveOut() {
        val worked = project()
        // GOWIN EDA alone would take `leftover`, which nothing instantiates either; the constraints say `top`.
        assertEquals("top", worked.topModule)
        val script = GowinEdaSteps.script(worked, "all")
        assertTrue(script, script.contains("open_project {" + worked.file.toString().replace('\\', '/') + "}"))
        assertTrue(script.contains("set_option -top_module top\n"))
        assertTrue(script.endsWith("run all\n"))

        val set = project(GowinEdaOptionsText.setString(null, "TopModule", "leftover"))
        val own = GowinEdaSteps.script(set, "syn")
        assertFalse("the options name it already", own.contains("set_option"))
        assertTrue(own.endsWith("run syn\n"))
    }

    @Test
    fun programmerOperationsFollowTheFamily() {
        assertEquals(2, GowinEdaProgrammer.operation("GW1NR-9C", GowinEdaTarget.SRAM, verify = false))
        assertEquals(4, GowinEdaProgrammer.operation("GW1NR-9C", GowinEdaTarget.SRAM, verify = true))
        assertEquals(5, GowinEdaProgrammer.operation("GW1NR-9C", GowinEdaTarget.FLASH, verify = false))
        assertEquals(8, GowinEdaProgrammer.operation("GW2AR-18C", GowinEdaTarget.FLASH, verify = false))
        assertEquals(54, GowinEdaProgrammer.operation("GW5A-25A", GowinEdaTarget.FLASH, verify = true))
        val arguments = GowinEdaProgrammer.arguments(
            "GW1NR-9C", 2, Path.of("impl/pnr/top.fs"),
            GowinEdaProgramming(cable = "Gowin USB Cable(GWU2X)", frequency = "15MHz", options = "--channel 1"),
        )
        assertEquals(
            listOf("--device", "GW1NR-9C", "--run", "2", "--fsFile", Path.of("impl/pnr/top.fs").toString(),
                "--cable", "Gowin USB Cable(GWU2X)", "--frequency", "15MHz", "--channel", "1"),
            arguments,
        )
        assertEquals(listOf("--device", "GW1NR-9C", "--run", "2", "--fsFile", "x.fs"), GowinEdaProgrammer.arguments("GW1NR-9C", 2, Path.of("x.fs"), GowinEdaProgramming()))
    }

    @Test
    fun conclusionListsTheErrorsOfAFailedRun() {
        val errors = listOf(GowinEdaMessage(GowinEdaSeverity.ERROR, "EX3863", "Syntax error near 'wire'", "C:\\p\\top.v", 21))
        val lines = GowinEdaConsole.conclusion(project(), GowinEdaFlow.BUILD, 1, errors, 3_000).map { it.first }
        assertEquals("Run All of top failed after 3 s with 1 error:", lines[1])
        assertEquals("  (EX3863) Syntax error near 'wire' (\"C:\\p\\top.v\":21)", lines[2])
    }

    @Test
    fun newProjectFilesAreGowinEdasOwn() {
        val directory = Files.createDirectories(root.resolve("blinky"))
        val sources = listOf(directory.resolve("src/blinky.sv"), directory.resolve("src/divider.v"))
        val files = GowinEdaNewProject.files(
            GowinEdaNewProjectSettings(
                name = "blinky", directory = directory, device = GowinEdaDevice("GW1NR-9C", "GW1NR-LV9QN88PC6/I5", "gw1nr9c-004"),
                topModule = "blinky", sources = sources, constraints = true, clocks = listOf("clk"), clockMegahertz = 27.0,
            ),
        )
        assertEquals(listOf("blinky.gprj", "impl/project_process_config.json", "src/blinky.cst", "src/blinky.sdc"), files.keys.toList())
        val project = GowinEdaProject.parse(directory.resolve("blinky.gprj"), files.getValue("blinky.gprj"), files.getValue("impl/project_process_config.json"))
        assertEquals(sources.sorted(), project.verilogSources)
        assertEquals(directory.resolve("src/blinky.cst"), project.constraintFile)
        assertEquals("blinky", project.topModule)
        assertEquals("Vlg_Std_Sysv2017", project.options.verilogStandard)
        assertTrue(files.getValue("src/blinky.sdc").contains("create_clock -name clk -period 37.037 -waveform {0 18.519} [get_ports {clk}]"))
        assertEquals("my_board_2", GowinEdaNewProject.sanitizeName("my-board 2"))
        assertEquals("_9k", GowinEdaNewProject.sanitizeName("9k"))
        assertFalse(GowinEdaNewProject.isValidName("tang-nano"))
    }
}
