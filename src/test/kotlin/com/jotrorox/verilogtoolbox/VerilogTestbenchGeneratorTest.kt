package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerilogTestbenchGeneratorTest {
    private fun generate(source: String): String =
        VerilogTestbenchGenerator.generate(VerilogModuleHeaders.modules(source.trimIndent()).single())

    private fun assertLines(text: String, vararg lines: String) {
        for (line in lines) assertTrue("'$line' is missing from:\n$text", text.lines().any { it.trim() == line })
    }

    @Test
    fun clockedModuleGetsClockResetAndDump() = assertEquals(
        """
        `timescale 1ns / 1ps
        `include "defines.vh"

        // Testbench for counter. Simulate it with the run icon next to the module name.
        module counter_tb;
            localparam int WIDTH = `COUNTER_WIDTH;

            logic clk = 0;
            logic rst_n = 0;
            logic en = '0;
            logic [WIDTH-1:0] count;

            counter #(
                .WIDTH(WIDTH)
            ) dut (
                .clk(clk),
                .rst_n(rst_n),
                .en(en),
                .count(count)
            );

            always #5 clk = ~clk;

            initial begin
                ${'$'}dumpfile("counter_tb.vcd");
                ${'$'}dumpvars(0, counter_tb);

                repeat (4) @(negedge clk);
                rst_n = 1;

                // TODO: add stimulus and checks here.
                // Change inputs on the falling edge of clk and check outputs after the rising edge:
                //     @(negedge clk); <input> = <value>;
                //     @(posedge clk); #1; if (<output> !== <expected>) ${'$'}error("...");

                repeat (20) @(posedge clk);
                ${'$'}finish;
            end
        endmodule

        """.trimIndent(),
        generate(
            """
            `include "defines.vh"
            module counter #(parameter int WIDTH = `COUNTER_WIDTH) (
                input logic clk, rst_n, en,
                output logic [WIDTH-1:0] count
            );
            endmodule
            """
        ),
    )

    @Test
    fun combinationalModuleGetsDelays() {
        val text = generate("module alu (input wire [7:0] a, b, output reg [7:0] y); endmodule")
        assertLines(
            text, "logic [7:0] a = '0;", "logic [7:0] b = '0;", "logic [7:0] y;", "alu dut (", ".y(y)",
            "\$dumpfile(\"alu_tb.vcd\");", "\$dumpvars(0, alu_tb);", "#100;", "\$finish;",
        )
        assertFalse(text.contains("always"))
        assertFalse(text.contains("`include"))
    }

    @Test
    fun signalsFollowThePortTypes() = assertLines(
        generate(
            """
            module dut (
                input signed [W-1:0] s, input [3:0] mem [0:N-1][2], input en = 1'b1, input int n, input real r,
                input pkg::req_t req, inout [7:0] bus, inout tri pads [4], output logic [1:0][7:0] grid [2], output T t
            );
            endmodule
            """
        ),
        "logic signed [W-1:0] s = '0;",
        "logic [3:0] mem [0:N-1][2];",
        "foreach (mem[i0, i1]) mem[i0][i1] = '0;",
        // A default port value is what the port has when it is left unconnected.
        "logic en = 1'b1;",
        "int n = 0;",
        "real r = 0.0;",
        "pkg::req_t req;  // TODO: give req an initial value",
        "wire [7:0] bus;",
        "logic [7:0] bus_drv = 'z;  // 'z releases bus",
        "assign bus = bus_drv;",
        "wire pads [4];",
        "logic [1:0][7:0] grid [2];",
        "T t;",
        "// The packages used here have to be compiled before this file.",
    )

    @Test
    fun parametersAreMirroredAndOverridden() {
        val text = generate(
            """
            module dut #(parameter W = 8, parameter [3:0] K, parameter type T = logic, parameter string S = "s",
                         localparam int AW = ${'$'}clog2(W))
                (input [AW-1:0] a, input T t);
            endmodule
            """
        )
        assertLines(
            text,
            "localparam W = 8;",
            "localparam [3:0] K = 0;  // TODO: K has no default",
            "localparam type T = logic;",
            "localparam int AW = \$clog2(W);",
            "// TODO: T, S of dut cannot be set from here and keep their defaults.",
            "dut #(", ".W(W),", ".K(K)", ") dut_ (",
        )
        assertFalse(text.contains(".AW("))
        assertFalse(text.contains(".T("))
        assertFalse(text.contains("localparam string"))
    }

    @Test
    fun macrosOfTheDesignFileAreDefinedAgain() = assertLines(
        generate(
            """
            `define GUARD
            `define W 8
            `define TOP (`W - 1)
            module dut (input [`TOP:0] d);
            endmodule
            """
        ),
        "`ifndef W", "`define W 8", "`ifndef TOP", "`define TOP (`W - 1)", "logic [`TOP:0] d = '0;",
    ).also { assertFalse(generate("`define GUARD\n`define W 8\nmodule dut (input [`W:0] d); endmodule").contains("GUARD")) }

    @Test
    fun whatCannotBeWrittenIsMarked() {
        val text = generate("module dut (input clk, my_if.master bus, output done, .odd(x)); endmodule")
        assertLines(
            text,
            "// TODO: bus is read as an interface port (my_if.master). Instantiate the interface as",
            "// TODO: the port '.odd(x)' could not be read.",
            ".clk(clk),", "// .bus(bus),", ".done(done)",
        )
        assertFalse(text.lines().any { it.trim().startsWith("my_if") })
    }

    @Test
    fun escapedIdentifiersKeepTheirTerminator() {
        val text = generate("module \\top+1 (input \\d[0] , output q); endmodule")
        assertLines(text, "module top_1_tb;", "logic \\d[0]  = '0;", "\\top+1  dut (", ".\\d[0] (\\d[0] ),")
    }

    @Test
    fun clocksAreRecognisedByName() {
        for (name in listOf("clk", "clock", "CLK", "clk_i", "i_clk", "aclk", "pclk", "sys_clk", "wr_clock", "clk_in")) {
            assertTrue(name, VerilogTestbenchGenerator.isClock(name))
        }
        for (name in listOf("clk_en", "clken", "clock_div", "click", "c", "data")) {
            assertFalse(name, VerilogTestbenchGenerator.isClock(name))
        }
    }

    @Test
    fun resetsAreRecognisedWithTheirPolarity() {
        for (name in listOf("rst", "reset", "RESET", "arst", "areset", "srst", "rst_i", "i_rst", "sys_rst", "por_reset")) {
            assertEquals(name, false, VerilogTestbenchGenerator.resetIsActiveLow(name))
        }
        for (name in listOf(
            "rst_n", "rstn", "resetn", "reset_n", "arst_n", "aresetn", "nrst", "nreset", "rst_ni", "rst_b", "i_rst_n",
            "presetn", "hresetn", "sys_rst_n", "RST_N",
        )) {
            assertEquals(name, true, VerilogTestbenchGenerator.resetIsActiveLow(name))
        }
        for (name in listOf("first", "burst", "preset", "rst_cnt", "restart", "n")) {
            assertNull(name, VerilogTestbenchGenerator.resetIsActiveLow(name))
        }
    }

    @Test
    fun resetWithoutClockIsReleasedAfterADelay() =
        assertLines(generate("module dut (input arst_n, input d, output q); endmodule"), "logic arst_n = 0;", "#20;", "arst_n = 1;")

    @Test
    fun fileIsNamedAfterTheModule() = assertEquals(
        "fifo_tb.sv",
        VerilogTestbenchGenerator.fileName(VerilogModuleHeaders.modules("module fifo (input a); endmodule").single()),
    )
}
