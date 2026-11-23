package com.jotrorox.verilogtoolbox.icarus

/**
 * Decides from what `iverilog` and `vvp` print and return whether a testbench passed.
 *
 * Icarus 13 reports `$error`, `$fatal` and failing assertions on standard output as
 * `ERROR: tb.sv:5: message` and `FATAL: tb.sv:5: message`, followed by a `Time: 5  Scope: tb` line.
 * Only `$fatal` makes `vvp` return non-zero (always 1); after `$error`, `$finish(n)` and, under `-n`,
 * `$stop` it returns 0, so the reports have to be read to see a failure. `iverilog` returns non-zero
 * (2) when the design does not compile and prints the reason on standard error.
 */
class IcarusTestOutput {
    /** Why a testbench failed: [message] is the headline, [details] the text below it. */
    data class Failure(val message: String, val details: String)

    /** A `$error` or `$fatal` report; [location] is the `file:line` it came from. */
    data class Report(val fatal: Boolean, val location: String, val message: String)

    private val compilerOutput = StringBuilder()
    private val line = StringBuilder()
    private var firstReport: Report? = null
    private var reports = 0

    fun compilerText(text: String) {
        compilerOutput.append(text)
    }

    /** Takes the simulation's standard output as it arrives, in chunks that need not end at a line break. */
    fun simulatorText(text: String) {
        line.append(text)
        while (true) {
            val end = line.indexOf("\n")
            if (end < 0) break
            simulatorLine(line.substring(0, end))
            line.delete(0, end + 1)
        }
        // A report is a short line; a longer one without an end is something else.
        if (line.length > MAX_LINE_LENGTH) line.setLength(0)
    }

    private fun simulatorLine(text: String) {
        val report = report(text) ?: return
        if (firstReport == null) firstReport = report
        reports++
    }

    /** What `iverilog` printed, for the console when the design compiled: its warnings. */
    val compilerMessages: String
        get() = compilerOutput.toString()

    fun compilationFailure(exitCode: Int): Failure? =
        if (exitCode == 0) null
        else Failure("Compilation failed", compilerOutput.toString().trimEnd().ifEmpty { "iverilog exited with code $exitCode" })

    /** The first report is the failure: later ones tend to follow from it. */
    fun simulationFailure(exitCode: Int): Failure? {
        simulatorLine(line.toString())
        line.setLength(0)
        val report = firstReport
            ?: return if (exitCode == 0) null else Failure("vvp exited with code $exitCode", "")
        val message = report.message.ifEmpty { if (report.fatal) "\$fatal was called" else "Assertion failed" }
        val others = when (reports) {
            1 -> ""
            2 -> "\n1 more error was reported."
            else -> "\n${reports - 1} more errors were reported."
        }
        return Failure(message, "at ${report.location}$others")
    }

    companion object {
        private const val MAX_LINE_LENGTH = 10_000

        /**
         * The location tells a report from a testbench that prints "ERROR: ..." itself. `WARNING:` and
         * `INFO:` reports look the same and are not failures.
         */
        private val REPORT = Regex("""^(ERROR|FATAL): (.+?:\d+): ?(.*)$""")

        fun report(line: String): Report? =
            REPORT.find(line.trimEnd())?.destructured?.let { (severity, location, message) ->
                Report(severity == "FATAL", location, message)
            }
    }
}
