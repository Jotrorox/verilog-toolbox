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
import java.nio.file.attribute.FileTime

class OpenFpgaLoaderProjectTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    /** The Makefile of a Tang Primer 25K project, after apicula's examples. */
    private val primer25k = """
        # Tang Primer 25K: yosys -> nextpnr-himbaechel -> gowin_pack -> openFPGALoader
        TOP     ?= top
        SRCS    ?= $(wildcard src/*.v)
        CST     ?= constraints/primer25k.cst
        BUILD   ?= build

        DEVICE  = GW5A-LV25MG121NES
        FAMILY  = GW5A-25A
        BOARD   = tangprimer25k

        LOADER  ?= openFPGALoader

        .PHONY: all synth pnr load flash clean
        .DEFAULT_GOAL := all

        all: $(BUILD)/$(TOP).fs

        $(BUILD)/$(TOP)-synth.json: $(SRCS) | $(BUILD)
        →$(YOSYS) -q -l $(BUILD)/yosys.log -p "read_verilog $(SRCS); synth_gowin -top $(TOP) -family gw5a -json $@"

        $(BUILD)/$(TOP)-pnr.json: $(BUILD)/$(TOP)-synth.json $(CST)
        →$(NEXTPNR) --json $< --write $@ --top $(TOP) --device $(DEVICE) --freq 50 \
        →--vopt family=$(FAMILY) --vopt cst=$(CST) --vopt sspi_as_gpio \
        →-l $(BUILD)/nextpnr.log

        $(BUILD)/$(TOP).fs: $(BUILD)/$(TOP)-pnr.json
        →$(PACK) --sspi_as_gpio --cpu_as_gpio -d $(FAMILY) -o $@ $<

        # Load into SRAM (lost on power cycle)
        load: $(BUILD)/$(TOP).fs
        →$(LOADER) -b $(BOARD) $<

        # Write to SPI flash (persistent)
        flash: $(BUILD)/$(TOP).fs
        →$(LOADER) -b $(BOARD) -f $<

        clean:
        →rm -rf $(BUILD)
    """.trimIndent().replace("→", "\t") // Recipe lines start with a tab.

    @Test
    fun loaderInvocationsAreReadFromTheRecipes() {
        val makefile = Makefile.parse(primer25k)
        assertEquals(
            listOf(
                Makefile.Invocation("load", "build/top.fs", "tangprimer25k", "", "", FpgaDestination.SRAM, false, ""),
                Makefile.Invocation("flash", "build/top.fs", "tangprimer25k", "", "", FpgaDestination.FLASH, false, ""),
            ),
            makefile.invocations(),
        )
        assertTrue(makefile.makes("build/top.fs"))
        assertTrue(makefile.makes("./build/top.fs"))
        assertFalse(makefile.makes("build/other.fs"))
        assertEquals("GW5A-LV25MG121NES", makefile.value("DEVICE"))
    }

    @Test
    fun variablesAndFunctionsAreExpanded() {
        val makefile = Makefile.parse(
            listOf(
                "PROJ = blinky",
                "PROJ ?= ignored",
                "OUT := out/\$(PROJ)",
                "PROJ = blinky2",
                "FLAGS = -b",
                "FLAGS += icebreaker",
                "JSON = \$(OUT).json",
                "BIN = \$(JSON:.json=.bin)",
                "NAME = \$(basename \$(notdir \$(BIN)))",
                "PART = \$(strip   xc7a35t  )",
                "IFS = \$(if \$(PART),yes,no)",
                "SUB = \$(patsubst %.json,%.asc,\$(JSON))",
                "DOLLAR = \$\$HOME",
            ).joinToString("\n")
        )
        assertEquals("out/blinky", makefile.value("OUT")) // Simple: expanded when assigned.
        assertEquals("-b icebreaker", makefile.value("FLAGS"))
        assertEquals("out/blinky.bin", makefile.value("BIN"))
        assertEquals("blinky", makefile.value("NAME"))
        assertEquals("xc7a35t", makefile.value("PART"))
        assertEquals("yes", makefile.value("IFS"))
        assertEquals("out/blinky.asc", makefile.value("SUB"))
        assertEquals("\$HOME", makefile.value("DOLLAR"))
        assertEquals("", makefile.value("UNSET"))
    }

    @Test
    fun patternRulesAndOtherCommandsAreUnderstood() {
        val makefile = Makefile.parse(
            listOf(
                "BOARD ?= ulx3s",
                "%.bit: %.config",
                "\tecppack --compress \$< \$@",
                "prog-%: %.bit",
                "\t@openFPGALoader --board=\$(BOARD) --freq 3000000 --verify \$< && echo done",
                "detect:",
                "\topenFPGALoader --detect",
                "prog-ext: blinky.bit",
                "\t-/opt/oss/bin/openFPGALoader -c ft2232 --fpga-part LFE5U-85F-6BG381C -f --external-flash --verify blinky.bit",
                "prog-dbg: TARGET = debug",
            ).joinToString("\n")
        )
        assertEquals(
            listOf(
                // `$<` of a pattern rule stays a pattern: no bitstream, but the board and the options.
                Makefile.Invocation("prog-%", "%.bit", "ulx3s", "", "", FpgaDestination.SRAM, false, "--freq 3000000"),
                Makefile.Invocation("prog-ext", "blinky.bit", "", "ft2232", "LFE5U-85F-6BG381C", FpgaDestination.EXTERNAL_FLASH, true, ""),
            ),
            makefile.invocations(),
        )
        assertTrue(makefile.makes("blinky.bit"))
        assertTrue(makefile.makes("sub/blinky.bit"))
        assertFalse(makefile.makes("blinky.fs"))
    }

    @Test
    fun continuationsCommentsAndConditionalsAreSkippedOver() {
        val makefile = Makefile.parse(
            listOf(
                "ifeq (\$(OS),Windows_NT)",
                "LOADER = openFPGALoader.exe",
                "else",
                "LOADER = openFPGALoader # the programmer",
                "endif",
                "define HELP",
                "load: nothing",
                "endef",
                "load: top.bit",
                "\t\$(LOADER) \\",
                "\t  -b arty_a7_35t \\",
                "\t  top.bit",
            ).joinToString("\n")
        )
        assertEquals(
            listOf(Makefile.Invocation("load", "top.bit", "arty_a7_35t", "", "", FpgaDestination.SRAM, false, "")),
            makefile.invocations(),
        )
    }

    /** A project laid out as a Tang Primer 25K project is, its bitstream built after its sources when [built]. */
    private fun primerProject(built: Boolean = true): Path {
        val project = Files.createDirectories(root.resolve("minepga"))
        Files.writeString(project.resolve("Makefile"), primer25k)
        val source = Files.writeString(Files.createDirectories(project.resolve("src")).resolve("top.v"), "module top; endmodule\n")
        Files.writeString(Files.createDirectories(project.resolve("constraints")).resolve("primer25k.cst"), "IO_LOC \"clk\" E2;\n")
        Files.setLastModifiedTime(source, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(project.resolve("constraints/primer25k.cst"), FileTime.fromMillis(1_000_000))
        if (built) {
            val build = Files.createDirectories(project.resolve("build"))
            for (name in listOf("top-synth.json", "top-pnr.json", "top.fs")) {
                Files.setLastModifiedTime(Files.writeString(build.resolve(name), "x"), FileTime.fromMillis(2_000_000))
            }
        }
        return project
    }

    @Test
    fun makefileSaysHowToProgramAndBuild() {
        val project = primerProject()
        val sram = OpenFpgaLoaderProject.detect(project, FpgaDestination.SRAM)
        assertEquals(project.resolve("build/top.fs"), sram.bitstream)
        assertEquals(FpgaTarget(board = "tangprimer25k"), sram.target)
        assertEquals("make build/top.fs", sram.buildCommand)
        assertEquals("Makefile, rule load", sram.source)

        val flash = OpenFpgaLoaderProject.detect(project, FpgaDestination.FLASH)
        assertEquals(FpgaTarget(board = "tangprimer25k", destination = FpgaDestination.FLASH), flash.target)
        assertEquals("Makefile, rule flash", flash.source)
    }

    @Test
    fun bitstreamThatIsNotBuiltYetIsFoundInTheMakefile() {
        val project = primerProject(built = false)
        val programming = OpenFpgaLoaderProject.detect(project, FpgaDestination.SRAM)
        assertEquals(project.resolve("build/top.fs"), programming.bitstream)
        assertEquals("make build/top.fs", programming.buildCommand)
    }

    @Test
    fun makefileInASubdirectoryIsRunThere() {
        val project = Files.createDirectories(root.resolve("boards"))
        val board = Files.createDirectories(project.resolve("icebreaker"))
        Files.writeString(board.resolve("Makefile"), "blinky.bin: blinky.asc\n\ticepack \$< \$@\nprog: blinky.bin\n\topenFPGALoader -b ice40_generic \$<\n")
        val bitstream = Files.writeString(board.resolve("blinky.bin"), "x")
        val programming = OpenFpgaLoaderProject.detect(project, FpgaDestination.SRAM, bitstream)
        assertEquals(bitstream, programming.bitstream)
        assertEquals("ice40_generic", programming.target.board)
        assertEquals("make -C icebreaker blinky.bin", programming.buildCommand)
        assertEquals(board.resolve("Makefile"), OpenFpgaLoaderProject.findMakefile(project, board))
        assertNull(OpenFpgaLoaderProject.findMakefile(project, project))
    }

    @Test
    fun withoutMakefileTheNewestBitstreamIsTaken() {
        val project = Files.createDirectories(root.resolve("plain"))
        fun write(path: String, time: Long) =
            Files.setLastModifiedTime(Files.writeString(Files.createDirectories(project.resolve(path).parent).resolve(project.resolve(path).fileName), "x"), FileTime.fromMillis(time))
        write("out/old.bit", 1_000)
        write("out/new.bit", 2_000)
        write("out/firmware.bin", 3_000) // A .bin is as often firmware: after the others.
        write(".git/objects/stale.bit", 4_000)
        write("cmake-build-debug/CMakeFiles/3.30/CMakeDetermineCompilerABI_C.bin", 5_000)
        assertEquals(
            listOf(project.resolve("out/new.bit"), project.resolve("out/old.bit"), project.resolve("out/firmware.bin")),
            OpenFpgaLoaderProject.bitstreams(project),
        )
        val programming = OpenFpgaLoaderProject.detect(project, FpgaDestination.SRAM)
        assertEquals(project.resolve("out/new.bit"), programming.bitstream)
        assertEquals(FpgaTarget(), programming.target)
        assertEquals("", programming.buildCommand)
        assertNull(programming.source)
    }

    @Test
    fun sourceChangedAfterTheBuildIsNoticed() {
        val project = primerProject()
        val bitstream = project.resolve("build/top.fs")
        assertNull(OpenFpgaLoaderProject.newerSource(project, bitstream))
        Files.setLastModifiedTime(project.resolve("constraints/primer25k.cst"), FileTime.fromMillis(3_000_000))
        assertEquals(project.resolve("constraints/primer25k.cst"), OpenFpgaLoaderProject.newerSource(project, bitstream))
    }

    @Test
    fun pathsAreGivenRelativeToTheProject() {
        val project = root.resolve("p")
        assertEquals("build/top.fs", OpenFpgaLoaderProject.relative(project, project.resolve("build/top.fs")))
        assertEquals(".", OpenFpgaLoaderProject.relative(project, project))
        assertEquals(root.resolve("elsewhere.bit").toString(), OpenFpgaLoaderProject.relative(project, root.resolve("elsewhere.bit")))
    }
}
