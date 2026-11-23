package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class QuartusNewProjectTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun sanitizesNamesToWhatQuartusTakes() {
        assertEquals("verilog-toolbox", QuartusNewProject.sanitizeName("verilog-toolbox"))
        assertEquals("my_blinky_v2", QuartusNewProject.sanitizeName("my blinky (v2)"))
        assertEquals("led_chaser", QuartusNewProject.sanitizeName(" led.chaser "))
        assertEquals("project", QuartusNewProject.sanitizeName("!!!"))
        assertEquals("project", QuartusNewProject.sanitizeName(""))
    }

    @Test
    fun validatesNames() {
        assertTrue(QuartusNewProject.isValidName("blinky"))
        assertTrue(QuartusNewProject.isValidName("led-chaser_2"))
        assertFalse(QuartusNewProject.isValidName(""))
        assertFalse(QuartusNewProject.isValidName("my project"))
        assertFalse(QuartusNewProject.isValidName("top.v"))
    }

    @Test
    fun recognizesClockNames() {
        for (name in listOf("clk", "CLK", "clock", "sys_clk", "clk_i", "i_clk", "CLOCK_50", "CLOCK2_50", "MAX10_CLK1_50",
            "clk50m", "spi_sclk", "ADC_CLK_10")) {
            assertTrue(name, QuartusNewProject.isClockName(name))
        }
        for (name in listOf("clk_en", "clken", "clock_enable", "clk_div", "reset", "data_in", "block", "pll_locked")) {
            assertFalse(name, QuartusNewProject.isClockName(name))
        }
    }

    @Test
    fun findsTheClockInputsOfAModule() {
        val module = VerilogModuleHeaders.modules(
            """
                module blinky (
                    input  wire       CLOCK_50,
                    input  wire       clk_en,
                    input  wire [1:0] clk_sel,
                    input  wire [1:0] clk,
                    output wire       clk_out,
                    input  logic      spi_sclk,
                    output reg  [7:0] led
                );
                endmodule
            """.trimIndent()
        ).single()
        assertEquals(listOf("CLOCK_50", "spi_sclk"), QuartusNewProject.clockPorts(module))
    }

    @Test
    fun givesPeriodsInNanoseconds() {
        assertEquals("20.000", QuartusNewProject.period(50.0))
        assertEquals("80.000", QuartusNewProject.period(12.5))
        assertEquals("8.138", QuartusNewProject.period(122.88))
    }

    @Test
    fun theCreatedFilesReadBackAsTheProject() {
        val directory = temporary.root.toPath().toAbsolutePath().normalize()
        val top = directory.resolve("rtl/blinky.sv")
        val counter = directory.resolve("rtl/counter.v")
        val settings = QuartusNewProjectSettings(
            name = "blinky",
            directory = directory,
            family = "MAX 10",
            device = "10M50DAF484C7G",
            topLevelEntity = "blinky",
            sources = listOf(top, counter),
            clocks = listOf("clk"),
            clockMegahertz = 50.0,
        )
        val files = QuartusNewProject.files(settings)
        assertEquals(listOf("blinky.qpf", "blinky.qsf", "blinky.sdc"), files.keys.toList())
        for ((name, text) in files) Files.writeString(directory.resolve(name), text)

        val project = QuartusProject.of(directory.resolve("blinky.qpf"))!!
        assertEquals(listOf("blinky"), project.revisions)
        val revision = project.revision()
        assertEquals("MAX 10", revision.family)
        assertEquals("10M50DAF484C7G", revision.device)
        assertEquals("blinky", revision.topLevelEntity)
        assertEquals("ALL", revision.global("NUM_PARALLEL_PROCESSORS"))
        assertEquals(
            listOf(
                QuartusFile(top, QuartusFileKind.SYSTEMVERILOG),
                QuartusFile(counter, QuartusFileKind.VERILOG),
                QuartusFile(directory.resolve("blinky.sdc"), QuartusFileKind.SDC),
            ).sortedBy { it.path.toString() },
            revision.listedFiles.sortedBy { it.path.toString() },
        )
        assertEquals(listOf(top, counter).sorted(), revision.verilogSources.sorted())
        // Files below the project are named relative to it, as Quartus names them.
        assertTrue(files.getValue("blinky.qsf").contains("SYSTEMVERILOG_FILE rtl/blinky.sv"))

        val commands = QuartusScript.commands(files.getValue("blinky.sdc"))
        assertEquals(
            listOf(
                listOf("create_clock", "-name", "clk", "-period", "20.000", "[get_ports clk]"),
                listOf("derive_pll_clocks"),
                listOf("derive_clock_uncertainty"),
            ),
            commands.map { it.words },
        )
    }

    @Test
    fun withoutAClockTheConstraintsAreATemplate() {
        val text = QuartusNewProject.timingConstraints("blinky", "blinky", emptyList(), 50.0)
        val commands = QuartusScript.commands(text).map { it.command }
        assertEquals(listOf("derive_pll_clocks", "derive_clock_uncertainty"), commands)
        assertTrue(text.contains("# create_clock -name clk -period 20.000 [get_ports clk]"))
    }

    @Test
    fun withoutConstraintsThereIsNoSdcFile() {
        val directory = temporary.root.toPath()
        val files = QuartusNewProject.files(
            QuartusNewProjectSettings("blinky", directory, "MAX 10", "10M08SAE144C8G", "top", emptyList(), clocks = null)
        )
        assertEquals(listOf("blinky.qpf", "blinky.qsf"), files.keys.toList())
        val commands = QuartusScript.commands(files.getValue("blinky.qsf"))
        assertTrue(commands.none { QuartusFileKind.ofAssignment(it.name) != null })
        assertEquals("top", commands.last { it.name == "TOP_LEVEL_ENTITY" }.value)
    }

    @Test
    fun findsTheSourcesButNotTestbenchesOrToolOutput() {
        val root = temporary.root.toPath().toAbsolutePath().normalize()
        write(root, "rtl/blinky.sv", "module blinky(input clk, output led); counter c(.clk(clk)); endmodule")
        write(root, "rtl/counter.v", "module counter(input clk); endmodule")
        write(root, "rtl/pkg.sv", "package defs; localparam int W = 8; endpackage")
        write(root, "rtl/defs.vh", "`define W 8")
        write(root, "tb/blinky_tb.sv", "module blinky_tb; blinky dut(); endmodule")
        write(root, "db/blinky.v", "module stale(input a); endmodule")
        write(root, "output_files/x.v", "module stale2(input a); endmodule")
        write(root, ".git/y.sv", "module hidden(input a); endmodule")
        write(root, "Simulation/z.v", "module sim(input a); endmodule")

        val files = QuartusNewProject.scan(root)
        assertEquals(
            listOf("rtl/blinky.sv", "rtl/counter.v", "rtl/pkg.sv").map { root.resolve(it) },
            QuartusNewProject.sources(files, root),
        )
        // A directory below the scanned one has the sources below it.
        assertEquals(
            listOf("rtl/blinky.sv", "rtl/counter.v", "rtl/pkg.sv").map { root.resolve(it) },
            QuartusNewProject.sources(files, root.resolve("rtl")),
        )
        assertEquals(emptyList<Path>(), QuartusNewProject.sources(files, root.resolve("tb")))
        assertEquals(listOf("blinky", "counter"), QuartusNewProject.designs(files).map { it.name })
        assertTrue(files.single { it.path.endsWith("blinky_tb.sv") }.isTestbench)
    }

    @Test
    fun proposesTheModuleNamedLikeTheProject() {
        val designs = VerilogModuleHeaders.modules(
            "module counter(input clk); endmodule\nmodule Blinky(input clk); endmodule\nmodule blinky(input clk); endmodule"
        )
        assertEquals("blinky", QuartusNewProject.defaultTopLevelEntity(designs, "blinky"))
        assertEquals("Blinky", QuartusNewProject.defaultTopLevelEntity(designs.take(2), "blinky"))
        assertEquals("counter", QuartusNewProject.defaultTopLevelEntity(designs, "uart"))
        assertNull(QuartusNewProject.defaultTopLevelEntity(emptyList(), "uart"))
    }

    private fun write(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
