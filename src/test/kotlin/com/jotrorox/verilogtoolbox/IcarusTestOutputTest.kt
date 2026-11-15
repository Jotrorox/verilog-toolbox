package com.jotrorox.verilogtoolbox

import com.jotrorox.verilogtoolbox.IcarusTestOutput.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The outputs are what Icarus Verilog 13.0 prints for the system task named by each test. */
class IcarusTestOutputTest {
    private fun simulation(output: String, exitCode: Int = 0): Failure? =
        IcarusTestOutput().apply { simulatorText(output) }.simulationFailure(exitCode)

    @Test
    fun cleanRunPasses() {
        assertNull(simulation("q=5\ntb.sv:5: \$finish called at 5 (1s)\n"))
        assertNull(simulation("tb.sv:5: \$finish(2) called at 5 (1s)\n"))
        // What `$stop` does under `-n`.
        assertNull(simulation("tb.sv:5: \$stop called at 5 (1s)\n"))
    }

    @Test
    fun errorFailsAlthoughVvpReturnsZero() = assertEquals(
        Failure("bad 3", "at tb.sv:5"),
        simulation("ERROR: tb.sv:5: bad 3\n       Time: 5  Scope: tb\nafter\n"),
    )

    @Test
    fun fatalFails() = assertEquals(
        Failure("boom 7", "at /my designs/tb.sv:5"),
        simulation("FATAL: /my designs/tb.sv:5: boom 7\n       Time: 5  Scope: tb\n", exitCode = 1),
    )

    @Test
    fun reportsWithoutMessageAreNamed() {
        // `assert (a == 1);` and `$error;` print the same.
        assertEquals(Failure("Assertion failed", "at tb.sv:5"), simulation("ERROR: tb.sv:5: \n       Time: 5  Scope: tb\n"))
        assertEquals(Failure("\$fatal was called", "at tb.sv:5"), simulation("FATAL: tb.sv:5: \n       Time: 5  Scope: tb\n", 1))
    }

    @Test
    fun firstErrorIsTheFailure() {
        assertEquals(
            Failure("clocked", "at tb.sv:4\n1 more error was reported."),
            simulation("ERROR: tb.sv:4: clocked\n       Time: 1  Scope: tb\nFATAL: tb.sv:9: gave up\n       Time: 3  Scope: tb\n", 1),
        )
        assertEquals(
            Failure("a", "at tb.sv:1\n2 more errors were reported."),
            simulation("ERROR: tb.sv:1: a\nERROR: tb.sv:2: b\nERROR: tb.sv:3: c\n"),
        )
    }

    @Test
    fun warningsAndInfoDoNotFail() = assertNull(
        simulation("WARNING: tb.sv:5: careful\n         Time: 5  Scope: tb\nINFO: tb.sv:6: fyi\n      Time: 5  Scope: tb\n")
    )

    @Test
    fun textThatOnlyLooksLikeAReportDoesNotFail() =
        assertNull(simulation("ERROR: not real\nFATAL: fake\nsaw ERROR: tb.sv:5: in the middle\n"))

    @Test
    fun failedSystemTaskFails() = assertEquals(
        Failure("\$readmemh: Unable to open nope.hex for reading.", "at tb.sv:3"),
        simulation("ERROR: tb.sv:3: \$readmemh: Unable to open nope.hex for reading.\nx\n"),
    )

    @Test
    fun exitCodeAloneFails() =
        assertEquals(Failure("vvp exited with code 255", ""), simulation("tb.vvp: Unable to open input file.\n", 255))

    @Test
    fun reportsAreFoundAcrossChunksAndLineEndings() {
        val output = IcarusTestOutput()
        listOf("q=1\r\nERR", "OR: tb.sv:5: ba", "d\r\n       Time", ": 5  Scope: tb\r\n").forEach(output::simulatorText)
        assertEquals(Failure("bad", "at tb.sv:5"), output.simulationFailure(0))
    }

    @Test
    fun lastLineNeedsNoLineBreak() = assertEquals(Failure("late", "at tb.sv:7"), simulation("FATAL: tb.sv:7: late", 1))

    @Test
    fun compilerOutputIsTheFailure() {
        val output = IcarusTestOutput()
        output.compilerText("tb.sv:5: syntax error\n")
        output.compilerText("tb.sv:5: error: Malformed statement\n")
        assertEquals(
            Failure("Compilation failed", "tb.sv:5: syntax error\ntb.sv:5: error: Malformed statement"),
            output.compilationFailure(2),
        )
    }

    @Test
    fun compilerWarningsDoNotFail() {
        val output = IcarusTestOutput()
        output.compilerText("tb.sv:3: warning: implicit definition of wire 'x'.\n")
        assertNull(output.compilationFailure(0))
        assertEquals("tb.sv:3: warning: implicit definition of wire 'x'.\n", output.compilerMessages)
    }

    @Test
    fun silentCompilerFailureStillFails() =
        assertEquals(Failure("Compilation failed", "iverilog exited with code 127"), IcarusTestOutput().compilationFailure(127))
}
