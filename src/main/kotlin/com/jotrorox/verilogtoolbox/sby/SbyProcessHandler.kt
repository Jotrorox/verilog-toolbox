package com.jotrorox.verilogtoolbox.sby

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap

/** A task of a run as a node of the test tree. */
data class SbyTask(val name: String, val locationUrl: String?)

/**
 * Runs SymbiYosys and turns its output into the service messages the IDE's test runner reads: each task
 * becomes a test, started at its first line and passed or failed at its `DONE` line, as [SbyTaskResult]
 * decides. The lines of a task are shown under it; tasks run in parallel, so their tests do too.
 * [taskOf] names the task of a workdir; [runName] is the test that stands for a run that failed before any
 * task started.
 */
class SbyProcessHandler(
    commandLine: GeneralCommandLine,
    private val taskOf: (workdir: String) -> SbyTask,
    private val runName: String,
) : KillableProcessHandler(commandLine) {
    private val lock = Any()
    private val partialLines = HashMap<Key<*>, StringBuilder>()

    /** The results by workdir, in the order the tasks started. */
    private val results = LinkedHashMap<String, SbyTaskResult>()
    private val tasks = ConcurrentHashMap<String, SbyTask>()
    private val running = LinkedHashSet<String>()
    private val starts = HashMap<String, Long>()
    private val unattributed = StringBuilder()

    @Volatile
    private var stopRequested = false

    init {
        // A soft stop is Ctrl+C, which the Windows launcher of sby passes to no one: the solvers would go on to
        // the end. The whole tree goes at once instead; SymbiYosys keeps nothing worth a clean exit.
        setShouldKillProcessSoftly(false)
        setShouldDestroyProcessRecursively(true)
    }

    /** The task of a test, by the test's name. */
    fun result(testName: String): SbyTaskResult? = synchronized(lock) {
        tasks.entries.firstOrNull { it.value.name == testName }?.key?.let { results[it] }
    }

    /** The results of the tasks so far, in the order they started. */
    fun results(): List<SbyTaskResult> = synchronized(lock) { results.values.toList() }

    fun task(result: SbyTaskResult): SbyTask? = tasks[result.workdir]

    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        if (outputType == ProcessOutputTypes.SYSTEM) return super.notifyTextAvailable(text, outputType)
        // Output arrives in chunks that need not end at a line break.
        val lines = synchronized(lock) {
            val partial = partialLines.getOrPut(outputType) { StringBuilder() }.append(text)
            val complete = mutableListOf<String>()
            while (true) {
                val end = partial.indexOf("\n")
                if (end < 0) break
                complete += partial.substring(0, end + 1)
                partial.delete(0, end + 1)
            }
            complete
        }
        lines.forEach { line(it, outputType) }
    }

    private fun line(text: String, outputType: Key<*>) {
        val line = SbyOutput.parse(text)
        val workdir = line?.workdir
        if (line == null || workdir == null || !ProcessOutputType.isStdout(outputType)) {
            synchronized(lock) { if (unattributed.length < MAX_UNATTRIBUTED) unattributed.append(text) }
            return super.notifyTextAvailable(text, outputType)
        }
        val messages = mutableListOf<ServiceMessageBuilder>()
        synchronized(lock) {
            val result = results.getOrPut(workdir) {
                val task = taskOf(workdir).also { tasks[workdir] = it }
                running += workdir
                starts[workdir] = System.nanoTime()
                messages += ServiceMessageBuilder.testStarted(task.name).location(task.locationUrl)
                SbyTaskResult(workdir)
            }
            val name = tasks.getValue(workdir).name
            if (workdir in running) messages += ServiceMessageBuilder("testStdOut").addAttribute("name", name).addAttribute("out", text)
            result.accept(line.message)
            if (result.isDone && running.remove(workdir)) messages += finished(workdir, result)
        }
        messages.forEach(::message)
        if (messages.isEmpty()) super.notifyTextAvailable(text, outputType)
    }

    /** The messages that end the test of a task. */
    private fun finished(workdir: String, result: SbyTaskResult): List<ServiceMessageBuilder> {
        val name = tasks.getValue(workdir).name
        val failure = result.failure
        val duration = (System.nanoTime() - (starts[workdir] ?: System.nanoTime())) / 1_000_000
        return listOfNotNull(
            failure?.let {
                ServiceMessageBuilder.testFailed(name).addAttribute("message", it.message).addAttribute("details", it.details)
            },
            ServiceMessageBuilder.testFinished(name).addAttribute("duration", duration.toString()),
        )
    }

    override fun destroyProcessImpl() {
        stopRequested = true
        super.destroyProcessImpl()
    }

    override fun onOSProcessTerminated(exitCode: Int) {
        // The output readers have finished; what is left of a line without a break is a line too.
        val rest = synchronized(lock) { partialLines.entries.map { it.key to it.value.toString() }.also { partialLines.clear() } }
        rest.filter { it.second.isNotEmpty() }.forEach { (type, text) -> line(text + "\n", type) }
        val messages = mutableListOf<ServiceMessageBuilder>()
        synchronized(lock) {
            // A stopped run reports nothing more: the test runner then shows what was running as terminated.
            if (!stopRequested) {
                // Such as a Python error, which SymbiYosys prints without its prefix.
                val output = unattributed.toString().trim()
                for (workdir in running.toList()) {
                    val name = tasks.getValue(workdir).name
                    messages += ServiceMessageBuilder.testFailed(name)
                        .addAttribute("message", "SymbiYosys ended before the task was done (exit code $exitCode)")
                        .addAttribute("details", output)
                    messages += ServiceMessageBuilder.testFinished(name)
                }
                running.clear()
                if (results.isEmpty() && exitCode != 0) {
                    messages += ServiceMessageBuilder.testStarted(runName)
                    messages += ServiceMessageBuilder.testFailed(runName)
                        .addAttribute("message", startFailure(output, exitCode))
                        .addAttribute("details", output)
                    messages += ServiceMessageBuilder.testFinished(runName)
                }
            }
        }
        messages.forEach(::message)
        super.onOSProcessTerminated(exitCode)
    }

    private fun message(message: ServiceMessageBuilder) {
        super.notifyTextAvailable("$message\n", ProcessOutputTypes.STDOUT)
    }

    private fun ServiceMessageBuilder.location(url: String?): ServiceMessageBuilder =
        if (url == null) this else addAttribute("locationHint", url)

    internal companion object {
        private const val MAX_UNATTRIBUTED = 100_000

        /** Why no task started, from the output of the run: SymbiYosys's own `ERROR:` line, else Python's error. */
        internal fun startFailure(output: String, exitCode: Int): String {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return lines.firstOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
                ?: lines.lastOrNull { PYTHON_ERROR.matches(it) }
                ?: "SymbiYosys exited with code $exitCode"
        }

        private val PYTHON_ERROR = Regex("""^\w+(Error|Exception): .+""")
    }
}
