package com.jotrorox.verilogtoolbox.yosys

import com.jotrorox.verilogtoolbox.common.FileList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class YosysSynthesisTest {
    private val root: Path = Path.of("/my project").toAbsolutePath()

    private val design = FileList(
        sources = listOf(root.resolve("rtl/top.sv")),
        includeDirectories = listOf(root.resolve("inc")),
        defines = listOf("WIDTH=8"),
        libraryDirectories = listOf(root.resolve("lib")),
        libraryFiles = listOf(root.resolve("cells.v")),
        libraryExtensions = listOf(".vp"),
        topModules = emptyList(),
        ignored = emptyList(),
        errors = emptyList(),
    )

    @Test
    fun slangReadsTheDesignFromACommandFile() {
        val run = YosysSynthesis.run(design, "top", SynthesisTarget.ICE40, slang = true)
        assertEquals(
            """
                read_slang -f design.f
                hierarchy -check -top top
                synth_ice40 -top top
                tee -q -o stat.txt stat
                tee -q -o stat.json stat -json

            """.trimIndent(),
            run.script,
        )
        assertEquals(
            """
                -I '${root.resolve("inc")}'
                -D 'WIDTH=8'
                -y '${root.resolve("lib")}'
                --libext .v,.sv,.vp
                -v '${root.resolve("cells.v")}'
                '${root.resolve("rtl/top.sv")}'
                --top top

            """.trimIndent(),
            run.commandFile,
        )
    }

    @Test
    fun yosysOwnFrontEndGetsQuotedArguments() {
        val run = YosysSynthesis.run(design, "top", SynthesisTarget.GENERIC, slang = false)
        assertNull(run.commandFile)
        assertEquals(
            """
                read_verilog -sv "-DWIDTH=8" "-I${root.resolve("inc")}" "${root.resolve("rtl/top.sv")}" "${root.resolve("cells.v")}"
                hierarchy -check -top top -libdir "${root.resolve("lib")}"
                synth -top top
                tee -q -o stat.txt stat
                tee -q -o stat.json stat -json

            """.trimIndent(),
            run.script,
        )
    }

    @Test
    fun slangQuotingKeepsPathsLiteral() {
        assertEquals("'C:\\hdl\\my top.sv'", YosysSynthesis.slangQuote("C:\\hdl\\my top.sv"))
        assertEquals("'/a/\$HOME/b.sv'", YosysSynthesis.slangQuote("/a/\$HOME/b.sv"))
        assertEquals("'/it'\"'\"'s/a\"b\$d.sv'", YosysSynthesis.slangQuote("/it's/a\"b\$d.sv"))
    }

    @Test
    fun quotedPathsReadBackAsTheyWere() {
        // The command file has to mean to slang what it says: FileList reads it as slang does.
        val tricky = FileList(
            sources = listOf(Path.of("/it's here/\$x a.sv").toAbsolutePath(), Path.of("/plain/b.sv").toAbsolutePath()),
            includeDirectories = emptyList(), defines = emptyList(), libraryDirectories = emptyList(),
            libraryFiles = emptyList(), libraryExtensions = emptyList(), topModules = emptyList(),
            ignored = emptyList(), errors = emptyList(),
        )
        val folder = kotlin.io.path.createTempDirectory("yosys-test")
        try {
            val file = folder.resolve("design.f")
            java.nio.file.Files.writeString(file, YosysSynthesis.commandFile(tricky, "top"))
            val read = FileList.parse(file, folder) { null }
            assertEquals(tricky.sources, read.sources)
            assertEquals(listOf("top"), read.topModules)
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun genericGatesAreSummedUp() {
        // stat -json of Yosys 0.71 for the demo counter, abridged.
        val json = """
            {
               "modules": { "\\counter": { "num_cells": 10 } },
               "design": {
                  "num_cells": 10,
                  "num_cells_by_type": {
                     "${'$'}_AND_": 1, "${'$'}_NAND_": 1, "${'$'}_NOT_": 1, "${'$'}_SDFFE_PP0P_": 4, "${'$'}_XNOR_": 1, "${'$'}_XOR_": 2
                  }
               }
            }
        """.trimIndent()
        val summary = YosysSynthesis.summary(json)!!
        assertEquals(YosysSynthesis.Summary(cells = 10, luts = 0, flipFlops = 4, latches = 0, memories = 0, dsps = 0), summary)
        assertEquals("10 cells: 4 flip-flops, 6 other", summary.describe())
    }

    @Test
    fun fpgaCellsAreSorted() {
        val json = """
            {"design": {"num_cells": 23, "num_cells_by_type": {
                "SB_LUT4": 8, "SB_CARRY": 3, "SB_DFFE": 2, "SB_DFFSR": 2, "SB_RAM40_4K": 1, "SB_MAC16": 1,
                "${'$'}_DLATCH_P_": 1, "FDRE": 2, "LUT6": 2, "TRELLIS_FF": 1
            }}}
        """.trimIndent()
        val summary = YosysSynthesis.summary(json)!!
        assertEquals(YosysSynthesis.Summary(cells = 23, luts = 10, flipFlops = 7, latches = 1, memories = 1, dsps = 1), summary)
        assertEquals("23 cells: 10 LUTs, 7 flip-flops, 1 latch, 1 block RAM, 1 DSP, 3 other", summary.describe())
    }

    @Test
    fun anEmptyDesignHasNoParts() {
        assertEquals("0 cells", YosysSynthesis.summary("""{"design": {"num_cells": 0}}""")!!.describe())
        assertNull(YosysSynthesis.summary("not json"))
        assertNull(YosysSynthesis.summary("""{"modules": {}}"""))
    }

    @Test
    fun warningsAreCountedFromTheLog() {
        assertEquals(0, YosysSynthesis.warningCount("End of script.\n"))
        assertEquals(
            3,
            YosysSynthesis.warningCount("Found and reported 1 problems.\nWarnings: 2 unique messages, 3 total\nEnd of script.\n"),
        )
        assertEquals(1, YosysSynthesis.warningCount("Warnings: 1 unique message, 1 total\n"))
    }
}
