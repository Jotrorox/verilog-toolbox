package com.jotrorox.verilogtoolbox.verilator

import com.jotrorox.verilogtoolbox.icarus.SimulationOutput.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerilatorTestOutputTest {
    private fun simulation(output: String, exitCode: Int = 0): Failure? =
        VerilatorTestOutput().apply { simulatorText(output) }.simulationFailure(exitCode)

    @Test
    fun cleanRunPasses() {
        assertNull(simulation("done q=10\n- counter_tb.sv:12: Verilog \$finish\n"))
        assertNull(simulation("[30] %Warning: tb.sv:4: careful\n"))
    }

    @Test
    fun errorIsTheFailureAndTheStopAfterItIsNot() {
        val output = "[112] %Error: counter_tb.sv:10: Assertion failed in TOP.counter_tb: q is 9\n" +
            "%Error: counter_tb.sv:10: Verilog \$stop\nAborting...\n"
        assertEquals(Failure("q is 9", "at counter_tb.sv:10"), simulation(output, 134))
    }

    @Test
    fun fatalWithoutMessage() =
        assertEquals(Failure("\$fatal was called", "at tb.sv:3"), simulation("[5] %Fatal: tb.sv:3: Assertion failed in TOP.tb: \n", 134))

    @Test
    fun failingAssertion() =
        assertEquals(
            Failure("'assert' failed.", "at tb.sv:7"),
            simulation("[5] %Error: tb.sv:7: Assertion failed in TOP.tb.unnamedblk1: 'assert' failed.\n", 134),
        )

    @Test
    fun stopAloneFails() = assertEquals(Failure("Verilog \$stop", "at tb.sv:9"), simulation("%Error: tb.sv:9: Verilog \$stop\n", 134))

    @Test
    fun checkOfACppTestbenchOnWindows() {
        val output = "%Error: C:/work/counter_tb.cpp:40: check failed: top->q == 1\n" +
            "%Error: C:/work/counter_tb.cpp:41: check failed: top->q == 2\n"
        assertEquals(
            Failure("check failed: top->q == 1", "at C:/work/counter_tb.cpp:40\n1 more error was reported."),
            simulation(output, 1),
        )
    }

    @Test
    fun exitCodeWithoutReport() = assertEquals(Failure("Simulation exited with code 3", ""), simulation("segfault?\n", 3))

    @Test
    fun reportsAreFoundAcrossChunks() {
        val output = VerilatorTestOutput()
        listOf("[1] %Err", "or: tb.sv:5: Assertion failed in TOP.tb: ba", "d\r\n").forEach(output::simulatorText)
        assertEquals(Failure("bad", "at tb.sv:5"), output.simulationFailure(1))
    }

    @Test
    fun lastLineNeedsNoLineBreak() = assertEquals(Failure("late", "at tb.sv:7"), simulation("%Error: tb.sv:7: late", 1))
}
