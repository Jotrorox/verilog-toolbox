package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class IcarusTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Finds locations as "matched text -> file:line", resolving only [known] file names. */
    private fun locations(line: String, vararg known: String): List<String> =
        IcarusConsoleFilter.locations(line) { path -> path.takeIf { it in known }?.let { Path.of(it) } }
            .map { "${line.substring(it.start, it.end)} -> ${it.file}:${it.line}" }

    @Test
    fun compilerErrorIsLinked() = assertEquals(
        listOf("tb.v:12 -> tb.v:12"),
        locations("tb.v:12: syntax error", "tb.v"),
    )

    @Test
    fun pathWithSpacesIsLinked() = assertEquals(
        listOf("/my designs/tb.v:3 -> /my designs/tb.v:3"),
        locations("/my designs/tb.v:3: error: Unknown module type: counter", "/my designs/tb.v"),
    )

    @Test
    fun windowsDriveLetterIsPartOfThePath() = assertEquals(
        listOf("C:\\hdl\\tb.v:7 -> C:\\hdl\\tb.v:7"),
        locations("C:\\hdl\\tb.v:7: error: oops", "C:\\hdl\\tb.v"),
    )

    @Test
    fun simulatorMessagesAreLinked() = assertEquals(
        listOf("tb.v:20 -> tb.v:20", "dut.v:4 -> dut.v:4"),
        locations("ERROR: tb.v:20: assertion failed, see dut.v:4", "tb.v", "dut.v"),
    )

    @Test
    fun unknownFilesAndPlainNumbersAreIgnored() {
        assertEquals(emptyList<String>(), locations("other.v:12: syntax error", "tb.v"))
        assertEquals(emptyList<String>(), locations("time 10:30 value 12:5", "tb.v"))
    }

    @Test
    fun sourcesDefaultToTheTestbenchDirectory() {
        val testbench = folder.newFile("tb.v").toPath()
        val design = folder.newFile("counter.sv").toPath()
        folder.newFile("defines.vh")
        folder.newFile("notes.txt")
        assertEquals(listOf(testbench, design), IcarusCommandLines.sources(testbench, folder.root.toPath(), ""))
    }

    @Test
    fun packagesAreCompiledBeforeTheFilesThatImportThem() {
        val testbench = folder.newFile("tb.sv").toPath().also { it.toFile().writeText("import types_pkg::*;\nmodule tb;\nendmodule\n") }
        val design = folder.newFile("alu.sv").toPath().also { it.toFile().writeText("// no package here\nmodule alu;\nendmodule\n") }
        val types = folder.newFile("types_pkg.sv").toPath().also { it.toFile().writeText("`timescale 1ns/1ps\n  package types_pkg;\nendpackage\n") }
        val bus = folder.newFile("bus_pkg.sv").toPath().also { it.toFile().writeText("package automatic bus_pkg;\nendpackage\n") }
        assertEquals(listOf(bus, types, testbench, design), IcarusCommandLines.sources(testbench, folder.root.toPath(), ""))
    }

    @Test
    fun configuredSourcesKeepTheirOrder() {
        val testbench = folder.newFile("tb.sv").toPath()
        val types = folder.newFile("types_pkg.sv").toPath().also { it.toFile().writeText("package types_pkg;\nendpackage\n") }
        val design = folder.newFile("alu.sv").toPath()
        assertEquals(
            listOf(testbench, design, types),
            IcarusCommandLines.sources(testbench, folder.root.toPath(), "alu.sv types_pkg.sv"),
        )
    }

    @Test
    fun configuredSourcesReplaceTheDefault() {
        val testbench = folder.newFile("tb.v").toPath()
        folder.newFile("counter.sv")
        val root = folder.root.toPath()
        assertEquals(
            listOf(testbench, root.resolve("rtl/a.v"), root.resolve("my rtl/b.v")),
            IcarusCommandLines.sources(testbench, root, "rtl/a.v \"my rtl/b.v\" tb.v"),
        )
    }

    private fun fileList(text: String): FileList {
        val root = folder.root.toPath()
        return FileList.parse(folder.newFile("design.f").toPath().also { it.toFile().writeText(text) }, root)
    }

    @Test
    fun fileListBecomesCompilerArguments() {
        val root = folder.root.toPath().toAbsolutePath().normalize()
        val design = fileList("rtl/a.sv -I inc +define+W=8+FAST -y lib +libext+.vp -v cells.v --top chip --std latest")
        assertEquals(
            listOf(
                "-s", "tb", "-I", "$root/inc", "-D", "W=8", "-D", "FAST", "-y", "$root/lib", "-Y", ".sv", "-Y", ".vp",
                "-l", "$root/cells.v", "$root/rtl/a.sv", "$root/tb.v",
            ),
            IcarusCommandLines.designArguments(design, root.resolve("tb.v"), "tb"),
        )
    }

    @Test
    fun fileListNamesTheTopWhenTheRunDoesNot() {
        val root = folder.root.toPath().toAbsolutePath().normalize()
        // A testbench the list names already is not compiled twice, and stays where the list has it.
        val design = fileList("tb.v rtl/a.sv --top tb,glbl")
        assertEquals(
            listOf("-s", "tb", "-s", "glbl", "$root/tb.v", "$root/rtl/a.sv"),
            IcarusCommandLines.designArguments(design, root.resolve("sub/../tb.v"), null),
        )
    }
}
