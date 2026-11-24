package com.jotrorox.verilogtoolbox.gowin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class GowinBuildTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    private fun spec(board: String = "tangprimer25k"): GowinBuildSpec {
        val source = root.resolve("src/top.v")
        if (!Files.exists(source)) {
            Files.createDirectories(source.parent)
            Files.writeString(source, "module top(input clk, output led); endmodule\n")
            Files.writeString(root.resolve("primer25k.cst"), "IO_LOC \"clk\" E2;\n")
        }
        return GowinBuildSpec(
            GowinBoards.byId(board)!!, "top", root.resolve("primer25k.cst"), GowinBuild.design(root, null),
            root.resolve("build/gowin"), root.resolve("build/gowin/top.fs"),
        )
    }

    @Test
    fun primer25kCommandsAreThoseOfApiculasExamples() {
        val spec = spec()
        val script = GowinBuild.yosysScript(spec, slang = false).script
        assertTrue(script, script.endsWith("synth_gowin -top top -family gw5a -nolutram -json \"${root.resolve("build/gowin/top-synth.json")}\"\n"))
        assertTrue(script, script.startsWith("read_verilog -sv"))
        assertEquals(
            listOf(
                "--json", root.resolve("build/gowin/top-synth.json").toString(), "--write", root.resolve("build/gowin/top-pnr.json").toString(),
                "--device", "GW5A-LV25MG121NES", "--vopt", "cst=${root.resolve("primer25k.cst")}", "--vopt", "sspi_as_gpio",
                "--top", "top", "--freq", "50", "--quiet", "-l", "nextpnr.log",
            ),
            GowinBuild.nextpnrArguments(spec),
        )
        assertEquals(
            listOf("--sspi_as_gpio", "--cpu_as_gpio", "-d", "GW5A-25A", "-o", root.resolve("build/gowin/top.fs").toString(), root.resolve("build/gowin/top-pnr.json").toString()),
            GowinBuild.packArguments(spec),
        )
    }

    @Test
    fun nano9kGivesNextpnrTheFamily() {
        val spec = spec("tangnano9k")
        val arguments = GowinBuild.nextpnrArguments(spec)
        assertTrue(arguments.toString(), arguments.containsAll(listOf("GW1NR-LV9QN88PC6/I5", "family=GW1N-9C")))
        assertTrue(GowinBuild.yosysScript(spec, slang = false).script.contains("synth_gowin -top top -json"))
        assertEquals(listOf("-c", "-d", "GW1N-9C"), GowinBuild.packArguments(spec).take(3))
    }

    @Test
    fun upToDateUntilASourceOrTheSettingsChange() {
        val spec = spec()
        val stamp = GowinBuild.stamp(spec, slang = false)
        assertFalse(GowinBuild.isUpToDate(spec, stamp))
        Files.createDirectories(spec.directory)
        Files.writeString(spec.bitstream, "bitstream")
        Files.writeString(spec.directory.resolve(GowinBuild.STAMP_FILE), stamp)
        for (input in GowinBuild.inputs(spec)) Files.setLastModifiedTime(input, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(spec.bitstream, FileTime.fromMillis(2_000_000))
        assertTrue(GowinBuild.isUpToDate(spec, stamp))

        assertFalse("another board", GowinBuild.isUpToDate(spec, GowinBuild.stamp(spec("tangnano9k"), slang = false)))
        Files.setLastModifiedTime(root.resolve("primer25k.cst"), FileTime.fromMillis(3_000_000))
        assertFalse("changed constraints", GowinBuild.isUpToDate(spec, stamp))
    }

    @Test
    fun frequenciesOfTheRoutedDesign() {
        val log = """
            Info: Max frequency for clock 'clk': 180.00 MHz (PASS at 50.00 MHz)
            Info: Max frequency for clock 'clk_slow': 300.00 MHz (PASS at 50.00 MHz)
            Info: Routing..
            Info: Max frequency for clock 'clk': 155.20 MHz (PASS at 50.00 MHz)
        """.trimIndent()
        assertEquals(
            listOf("'clk': 155.20 MHz (PASS at 50.00 MHz)", "'clk_slow': 300.00 MHz (PASS at 50.00 MHz)"),
            GowinBuild.frequencies(log),
        )
    }
}
