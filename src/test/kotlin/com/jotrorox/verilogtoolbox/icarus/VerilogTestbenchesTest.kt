package com.jotrorox.verilogtoolbox.icarus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class VerilogTestbenchesTest {
    private fun testbenches(source: String): List<String> = VerilogTestbenches.testbenches(source).map { it.name }

    @Test
    fun modulesWithoutPortsAreTestbenches() = assertEquals(
        listOf("tb", "empty_ports", "with_parameters"),
        testbenches(
            """
            module counter(input clk, output reg [3:0] q); endmodule
            module tb; endmodule
            module empty_ports(); endmodule
            module with_parameters #(parameter W = (8 + 1)) (); endmodule
            module parameters_and_ports #(parameter W = 8) (input [W-1:0] d); endmodule
            """.trimIndent()
        ),
    )

    @Test
    fun lifetimeQualifierAndCommentsAreSkipped() = assertEquals(
        listOf("tb"),
        testbenches("// module in_comment;\nmodule /* x */ automatic tb;\nendmodule\n"),
    )

    @Test
    fun reportsTheOffsetOfTheName() {
        val source = "module dut(input a); endmodule\nmodule tb; endmodule"
        assertEquals(source.lastIndexOf("tb"), VerilogTestbenches.testbenches(source).single().nameOffset)
    }

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(path: String, text: String) {
        val file = folder.root.toPath().resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun findsTheTestbenchesBelowADirectory() {
        write("rtl/counter.sv", "module counter(input clk); endmodule")
        write("tb/b_tb.sv", "module b_tb; endmodule")
        write("tb/a_tb.v", "module helper(input x); endmodule\nmodule a_first; endmodule\nmodule a_second(); endmodule")
        write("tb/deep/er/c_tb.SV", "module c_tb; endmodule")
        // Not sources, or not the project's.
        write("tb/defines.svh", "module in_header; endmodule")
        write("tb/notes.txt", "module in_text; endmodule")
        write(".git/old_tb.sv", "module hidden; endmodule")
        val root = folder.root.toPath()
        assertEquals(
            listOf("tb/a_tb.v a_first", "tb/a_tb.v a_second", "tb/b_tb.sv b_tb", "tb/deep/er/c_tb.SV c_tb"),
            VerilogTestbenches.inDirectory(root).map { (file, module) ->
                "${root.relativize(file).joinToString("/")} ${module.name}"
            }.toList(),
        )
        assertEquals(listOf("c_tb"), VerilogTestbenches.inDirectory(root.resolve("tb/deep")).map { it.second.name }.toList())
    }

    @Test
    fun directoryWithoutTestbenchesHasNone() {
        write("rtl/counter.sv", "module counter(input clk); endmodule")
        assertTrue(VerilogTestbenches.inDirectory(folder.root.toPath()).none())
        assertTrue(VerilogTestbenches.inDirectory(folder.root.toPath().resolve("missing")).none())
    }
}
