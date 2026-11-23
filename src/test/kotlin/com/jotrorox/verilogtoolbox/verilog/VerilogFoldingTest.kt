package com.jotrorox.verilogtoolbox.verilog

import org.junit.Assert.assertEquals
import org.junit.Test

class VerilogFoldingTest {
    /** The regions of [source] as "first line-last line" pairs, lines counted from one. */
    private fun lines(source: String): List<String> = VerilogFolding.regions(source).map {
        "${line(source, it.start)}-${line(source, it.end)}"
    }

    private fun line(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1

    /** [source] with its outermost regions collapsed to their placeholders. */
    private fun collapsed(source: String): String {
        val text = StringBuilder()
        var offset = 0
        for (region in VerilogFolding.regions(source)) {
            if (region.start < offset) continue
            text.append(source, offset, region.start).append(region.placeholder)
            offset = region.end
        }
        return text.append(source, offset, source.length).toString()
    }

    @Test
    fun moduleFoldsAfterItsNameAndBeginAfterItsLabel() {
        val source = """
            module automatic foo #(parameter W = 8) (
              input clk
            );
              always @(posedge clk) begin : blk
                q <= d;
              end
            endmodule : foo
        """.trimIndent()
        assertEquals("module automatic foo ... endmodule : foo", collapsed(source))
        assertEquals(listOf("1-7", "4-6"), lines(source))
        assertEquals(
            "  always @(posedge clk) begin : blk ... end",
            collapsed(source.lines().subList(3, 6).joinToString("\n")),
        )
    }

    @Test
    fun functionsAndTasksKeepTheirPrototype() = assertEquals(
        "function automatic int f(int a); ... endfunction\ntask t; ... endtask",
        collapsed("function automatic int f(int a);\n  return a;\nendfunction\ntask t;\n  #1;\nendtask"),
    )

    @Test
    fun caseKeepsItsExpression() = assertEquals(
        "unique casez (f(x)) ... endcase",
        collapsed("unique casez (f(x)) inside\n  1: y = 0;\nendcase"),
    )

    @Test
    fun regionsOnOneLineAreNotFolded() = assertEquals(
        emptyList<String>(),
        lines("module m; initial begin x = 1; end endmodule /* c */\ncase (x) default: ; endcase"),
    )

    @Test
    fun everyKindOfForkAndCaseIsPaired() = assertEquals(
        listOf("1-3", "4-6", "7-9", "10-12", "13-15", "16-18"),
        lines(
            """
            fork : f
              a();
            join
            fork
              b();
            join_any
            fork
              wait fork;
            join_none
            casez (x)
              default: disable fork;
            endcase
            unique case (x) inside
              1: y = 0;
            endcase
            randcase
              1: y = 0;
            endcase
            """.trimIndent()
        ),
    )

    @Test
    fun declarationsWithoutABodyOpenNothing() = assertEquals(
        listOf("1-13", "9-11", "14-18", "19-22"),
        lines(
            """
            class c extends base;
              typedef class later;
              virtual interface bus vif;
              extern function void f();
              extern static task t();
              pure virtual function int g();
              import "DPI-C" context function int h(input int a);
              export "DPI-C" task u;
              covergroup cg with function sample(bit a);
                coverpoint a;
              endgroup
              int last;
            endclass
            interface bus (interface other, interface.mp port);
              modport mp (import task run(), export function int get);
              logic a;
              logic b;
            endinterface
            interface class api;
              pure virtual task v();
              extern module ext (input a);
            endclass
            """.trimIndent()
        ),
    )

    @Test
    fun unbalancedCodeStillFoldsWhatIsBalanced() = assertEquals(
        listOf("1-6", "8-11", "9-10"),
        lines(
            """
            module a;
              initial begin
                x = 1;
              function int f();
              end
            endmodule
            end
            module b;
              always_comb begin
              end
            endmodule
            endcase
            """.trimIndent()
        ),
    )

    @Test
    fun otherPairedKeywords() = assertEquals(
        listOf("1-3", "4-10", "5-9", "6-8", "11-17", "12-15"),
        lines(
            """
            package p;
              localparam int W = 8;
            endpackage
            module m;
              generate
                for (genvar i = 0; i < 2; i++) begin : g
                  assign y[i] = a[i];
                end
              endgenerate
            endmodule
            primitive udp (output q, input d);
              table
                0 : 0;
                1 : 1;
              endtable
              initial q = 0;
            endprimitive
            """.trimIndent()
        ),
    )

    @Test
    fun comments() {
        val source = "// licence\n// line two\n\n/* block\n   comment */\nwire a; // trailing\n// alone\nwire b;\n"
        assertEquals("// licence ...\n\n/*...*/\nwire a; // trailing\n// alone\nwire b;\n", collapsed(source))
    }
}
