package com.jotrorox.verilogtoolbox

import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Direction.INOUT
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Direction.INPUT
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Direction.INTERFACE
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Direction.OUTPUT
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Parameter
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Port
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerilogModuleHeadersTest {
    private fun module(source: String) = VerilogModuleHeaders.modules(source.trimIndent()).single()

    @Test
    fun ansiPortsKeepTheirTypesAndDimensions() = assertEquals(
        listOf(
            Port("clk", INPUT),
            Port("s", INPUT, signed = true, packed = "[W-1:0]"),
            Port("grid", INPUT, packed = "[1:0][W-1:0]"),
            Port("mem", INPUT, packed = "[7:0]", unpacked = "[0:N-1][2]"),
            Port("q", OUTPUT, packed = "[W-1:0]"),
            Port("bus", INOUT, packed = "[7:0]"),
            Port("count", INPUT, dataType = "int unsigned"),
            Port("state", OUTPUT, dataType = "pkg::state_t [1:0]"),
            Port("idx", OUTPUT, packed = "[\$clog2(W)-1:0]"),
        ),
        module(
            """
            module dut (
                input clk,
                input wire signed [W-1:0] s,
                input logic [1:0][W-1:0] grid,
                input var logic [7:0] mem [0:N-1][2],
                output reg [W-1:0] q, // comment, with a comma
                inout tri [7:0] bus,
                input int unsigned count,
                output pkg::state_t [1:0] state,
                (* keep = "true" *) output wire [${'$'}clog2(W)-1:0] idx
            );
            endmodule
            """
        ).ports,
    )

    @Test
    fun portsOfOneDeclarationShareItButNotItsDimensionsOrDefault() = assertEquals(
        listOf(
            Port("a", INPUT, packed = "[3:0]", default = "4'd1"),
            Port("b", INPUT, packed = "[3:0]", unpacked = "[2]"),
            Port("c", INPUT, packed = "[3:0]"),
            // A type without a direction inherits the direction only.
            Port("d", INPUT, dataType = "real"),
            Port("y", OUTPUT),
            Port("z", OUTPUT),
        ),
        module("module dut (input [3:0] a = 4'd1, b [2], c, real d, output y, z); endmodule").ports,
    )

    @Test
    fun verilog1995PortsAreDeclaredInTheBody() {
        val module = module(
            """
            module dut (clk, d, q, missing);
                parameter WIDTH = 8, DEPTH = 4;
                localparam TOP = WIDTH - 1;
                localparam IDLE = 0;
                input clk;
                input signed [TOP:0] d;
                output reg [TOP:0] q;
                reg [TOP:0] next;
                function [7:0] f;
                    input [7:0] d;
                    f = d;
                endfunction
                task t; output clk; begin end endtask
            endmodule
            """
        )
        assertEquals(
            listOf(
                Port("clk", INPUT),
                Port("d", INPUT, signed = true, packed = "[TOP:0]"),
                Port("q", OUTPUT, packed = "[TOP:0]"),
                Port("missing", INOUT),
            ),
            module.ports,
        )
        // The local parameter no port depends on is left out.
        assertEquals(
            listOf(
                Parameter("WIDTH", default = "8"),
                Parameter("DEPTH", default = "4"),
                Parameter("TOP", default = "WIDTH - 1", isLocal = true),
            ),
            module.parameters,
        )
        assertEquals(listOf("no input, output or inout declaration was found for port 'missing'"), module.notes)
    }

    @Test
    fun parametersKeepTypeAndDefault() = assertEquals(
        listOf(
            Parameter("W", "int", default = "8"),
            Parameter("N", "int", default = "W * 2"),
            Parameter("MASK", "logic [W-1:0]", default = "{W{1'b1}}"),
            Parameter("BARE", default = "f(1, 2)"),
            Parameter("T", default = "logic [3:0]", isType = true),
            Parameter("TABLE", "int", unpacked = "[2]", default = "'{1, 2}"),
            Parameter("REQUIRED", "int"),
            Parameter("AW", "int", default = "\$clog2(N)", isLocal = true),
        ),
        module(
            """
            module dut #(
                parameter int W = 8, N = W * 2,
                parameter logic [W-1:0] MASK = {W{1'b1}},
                parameter BARE = f(1, 2),
                parameter type T = logic [3:0],
                parameter int TABLE [2] = '{1, 2},
                parameter int REQUIRED,
                localparam int AW = ${'$'}clog2(N), UNUSED = 3
            ) (input [AW-1:0] a);
                parameter IN_BODY = 1;
            endmodule
            """
        ).parameters,
    )

    @Test
    fun interfacePortsAreToldApart() = assertEquals(
        listOf(
            Port("clk", INPUT),
            Port("bus", INTERFACE, "my_if.master"),
            Port("plain", INTERFACE, "my_if"),
            Port("generic", INTERFACE, "interface"),
            Port("req", INPUT, "pkg::req_t"),
            Port("state", INPUT, "state_t"),
        ),
        module(
            "module dut (input clk, my_if.master bus, my_if plain, interface generic, input pkg::req_t req, input state_t state); endmodule"
        ).ports,
    )

    @Test
    fun importsIncludesAndDefinesAreKept() {
        val module = module(
            """
            `include "defines.vh" // shared
            `define TOP \
                (`WIDTH - 1)
            module automatic dut import a_pkg::*; import b_pkg::t; #(parameter W = `WIDTH) (input [`TOP:0] d);
            endmodule
            `include "later.vh"
            """
        )
        assertEquals("dut", module.name)
        assertEquals(listOf("import a_pkg::*;", "import b_pkg::t;"), module.imports)
        assertEquals(listOf("`include \"defines.vh\" // shared"), module.includes)
        assertEquals(setOf("TOP"), module.defines.keys)
        assertEquals(listOf(Port("d", INPUT, packed = "[`TOP:0]")), module.ports)
    }

    @Test
    fun everyBranchOfAConditionalHeaderIsRead() {
        val module = module(
            """
            module dut (
                input clk,
            `ifdef WIDE
                input [15:0] d
            `else
                input [7:0] d
            `endif
            );
            endmodule
            """
        )
        assertEquals(listOf(Port("clk", INPUT), Port("d", INPUT, packed = "[15:0]")), module.ports)
        assertTrue(module.notes.single().contains("`ifdef"))
    }

    @Test
    fun modulesAreFoundWithTheirRanges() {
        val source = "module a (input x); endmodule\n// module c (input z);\nmodule tb; endmodule\nmodule b (y); output y; endmodule"
        val modules = VerilogModuleHeaders.modules(source)
        assertEquals(listOf("a", "tb", "b"), modules.map { it.name })
        assertEquals(listOf(true, false, true), modules.map { it.ports.isNotEmpty() })
        assertEquals(0 until 29, modules[0].range)
        assertEquals(source.lastIndexOf("module b") until source.length, modules[2].range)
        assertEquals(source.indexOf("b (y)"), modules[2].nameOffset)
    }

    @Test
    fun brokenHeadersDoNotThrow() {
        for (source in listOf("module", "module dut (", "module dut #(parameter", "module dut (input [3:0", "module dut (a, ; input")) {
            VerilogModuleHeaders.modules(source)
        }
        assertEquals(
            listOf("the port '.a(b)' could not be read"),
            module("module dut (input x, .a(b)); endmodule").notes,
        )
    }
}
