package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
