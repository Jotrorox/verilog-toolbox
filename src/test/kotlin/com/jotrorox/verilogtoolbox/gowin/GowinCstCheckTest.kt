package com.jotrorox.verilogtoolbox.gowin

import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import org.junit.Assert.assertEquals
import org.junit.Test

class GowinCstCheckTest {
    private val top = VerilogModuleHeaders.modules(
        """
        module top #(parameter W = 4) (
          input clk,
          output [7:0] led,
          output [W-1:0] seg,
          output int count
        );
        endmodule
        """.trimIndent(),
    ).single()

    /** Pins for every port, so that a test only sees the problems it adds. */
    private val allPlaced = "IO_LOC \"clk\" E2;\nIO_LOC \"led\" G5;\nIO_LOC \"seg[0]\" A1;\nIO_LOC \"count\" B1;\n"

    private fun problems(text: String) = GowinCstCheck.problems(text, top, "src/top.v")

    /** The messages of the problems at a name, each with the text it marks. */
    private fun named(text: String) = problems(text).filter { it.range != null }.map { text.substring(it.range!!) to it.message }

    private fun fileLevel(text: String) = problems(text).filter { it.range == null }.map { it.message }

    @Test
    fun fileForAnotherDesignGetsOneWarning() = assertEquals(
        listOf("None of the ports in this file is a port of top module 'top' (src/top.v). Is the file for another design?"),
        problems("IO_LOC \"sys_clk\" E2;\nIO_LOC \"leds[0]\" G5;\n").map { it.message },
    )

    @Test
    fun allPlacedHasNoProblems() = assertEquals(emptyList<GowinCstCheck.Problem>(), problems(allPlaced))

    @Test
    fun unknownPort() = assertEquals(
        listOf("\"ledd[0]\"" to "'ledd' is not a port of top module 'top' (src/top.v)."),
        named(allPlaced + "IO_LOC \"ledd[0]\" G6;\n"),
    )

    @Test
    fun portAttributesAreCheckedToo() = assertEquals(
        listOf("\"foo\"" to "'foo' is not a port of top module 'top' (src/top.v)."),
        named(allPlaced + "IO_PORT \"foo\" IO_TYPE=LVCMOS33;\nIO_PORT \"clk\" IO_TYPE=LVCMOS33;\n"),
    )

    @Test
    fun bitOutOfRange() = assertEquals(
        listOf("\"led[8]\"" to "'led[8]' is out of range: port 'led' of top module 'top' (src/top.v) is [7:0]."),
        named(allPlaced + "IO_LOC \"led[8]\" G6;\n"),
    )

    @Test
    fun bitOfScalarPort() = assertEquals(
        listOf("\"clk[0]\"" to "'clk[0]' has a bit index, but port 'clk' of top module 'top' (src/top.v) is a single bit."),
        named(allPlaced + "IO_PORT \"clk[0]\" PULL_MODE=UP;\n"),
    )

    @Test
    fun parameterisedRangeOnlyChecksTheName() {
        val text = "IO_LOC \"clk\" E2;\nIO_LOC \"led\" G5;\nIO_LOC \"count\" B1;\nIO_LOC \"seg[99]\" A1;\n"
        assertEquals(emptyList<GowinCstCheck.Problem>(), problems(text))
    }

    @Test
    fun missingScalarPort() = assertEquals(
        listOf("No pin for clk: these ports of top module 'top' (src/top.v) have no IO_LOC."),
        fileLevel(allPlaced.replace("IO_LOC \"clk\" E2;\n", "IO_PORT \"clk\" IO_TYPE=LVCMOS33;\n")),
    )

    @Test
    fun missingBitsOfVector() {
        val bits = (0..7).filter { it != 5 && it != 6 }.joinToString("") { "IO_LOC \"led[$it]\" G$it;\n" }
        assertEquals(
            listOf("No pin for clk, led[5], led[6]: these ports of top module 'top' (src/top.v) have no IO_LOC."),
            fileLevel(bits + "IO_LOC \"seg[0]\" A1;\nIO_LOC \"count\" B1;\n"),
        )
    }

    @Test
    fun vectorWithoutAnyPinIsNamedAsAWhole() = assertEquals(
        listOf("No pin for led, seg: these ports of top module 'top' (src/top.v) have no IO_LOC."),
        fileLevel("IO_LOC \"clk\" E2;\nIO_LOC \"count\" B1;\n"),
    )

    @Test
    fun vectorPlacedBitByBit() {
        val bits = (0..7).joinToString("") { "IO_LOC \"led[ $it ]\" G$it;\n" }
        assertEquals(emptyList<GowinCstCheck.Problem>(), problems(bits + allPlaced.replace("IO_LOC \"led\" G5;\n", "")))
    }

    @Test
    fun wholeVectorPlacedByName() = assertEquals(emptyList<String>(), fileLevel(allPlaced))

    @Test
    fun interfacePortOnlyNeedsItsName() {
        val module = VerilogModuleHeaders.modules("module top(bus_if.master bus, input clk);\nendmodule\n").single()
        assertEquals(emptyList<GowinCstCheck.Problem>(), GowinCstCheck.problems("IO_LOC \"bus[3]\" A1;\nIO_LOC \"clk\" E2;\n", module, "top.sv"))
        assertEquals(
            listOf("No pin for bus: these ports of top module 'top' (top.sv) have no IO_LOC."),
            GowinCstCheck.problems("IO_LOC \"clk\" E2;\n", module, "top.sv").map { it.message },
        )
    }
}
