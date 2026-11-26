package com.jotrorox.verilogtoolbox.sby

import com.jotrorox.verilogtoolbox.sby.SbyTaskResult.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logs are what SymbiYosys of the OSS CAD Suite 2026-11-26 printed for `counter.sby` and its variants. */
class SbyOutputTest {
    private fun log(name: String): List<String> =
        javaClass.getResourceAsStream("/sby/$name.log")!!.bufferedReader().readLines()

    /** The results of the tasks in a log, by workdir. */
    private fun results(name: String): Map<String, SbyTaskResult> {
        val results = linkedMapOf<String, SbyTaskResult>()
        for (text in log(name)) {
            val line = SbyOutput.parse(text) ?: continue
            val workdir = line.workdir ?: continue
            results.getOrPut(workdir) { SbyTaskResult(workdir) }.accept(line.message)
        }
        return results
    }

    @Test
    fun lines() {
        assertEquals(SbyOutput.Line("counter_bmc", "DONE (FAIL, rc=2)"), SbyOutput.parse("SBY 19:11:04 [counter_bmc] DONE (FAIL, rc=2)"))
        assertEquals(SbyOutput.Line("counter_bmc", "engine_0: smtbmc"), SbyOutput.parse("SBY  9:05:03 [counter_bmc] engine_0: smtbmc\r\n"))
        assertEquals(
            SbyOutput.Line(null, "The following tasks failed: ['bmc']"),
            SbyOutput.parse("SBY 19:11:04 The following tasks failed: ['bmc']"),
        )
        // Coloured, as when SymbiYosys thinks it writes to a terminal.
        assertEquals(
            SbyOutput.Line("counter_bmc", "DONE (PASS, rc=0)"),
            SbyOutput.parse("\u001B[34mSBY\u001B[0m \u001B[32m19:11:04\u001B[0m [\u001B[34mcounter_bmc\u001B[0m] DONE (PASS, rc=0)"),
        )
        assertNull(SbyOutput.parse("Traceback (most recent call last):"))
    }

    @Test
    fun failedAssertion() {
        val result = results("fail").values.single()
        assertEquals("FAIL", result.status)
        assertEquals(2, result.returnCode)
        assertEquals(listOf(SbyTaskResult.Trace("counterexample trace", null, "counter_bmc\\engine_0\\trace.vcd")), result.traces)
        assertEquals(
            listOf(SbyTaskResult.Property(Kind.FAILED_ASSERTION, "counter._witness_.check_counter_sv_14_assert_8", "counter.sv:14.46-14.68", "7")),
            result.properties,
        )
        assertEquals("counter_bmc\\engine_0\\trace.vcd", result.mainTrace)
        assertEquals(listOf("engine_0/trace.vcd"), result.writtenTraces)
        val failure = result.failure!!
        assertEquals("Assertion failed at counter.sv:14.46-14.68 in step 7", failure.message)
        assertEquals(
            """
            Status: FAIL (rc=2)
            counterexample trace: counter_bmc\engine_0\trace.vcd
            failed assertion counter._witness_.check_counter_sv_14_assert_8 at counter.sv:14.46-14.68 step 7
            """.trimIndent(),
            failure.details,
        )
    }

    @Test
    fun proofThatHolds() {
        val result = results("pass").values.single()
        assertEquals("PASS", result.status)
        assertNull(result.failure)
        assertNull(result.mainTrace)
    }

    @Test
    fun syntaxError() {
        val result = results("error").values.single()
        assertEquals(16, result.returnCode)
        val failure = result.failure!!
        assertEquals("broken.sv:11: ERROR: syntax error, unexpected TOK_LOGIC", failure.message)
        assertTrue(failure.details, "base: broken.sv:11: ERROR: syntax error, unexpected TOK_LOGIC" in failure.details)
    }

    @Test
    fun unreachedCover() {
        val result = results("unreached").values.single()
        assertEquals(
            listOf(SbyTaskResult.Property(Kind.UNREACHED_COVER, "counter._witness_.check_unreached_sv_15_cover_13", "unreached.sv:15.54-15.76", null)),
            result.properties,
        )
        assertEquals("Cover statement not reached at unreached.sv:15.54-15.76", result.failure!!.message)
    }

    @Test
    fun parallelTasks() {
        val results = results("tasks")
        assertEquals(setOf("counter_bmc", "counter_prove", "counter_cover"), results.keys)
        val cover = results.getValue("counter_cover")
        assertNull(cover.failure)
        assertEquals("counter_cover\\engine_0\\trace0.vcd", cover.mainTrace)
        assertEquals(Kind.REACHED_COVER, cover.properties.single().kind)
        val prove = results.getValue("counter_prove")
        assertEquals(listOf("basecase", "induction"), prove.traces.map { it.case })
        assertEquals("counter_prove\\engine_0\\trace.vcd", prove.mainTrace)
        assertEquals("Assertion failed at counter.sv:14.46-14.68 in step 7", prove.failure!!.message)
    }

    @Test
    fun runWithoutTasks() {
        val result = results("notasks").getValue("notasks")
        assertEquals("PASS", result.status)
        assertNull(result.failure)
    }

    @Test
    fun namedPropertiesShowTheirName() {
        val result = SbyTaskResult("t")
        result.accept("summary:   failed assertion fifo.a_not_full at fifo.sv:40.5-40.30 steps 3, 4")
        result.accept("DONE (FAIL, rc=2)")
        assertEquals("Assertion failed (fifo.a_not_full) at fifo.sv:40.5-40.30 in steps 3, 4", result.failure!!.message)
    }

    @Test
    fun otherStatuses() {
        fun failure(vararg lines: String) = SbyTaskResult("t").apply { lines.forEach(::accept) }.failure!!.message
        assertEquals("No engine came to a result (UNKNOWN)", failure("DONE (UNKNOWN, rc=4)"))
        assertEquals("Reached TIMEOUT (60 seconds). Terminating all subprocesses.",
            failure("Reached TIMEOUT (60 seconds). Terminating all subprocesses.", "DONE (TIMEOUT, rc=8)"))
        assertEquals("The task passed, which is not the expected result", failure("DONE (PASS, rc=1)"))
        // A task expected to fail that fails has passed.
        assertNull(SbyTaskResult("t").apply { accept("DONE (FAIL, rc=0)") }.failure)
    }

    @Test
    fun whyNoTaskStarted() {
        assertEquals("Task name 'nope' didn't match any lines in [tasks].",
            SbyProcessHandler.startFailure("ERROR: Task name 'nope' didn't match any lines in [tasks].\n", 1))
        assertEquals("FileNotFoundError: [Errno 2] No such file or directory: 'rtl\\\\counter.sv'",
            SbyProcessHandler.startFailure("Traceback (most recent call last):\n  File \"sby.py\"\nFileNotFoundError: [Errno 2] No such file or directory: 'rtl\\\\counter.sv'\n", 1))
        assertEquals("SymbiYosys exited with code 3", SbyProcessHandler.startFailure("", 3))
    }
}
