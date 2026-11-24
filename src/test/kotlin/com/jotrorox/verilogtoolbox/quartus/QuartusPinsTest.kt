package com.jotrorox.verilogtoolbox.quartus

import com.jotrorox.verilogtoolbox.quartus.QuartusPins.Node
import com.jotrorox.verilogtoolbox.quartus.QuartusPins.Standard
import com.jotrorox.verilogtoolbox.quartus.QuartusPins.StandardSource
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Direction.INPUT
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Direction.INTERFACE
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Direction.OUTPUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuartusPinsTest {
    private fun module(source: String) = VerilogModuleHeaders.modules(source.trimIndent()).single()

    private fun names(nodes: List<Node>) = nodes.map { it.name }

    @Test
    fun scalarsAreOneNodeAndLiteralVectorsOneNodePerBitInTheOrderOfTheirRange() {
        val top = module("module top (input clk, output logic [3:0] led, input [0:1] sw); endmodule")
        val nodes = QuartusPins.nodes(top, emptyList())
        assertEquals(listOf("clk", "led[3]", "led[2]", "led[1]", "led[0]", "sw[0]", "sw[1]"), names(nodes))
        assertEquals(Node("led[2]", OUTPUT, "led"), nodes[2])
        assertEquals(Node("clk", INPUT, "clk"), nodes[0])
    }

    @Test
    fun vectorsWithComputableBoundsAreSplitWithTheParametersAndTheirOverrides() {
        val top = module(
            """
            module top #(
                parameter int CHANNELS = 3,
                parameter int RATE = 250_000,
                localparam int SAMPLES = (RATE / 1000) * 17 + 64,
                localparam int AW = ${'$'}clog2(SAMPLES)
            ) (input [CHANNELS-1:0] miso, output [AW-1:0] addr);
            endmodule
            """
        )
        // RATE gives 4314 samples, which take 13 address bits.
        assertEquals(
            listOf("miso[2]", "miso[1]", "miso[0]") + (12 downTo 0).map { "addr[$it]" },
            names(QuartusPins.nodes(top, emptyList())),
        )
        assertEquals(
            listOf("miso[1]", "miso[0]"),
            names(QuartusPins.nodes(top, emptyList(), mapOf("CHANNELS" to "2"))).filter { it.startsWith("miso") },
        )
    }

    @Test
    fun vectorsWithUnknownBoundsAreOneNodeOrTheBitsTheSettingsAssign() {
        val top = module("module top (input [WIDTH-1:0] a, input [`W:0] b, input [1:0][3:0] grid, my_if.master bus); endmodule")
        val nodes = QuartusPins.nodes(top, listOf(QuartusPin("b[0]", "PIN_A1", null), QuartusPin("b[1]", "PIN_A2", null)))
        assertEquals(
            listOf(
                Node("a", INPUT, "a", isWhole = true),
                Node("b[1]", INPUT, "b"),
                Node("b[0]", INPUT, "b"),
                Node("grid", INPUT, "grid", isWhole = true),
                Node("bus", INTERFACE, "bus"),
            ),
            nodes,
        )
    }

    @Test
    fun nodesTheSettingsAssignButNoPortExplainsAreKept() {
        val top = module("module top (input clk, output [1:0] led); endmodule")
        val pins = listOf(
            QuartusPin("CLK", "PIN_P11", "3.3-V LVTTL"),
            // The I/O standard of a bus belongs to its bits; a location on the bus itself would be lost.
            QuartusPin("led", null, "3.3-V LVTTL"),
            QuartusPin("old_led", "PIN_A8", null),
            QuartusPin("led[2]", "PIN_A9", null),
        )
        val nodes = QuartusPins.nodes(top, pins)
        assertEquals(listOf("CLK", "led[1]", "led[0]", "old_led", "led[2]"), names(nodes))
        assertTrue(nodes.takeLast(2).all { it.isOrphan && it.direction == null })
        val located = listOf(QuartusPin("led", "PIN_B1", null), QuartusPin("x", null, "1.8 V"))
        assertEquals(listOf("led", "x"), names(QuartusPins.nodes(top, located)).takeLast(2))
        // Without a top-level module every node of the settings is still shown.
        val loose = listOf(QuartusPin("a", "PIN_A1", null), QuartusPin("b", null, "2.5 V"))
        assertEquals(listOf("a", "b"), names(QuartusPins.nodes(null, loose)))
    }

    @Test
    fun theTopLevelIsFoundByNameAndThenRegardlessOfCase() {
        val modules = VerilogModuleHeaders.modules("module sub (input a); endmodule\nmodule Led_Chaser (input b); endmodule")
        assertEquals("Led_Chaser", QuartusPins.topLevel(modules, "led_chaser")?.name)
        assertEquals("sub", QuartusPins.topLevel(modules, "sub")?.name)
        assertNull(QuartusPins.topLevel(modules, "missing"))
    }

    @Test
    fun aBitInheritsTheIoStandardOfItsBus() {
        val standards = mapOf("clk_50" to "3.3-V LVTTL", "adc_sclk" to "2.5 V", "grid[1]" to "1.8 V")
        assertEquals(Standard("3.3-V LVTTL", StandardSource.OWN), QuartusPins.ioStandard("clk_50", standards))
        assertEquals(Standard("2.5 V", StandardSource.BUS, "adc_sclk"), QuartusPins.ioStandard("adc_sclk[0]", standards))
        assertEquals(Standard("2.5 V", StandardSource.BUS, "adc_sclk"), QuartusPins.ioStandard("ADC_SCLK[3]", standards))
        assertEquals(Standard("1.8 V", StandardSource.BUS, "grid[1]"), QuartusPins.ioStandard("grid[1][0]", standards))
        assertEquals(Standard("1.2 V", StandardSource.DEFAULT), QuartusPins.ioStandard("other", standards, "1.2 V"))
        assertNull(QuartusPins.ioStandard("other", standards))
        assertTrue(QuartusPins.ioStandard("adc_sclk[0]", standards)!!.isInherited)
    }

    @Test
    fun thePinFileGivesTheAssignablePinsAndWhereTheFitterPutEachNode() {
        val report = QuartusPins.pinReport(
            """
             -- VCCIO         : Dedicated power pin, which MUST be connected to VCC
             --                  Bank 3:       3.3V
             --                  Bank 1B:       2.5V
            CHIP  "led-chaser"  ASSIGNED TO AN: 10M50DAF484C7G

            Pin Name/Usage               : Location  : Dir.   : I/O Standard      : Voltage : I/O Bank  : User Assignment
            -------------------------------------------------------------------------------------------------------------
            GND                          : A1        : gnd    :                   :         :           :
            RESERVED_INPUT_WITH_WEAK_PULLUP : A2        :        :                   :         : 8         :
            led[0]                       : A8        : output : 3.3-V LVTTL       :         : 7         : Y
            NC                           : E5        :        :                   :         :           :
            ~ALTERA_TCK~ / RESERVED_INPUT : G2        : input  : 2.5 V Schmitt Trigger :         : 1B        : N
            ANAIN1                       : G5        :        :                   :         :           :
            VCCIO8                       : F9        : power  :                   : 2.5V    : 8         :
            clk_50                       : P11       : input  : 3.3-V LVTTL       :         : 3         : N
            """.trimIndent()
        )
        assertEquals("10M50DAF484C7G", report.device)
        assertEquals(mapOf("3" to "3.3V", "1B" to "2.5V"), report.bankVoltages)
        assertEquals(listOf("PIN_A2", "PIN_A8", "PIN_P11"), report.pins.map { it.location })
        assertEquals(listOf(null, "led[0]", "clk_50"), report.pins.map { it.node })
        assertEquals("PIN_P11", report.placement("CLK_50")?.location)
        assertEquals("3", report.pin("p11")?.bank)
        assertNull(report.placement("missing"))
    }

    @Test
    fun theSameLocationOnTwoNodesIsAConflictHoweverItIsWritten() {
        assertEquals(
            mapOf("a" to listOf("b", "e"), "b" to listOf("a", "e"), "e" to listOf("a", "b")),
            QuartusPins.conflicts(mapOf("a" to "PIN_P11", "b" to "p11", "c" to "PIN_A1", "d" to null, "e" to "P11")),
        )
        assertTrue(QuartusPins.sameLocation("pin_p11", "P11"))
        assertFalse(QuartusPins.sameLocation(null, null))
        assertTrue(QuartusPins.isPinLocation("PIN_AA2"))
        assertTrue(QuartusPins.isPinLocation("PIN_12"))
        assertFalse(QuartusPins.isPinLocation("PIN_FOO BAR"))
    }

    @Test
    fun rowsCombineAssignmentsStandardsFitterLocationsAndConflicts() {
        val top = module("module top (input clk, output [1:0] led, input rst); endmodule")
        val pins = listOf(
            QuartusPin("clk", "PIN_P11", "3.3-V LVTTL"),
            QuartusPin("led", null, "2.5 V"),
            QuartusPin("led[1]", "PIN_P11", null),
        )
        val report = QuartusPins.pinReport(
            "Pin Name/Usage : Location : Dir. : I/O Standard : Voltage : I/O Bank : User Assignment\n" +
                "led[0] : A8 : output : 2.5 V : : 7 : N\n" +
                "RESERVED_INPUT : A9 : : : : 7 :\n"
        )
        val rows = QuartusPins.rows(QuartusPins.nodes(top, pins), pins, null, report)
        assertEquals(listOf("clk", "led[1]", "led[0]", "rst"), rows.map { it.name })
        assertEquals(listOf(listOf("led[1]"), listOf("clk"), emptyList(), emptyList()), rows.map { it.conflicts })
        assertEquals(listOf(false, false, true, true), rows.map { it.isUnassigned })
        assertEquals("PIN_A8", rows[2].fitterLocation)
        assertEquals(Standard("2.5 V", StandardSource.BUS, "led"), rows[2].standard)
        // The pin of led[0] was the Fitter's choice, so it is free for any node; that of clk is not.
        assertEquals(listOf("PIN_A8", "PIN_A9"), QuartusPins.freePins(report, rows).map { it.location })
    }

    @Test
    fun constantExpressionsAreEvaluated() {
        assertEquals(50_000_000L, QuartusPins.evaluate("50_000_000"))
        assertEquals(4314L, QuartusPins.evaluate("(250_000 / 1000) * 17 + 64"))
        assertEquals(4L, QuartusPins.evaluate("\$clog2(9)"))
        assertEquals(12L, QuartusPins.evaluate("8'd12"))
        assertEquals(255L, QuartusPins.evaluate("'hFF"))
        assertEquals(16L, QuartusPins.evaluate("2**4"))
        assertEquals(7L, QuartusPins.evaluate("(1 << 3) - 1"))
        assertEquals(5L, QuartusPins.evaluate("W - 3", mapOf("W" to 8L)::get))
        assertNull(QuartusPins.evaluate("W - 3"))
        assertNull(QuartusPins.evaluate("pkg::W"))
        assertNull(QuartusPins.evaluate("1 / 0"))
    }
}
