package com.jotrorox.verilogtoolbox.waveform

import com.jotrorox.verilogtoolbox.waveform.WaveformSignals.Instance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringReader
import java.nio.file.Files

class WaveformSignalsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val counter = """
        module counter #(parameter WIDTH = 4) (input wire clk, input wire rst, output reg [WIDTH-1:0] count);
          wire wrap = &count;
          always @(posedge clk) count <= rst ? 0 : count + 1;
        endmodule
    """.trimIndent()

    private val testbench = """
        module counter_tb;
          reg clk = 0, rst = 1;
          wire [3:0] count_a, count_b;
          // counter commented (out);
          counter #(.WIDTH(4)) u_a (.clk(clk), .rst(rst), .count(count_a)),
                               u_b (.clk(clk), .rst(rst), .count(count_b));
          pair u_pair [1:0] (.clk(clk));
          initial begin
            ${'$'}dumpfile("counter_tb.vcd");
            ${'$'}dumpvars(0, counter_tb);
            check(count_a);
            #100 ${'$'}finish;
          end
        endmodule
    """.trimIndent()

    @Test
    fun identifierIsFoundAtAndJustAfterTheCaret() {
        val source = "assign wrap = &count;"
        assertEquals("wrap", WaveformSignals.identifierAt(source, source.indexOf("wrap")))
        assertEquals("wrap", WaveformSignals.identifierAt(source, source.indexOf("wrap") + 2))
        assertEquals("count", WaveformSignals.identifierAt(source, source.indexOf(";")))
        assertNull(WaveformSignals.identifierAt(source, source.indexOf("=")))
        assertNull(WaveformSignals.identifierAt("assign x = 1;", 2))
    }

    @Test
    fun instancesAreReadWithParametersArraysAndSeveralNames() {
        assertEquals(
            listOf(
                Instance("counter_tb", "counter", "u_a"),
                Instance("counter_tb", "counter", "u_b"),
                Instance("counter_tb", "pair", "u_pair"),
            ),
            WaveformSignals.instances(testbench),
        )
        assertEquals(emptyList<Instance>(), WaveformSignals.instances(counter))
    }

    @Test
    fun scopesFollowEveryChainOfInstances() {
        val instances = listOf(
            Instance("tb", "core", "dut"),
            Instance("core", "alu", "u_alu0"),
            Instance("core", "alu", "u_alu1"),
            Instance("other_tb", "alu", "u_alu"),
        )
        assertEquals(listOf("tb.dut.u_alu0", "tb.dut.u_alu1"), WaveformSignals.scopes("tb", "alu", instances))
        assertEquals(listOf("tb"), WaveformSignals.scopes("tb", "tb", instances))
        assertEquals(emptyList<String>(), WaveformSignals.scopes("tb", "unused", instances))
    }

    @Test
    fun recursiveInstancesEnd() {
        val instances = listOf(Instance("node", "node", "child"), Instance("tb", "node", "root"))
        assertEquals(listOf("tb.root"), WaveformSignals.scopes("tb", "node", instances))
    }

    private val vcd = """
        ${'$'}date today ${'$'}end
        ${'$'}timescale 1ns ${'$'}end
        ${'$'}scope module counter_tb ${'$'}end
        ${'$'}var reg 1 ! clk ${'$'}end
        ${'$'}var wire 4 " count_a [3:0] ${'$'}end
        ${'$'}scope module u_a ${'$'}end
        ${'$'}var wire 1 ! clk ${'$'}end
        ${'$'}var reg 4 " count [3:0] ${'$'}end
        ${'$'}var wire 1 # wrap ${'$'}end
        ${'$'}upscope ${'$'}end
        ${'$'}scope module u_b ${'$'}end
        ${'$'}var reg 4 ${'$'} count[3:0] ${'$'}end
        ${'$'}upscope ${'$'}end
        ${'$'}scope begin gen[0] ${'$'}end
        ${'$'}scope module u_c ${'$'}end
        ${'$'}var reg 4 % count ${'$'}end
        ${'$'}upscope ${'$'}end
        ${'$'}upscope ${'$'}end
        ${'$'}upscope ${'$'}end
        ${'$'}enddefinitions ${'$'}end
        #0
        ${'$'}dumpvars
        0!
        ${'$'}end
    """.trimIndent()

    @Test
    fun vcdHeaderGivesScopesAndVariables() {
        val scopes = WaveformSignals.vcdScopes(StringReader(vcd))!!
        assertEquals(
            listOf("counter_tb", "counter_tb.u_a", "counter_tb.u_b", "counter_tb.gen[0]", "counter_tb.gen[0].u_c"),
            scopes.keys.toList(),
        )
        assertEquals(setOf("clk", "count_a"), scopes["counter_tb"])
        assertEquals(setOf("clk", "count", "wrap"), scopes["counter_tb.u_a"])
        assertEquals(setOf("count"), scopes["counter_tb.u_b"])
    }

    @Test
    fun otherFormatsAreNotVcd() {
        assertNull(WaveformSignals.vcdScopes(StringReader("\u0000\u0001FST binary")))
        assertNull(WaveformSignals.vcdScopes(folder.root.toPath().resolve("missing.vcd")))
    }

    @Test
    fun signalIsFoundInEveryInstanceOfItsModule() {
        val instances = WaveformSignals.instances(testbench)
        val scopes = WaveformSignals.vcdScopes(StringReader(vcd))
        assertEquals(
            listOf("counter_tb.u_a.count", "counter_tb.u_b.count"),
            WaveformSignals.candidates("count", "counter", "counter_tb", instances, scopes),
        )
        // A port is in the dump in its own module and where it is connected; the module tells which.
        assertEquals(listOf("counter_tb.clk"), WaveformSignals.candidates("clk", "counter_tb", "counter_tb", instances, scopes))
        assertEquals(listOf("counter_tb.u_a.clk"), WaveformSignals.candidates("clk", "counter", "counter_tb", instances, scopes))
    }

    @Test
    fun dumpFindsWhatTheSourcesDoNotLeadTo() {
        val scopes = WaveformSignals.vcdScopes(StringReader(vcd))
        // An instance in a generate block: its scope is named like the instance.
        val generated = listOf(Instance("counter_tb", "deep", "u_c"))
        assertEquals(
            listOf("counter_tb.gen[0].u_c.count"),
            WaveformSignals.candidates("count", "deep", "counter_tb", generated, scopes),
        )
        // No instances known: every scope with the signal.
        assertEquals(
            listOf("counter_tb.u_a.wrap"), WaveformSignals.candidates("wrap", null, null, emptyList(), scopes),
        )
        assertEquals(emptyList<String>(), WaveformSignals.candidates("missing", "counter", "counter_tb", emptyList(), scopes))
    }

    @Test
    fun withoutVcdTheSourcesGiveTheNames() {
        assertEquals(
            listOf("counter_tb.u_a.wrap", "counter_tb.u_b.wrap"),
            WaveformSignals.candidates("wrap", "counter", "counter_tb", WaveformSignals.instances(testbench), null),
        )
        assertEquals(emptyList<String>(), WaveformSignals.candidates("wrap", "counter", null, emptyList(), null))
    }

    @Test
    fun instancesAreReadFromDirectoriesWithTheEditorTextFirst() {
        val directory = folder.newFolder("rtl").toPath()
        Files.writeString(directory.resolve("counter.v"), counter)
        val tb = Files.writeString(directory.resolve("counter_tb.v"), "module counter_tb; endmodule\n")
        Files.writeString(directory.resolve("notes.txt"), "counter u_x (.clk(clk));")
        assertEquals(emptyList<Instance>(), WaveformSignals.instancesIn(listOf(directory)))
        assertEquals(
            listOf("u_a", "u_b"),
            WaveformSignals.instancesIn(listOf(directory), mapOf(tb.toAbsolutePath().normalize() to testbench))
                .filter { it.module == "counter" }.map { it.name },
        )
    }
}
