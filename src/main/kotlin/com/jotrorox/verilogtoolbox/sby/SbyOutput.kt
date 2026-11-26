package com.jotrorox.verilogtoolbox.sby

/**
 * Reads what SymbiYosys prints. Every line is `SBY h:mm:ss [workdir] message`, the workdir naming the task
 * (`counter_bmc` for task `bmc` of `counter.sby`); tasks run in parallel, so their lines are interleaved. At
 * its end a task prints a summary and `DONE (STATUS, rc=N)`, where `rc` is 0 when the status is the expected
 * one, `PASS` unless the file says otherwise.
 */
object SbyOutput {
    /** A line of SymbiYosys; [workdir] is null for the lines about the run as a whole. */
    data class Line(val workdir: String?, val message: String)

    private val LINE = Regex("""^SBY\s+\d{1,2}:\d{2}:\d{2}\s+(?:\[([^\]]+)]\s)?(.*)$""")
    private val ANSI = Regex("""\u001B\[[0-9;]*m""")

    fun parse(line: String): Line? =
        LINE.find(line.replace(ANSI, "").trimEnd('\r', '\n'))?.destructured?.let { (workdir, message) ->
            Line(workdir.ifEmpty { null }, message)
        }
}

/** What a task of a SymbiYosys run printed, and what it came to, gathered from its lines as they arrive. */
class SbyTaskResult(val workdir: String) {
    /** A trace an engine wrote: a counterexample, or one that reaches a cover statement. */
    data class Trace(val kind: String, val case: String?, val path: String)

    /** An assertion that failed, a cover statement that was reached or not, or an assumption that was violated. */
    data class Property(val kind: Kind, val name: String, val location: String, val steps: String?)

    enum class Kind(val text: String) {
        FAILED_ASSERTION("failed assertion"),
        REACHED_COVER("reached cover statement"),
        VIOLATED_ASSUMPTION("violated assumption"),
        UNREACHED_COVER("unreached cover statement"),
    }

    /** Why a task did not pass: [message] is the headline, [details] the text below it. */
    data class Failure(val message: String, val details: String)

    var status: String? = null
        private set
    var returnCode: Int? = null
        private set

    val isDone: Boolean
        get() = returnCode != null

    val traces = mutableListOf<Trace>()
    val properties = mutableListOf<Property>()

    /** The traces the engines said they wrote, relative to the workdir; the summary names them in full. */
    val writtenTraces = mutableListOf<String>()

    private val errors = mutableListOf<String>()
    private val summary = mutableListOf<String>()
    private var unreachedCovers = false

    fun accept(message: String) {
        if (message.startsWith(SUMMARY)) return summary(message.removePrefix(SUMMARY))
        unreachedCovers = false
        DONE.find(message)?.destructured?.let { (status, code) ->
            this.status = status
            returnCode = code.toInt()
            return
        }
        WRITING_TRACE.find(message)?.let { writtenTraces += it.groupValues[1] }
        if (isError(message)) errors += message
    }

    private fun summary(line: String) {
        if (!line.startsWith(" ")) unreachedCovers = false
        when {
            line.startsWith(ELAPSED) || ENGINE_STATUS.containsMatchIn(line) -> return
            line == UNREACHED_COVERS -> {
                unreachedCovers = true
                summary += line
                return
            }
        }
        summary += line.trim()
        TRACE.find(line)?.destructured?.let { (kind, case, path) ->
            if (!path.startsWith("<")) traces += Trace(kind, case.ifEmpty { null }, path)
            return
        }
        PROPERTY.find(line)?.destructured?.let { (kind, name, location, steps) ->
            properties += Property(Kind.entries.first { it.text == kind }, name, location, steps.ifEmpty { null })
            return
        }
        if (unreachedCovers) {
            UNREACHED.find(line)?.destructured?.let { (name, location) ->
                properties += Property(Kind.UNREACHED_COVER, name, location, null)
            }
        }
    }

    /** The trace to show first: the first counterexample, else the first trace of any kind. */
    val mainTrace: String?
        get() = (traces.firstOrNull { it.kind == COUNTEREXAMPLE } ?: traces.firstOrNull())?.path

    /** Null while the task runs and when it ended as expected. */
    val failure: Failure?
        get() {
            val code = returnCode ?: return null
            if (code == 0) return null
            val details = (listOf("Status: $status (rc=$code)") + summary + errors.take(MAX_ERRORS))
                .distinct().joinToString("\n")
            return Failure(headline(), details)
        }

    private fun headline(): String {
        val failed = properties.firstOrNull { it.kind == Kind.FAILED_ASSERTION }
            ?: properties.firstOrNull { it.kind == Kind.VIOLATED_ASSUMPTION }
            ?: properties.firstOrNull { it.kind == Kind.UNREACHED_COVER }
        val firstError = errors.firstOrNull()?.let(::errorText)
        return when {
            status == "FAIL" && failed != null -> {
                val what = when (failed.kind) {
                    Kind.FAILED_ASSERTION -> "Assertion failed"
                    Kind.VIOLATED_ASSUMPTION -> "Assumption violated"
                    else -> "Cover statement not reached"
                }
                val name = displayName(failed.name)?.let { " ($it)" }.orEmpty()
                val steps = failed.steps?.let { " in step${if (',' in it) "s" else ""} $it" }.orEmpty()
                "$what$name at ${failed.location}$steps"
            }
            status == "FAIL" -> "The task failed"
            status == "PASS" -> "The task passed, which is not the expected result"
            status == "TIMEOUT" -> firstError ?: "The task timed out"
            status == "UNKNOWN" -> "No engine came to a result (UNKNOWN)"
            firstError != null -> firstError
            else -> "The task ended with $status"
        }
    }

    companion object {
        private const val SUMMARY = "summary: "
        private const val ELAPSED = "Elapsed "
        private const val UNREACHED_COVERS = "unreached cover statements:"
        private const val COUNTEREXAMPLE = "counterexample trace"
        private const val MAX_ERRORS = 20

        private val DONE = Regex("""^DONE \((\w+), rc=(\d+)\)""")
        private val ENGINE_STATUS = Regex("""^engine_\d+ \(.*\) (?:returned|did not return)""")
        private val TRACE = Regex("""^(counterexample trace|cover trace|trace)(?: \[([^\]]+)])?: (.+)$""")
        private val PROPERTY =
            Regex("""^\s+(failed assertion|reached cover statement|violated assumption) (\S+) at (\S+)(?: steps? ([\d, ]+))?$""")
        private val UNREACHED = Regex("""^\s+(\S+) at (\S+)$""")
        private val WRITING_TRACE = Regex("""Writing trace to VCD file: (\S+)""")

        /** Lines such as `base: counter.sv:11: ERROR: syntax error` and `ERROR: …`; `task failed. ERROR.` says nothing. */
        private fun isError(message: String): Boolean =
            ("ERROR" in message && !message.endsWith("task failed. ERROR.")) || message.startsWith("Reached TIMEOUT")

        /** An error without the name of the step in front, as in `counter.sv:11: ERROR: syntax error`. */
        private fun errorText(message: String): String = message.replaceFirst(Regex("""^[\w.]+: (?=\S+: ERROR|ERROR)"""), "")

        /**
         * The name of a property as written in the source, as `a_count` of `counter.a_count`. Properties
         * without a label get made-up names such as `counter._witness_.check_counter_sv_14_assert_8`, which
         * say less than the location does.
         */
        internal fun displayName(name: String): String? = name.takeUnless { "_witness_" in it || '$' in it }
    }
}
