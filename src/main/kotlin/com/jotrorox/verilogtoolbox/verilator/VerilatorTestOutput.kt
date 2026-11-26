package com.jotrorox.verilogtoolbox.verilator

import com.jotrorox.verilogtoolbox.icarus.SimulationOutput

/**
 * Decides from what a Verilated simulation prints and returns whether its testbench passed.
 *
 * Verilator's runtime reports `$error`, `$fatal` and failing assertions as
 * `[20] %Error: tb.sv:5: Assertion failed in TOP.tb: message`, and `$fatal` as `%Fatal`. After as many errors
 * as `+verilator+error+limit` allows, one by default, it stops with `%Error: tb.sv:5: Verilog $stop` and aborts,
 * which a non-zero exit code tells too. A C++ testbench fails by returning non-zero, and may print reports in
 * the same form to say why, as the one Generate Verilator C++ Testbench writes does.
 */
class VerilatorTestOutput : SimulationOutput {
    data class Report(val location: String, val message: String)

    private val line = StringBuilder()
    private var firstReport: Report? = null
    private var reports = 0

    /** A `$stop`, which a report before it explains. */
    private var stop: Report? = null

    override fun simulatorText(text: String) {
        line.append(text)
        while (true) {
            val end = line.indexOf("\n")
            if (end < 0) break
            simulatorLine(line.substring(0, end))
            line.delete(0, end + 1)
        }
        if (line.length > MAX_LINE_LENGTH) line.setLength(0)
    }

    private fun simulatorLine(text: String) {
        val report = report(text) ?: return
        if (report.message == STOP) {
            if (stop == null) stop = report
            return
        }
        if (firstReport == null) firstReport = report
        reports++
    }

    override fun simulationFailure(exitCode: Int): SimulationOutput.Failure? {
        simulatorLine(line.toString())
        line.setLength(0)
        val report = firstReport ?: stop
            ?: return if (exitCode == 0) null else SimulationOutput.Failure("Simulation exited with code $exitCode", "")
        val others = when (reports) {
            0, 1 -> ""
            2 -> "\n1 more error was reported."
            else -> "\n${reports - 1} more errors were reported."
        }
        return SimulationOutput.Failure(report.message, "at ${report.location}$others")
    }

    companion object {
        private const val MAX_LINE_LENGTH = 10_000
        private const val STOP = "Verilog \$stop"

        /** An optional `[time]`, then the severity, maybe with a warning code, and the place. */
        private val REPORT = Regex("""^(?:\[[^]]*]\s*)?%(Error|Fatal)(?:-[A-Z0-9_]+)?: (.+?:\d+): ?(.*)$""")

        /** What Verilator puts before the message of an assertion or `$error`. */
        private val ASSERTION = Regex("""^Assertion failed in [^:]+: ?""")

        fun report(line: String): Report? {
            val (severity, location, text) = REPORT.find(line.trimEnd())?.destructured ?: return null
            val assertion = ASSERTION.find(text)
            val message = if (assertion == null) text else text.substring(assertion.range.last + 1)
            return Report(
                location,
                message.ifEmpty { if (severity == "Fatal") "\$fatal was called" else "Assertion failed" },
            )
        }
    }
}
