package com.jotrorox.verilogtoolbox.gowin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class GowinProjectTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    private fun write(path: String, text: String): Path {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    @Test
    fun boardsByName() {
        assertEquals("tangprimer25k", GowinBoards.named("primer25k")?.id)
        assertEquals("tangprimer25k", GowinBoards.named("// Tang Primer 25K pin constraints")?.id)
        assertEquals("tangnano9k", GowinBoards.named("tangnano9k")?.id)
        assertEquals("tangnano9k", GowinBoards.named("Tang Nano 9K")?.id)
        assertEquals("tangnano", GowinBoards.named("tangnano.cst")?.id)
        assertEquals("tangprimer20k", GowinBoards.named("primer20k")?.id)
        assertNull(GowinBoards.named("pins"))
        assertNull(GowinBoards.named("primer20k and primer25k"))
    }

    @Test
    fun boardFromTheConstraintFileOrItsComment() {
        assertEquals("tangprimer25k", GowinProject.board(write("constraints/primer25k.cst", ""))?.id)
        val named = write("pins.cst", "// Tang Nano 20K\nIO_LOC \"clk\" 4;\n")
        assertEquals("tangnano20k", GowinProject.board(named)?.id)
        assertNull(GowinProject.board(write("other.cst", "IO_LOC \"clk\" 4; // tangnano9k after the head\n")))
    }

    @Test
    fun sourcesLeaveOutTestbenchesAndBuildDirectories() {
        val top = write("src/top.v", "module top(input clk, output led); endmodule\n")
        val pkg = write("src/defs.sv", "package defs; endpackage\n")
        write("src/top_tb.v", "module top_tb; top dut(); endmodule\n")
        write("build/gowin/old.v", "module old(input a); endmodule\n")
        write(".idea/x.v", "module x(input a); endmodule\n")
        write("src/defs.vh", "`define WIDTH 8\n")
        assertEquals(listOf(pkg, top), GowinProject.sources(root))
        assertEquals(listOf(root.resolve("src")), GowinProject.includeDirectories(root))
    }

    @Test
    fun topModuleIsTheOneTheConstraintsPlace() {
        val sources = listOf(
            write("src/i2c_master.v", "module i2c_master(input clk, input rst, output scl, inout sda); endmodule\n"),
            write("src/top.v", "module top(input clk, input key_i, output [7:0] led); i2c_master #(.DIV(10)) i2c (.clk(clk)); endmodule\n"),
        )
        val constraints = write("constraints/primer25k.cst", "IO_LOC \"clk\" E2;\nIO_LOC \"led[0]\" G5;\nIO_LOC \"key_i\" H11;\n")
        assertEquals("top", GowinProject.topModule(sources, constraints))
        // Without constraints, the module nothing instantiates.
        assertEquals("top", GowinProject.topModule(sources, null))
    }

    @Test
    fun modulesDefinedTwice() {
        val v = write("src/uart.v", "module uart(input clk); endmodule\n")
        val sv = write("src/uart.sv", "module uart(input logic clk); endmodule\n")
        val top = write("src/top.v", "module top(input clk); uart u(.clk(clk)); endmodule\n")
        assertEquals(mapOf("uart" to listOf(v, sv)), GowinProject.duplicateModules(listOf(v, sv, top)))
        assertEquals(emptyMap<String, List<Path>>(), GowinProject.duplicateModules(listOf(v, top)))
    }

    @Test
    fun constraintFilesNearestFirst() {
        val deep = write("boards/primer/primer25k.cst", "")
        val near = write("pins.cst", "")
        write("build/gowin/copy.cst", "")
        assertEquals(listOf(near, deep), GowinProject.constraintFiles(root))
    }

    @Test
    fun portConstraints() {
        val text = "IO_LOC  \"led[3]\" J5;\nIO_PORT \"led[3]\" IO_TYPE=LVCMOS33;\nINS_LOC \"q\" R2C3;\nIO_LOC \"clk\" E2;"
        val constraints = GowinCst.portConstraints(text)
        assertEquals(listOf("IO_LOC" to "led[3]", "IO_PORT" to "led[3]", "IO_LOC" to "clk"), constraints.map { it.keyword to it.name })
        assertEquals("led", constraints[0].port)
        assertEquals(3, constraints[0].bit)
        assertNull(constraints[2].bit)
        assertEquals("\"led[3]\"", text.substring(constraints[0].nameRange))
        assertEquals(setOf("led[3]", "clk"), GowinCst.locatedNames(text))
    }
}
