package com.jotrorox.verilogtoolbox.icarus

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One command of a test. The last step of a test is the simulation; the ones before it build the design. */
class SimulationStep(
    val commandLine: GeneralCommandLine,
    /** When the output of a build step is shown. It becomes the failure in any case if the step fails. */
    val output: BuildOutput = BuildOutput.WARNINGS,
    /** The headline of the failure when a build step fails. */
    val failure: String = "Compilation failed",
    /**
     * Run once a build step has succeeded, such as writing what the next step reads, and after the simulation
     * however it ended. Throws [ExecutionException] to fail the test.
     */
    val afterwards: (() -> Unit)? = null,
)

/** When the output of a build step is shown in the console. */
enum class BuildOutput {
    /** Once the step has succeeded: what a compiler says then is warnings. */
    WARNINGS,

    /** As it comes, for a long build that tells its progress. */
    LIVE,

    /** Only as the failure: what it says on success is of no interest. */
    ON_FAILURE,
}

/** Decides from what a simulation prints and how it exits whether it passed. */
interface SimulationOutput {
    /** Takes the simulation's standard output as it arrives, in chunks that need not end at a line break. */
    fun simulatorText(text: String)

    /** Why the simulation failed, or null if it passed. */
    fun simulationFailure(exitCode: Int): Failure?

    /** Why a test failed: [message] is the headline, [details] the text below it. */
    data class Failure(val message: String, val details: String)
}

/** A testbench as a node of the test tree. */
class SimulationTest(
    val name: String,
    /** What the tree node navigates to and what the result is remembered under, see [VerilogTestLocator]. */
    val locationUrl: String?,
    /** The node the test is shown below, or null for the top level. Tests of a suite follow each other. */
    val suite: String?,
    val suiteLocationUrl: String?,
    /**
     * The steps to run in order, built when the test starts and given a sink for lines about them. The last one
     * is the simulation, the ones before it build the design.
     */
    val steps: ((String) -> Unit) -> List<SimulationStep>,
    /** Follows the simulation, not the build. */
    val simulationListener: ProcessListener? = null,
    /** Reads the output of the simulation, a new one for each run of the test. */
    val output: () -> SimulationOutput = { IcarusTestOutput() },
)

/**
 * Runs testbenches one after another as a single process whose output carries the service messages
 * the IDE's test runner reads, so each testbench becomes a test that passes or fails as its
 * [SimulationOutput] decides. [tests] is evaluated on a background thread.
 */
class SimulationTestsProcessHandler(private val tests: () -> List<SimulationTest>) : ProcessHandler() {
    private val lock = Any()
    private var current: OSProcessHandler? = null
    private var stopRequested = false

    /** A service message is only recognised at the start of a line. */
    @Volatile
    private var atLineStart = true

    private val stopped: Boolean
        get() = synchronized(lock) { stopRequested }

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            var passed = false
            try {
                passed = runAll(tests())
            } catch (e: ExecutionException) {
                notifyTextAvailable((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
            } finally {
                notifyProcessTerminated(if (stopped) STOPPED_EXIT_CODE else if (passed) 0 else FAILED_EXIT_CODE)
            }
        }
    }

    private fun runAll(tests: List<SimulationTest>): Boolean {
        message(ServiceMessageBuilder("testCount").addAttribute("count", tests.size.toString()))
        var passed = true
        var suite: String? = null
        for (test in tests) {
            if (stopped) break
            if (test.suite != suite) {
                suite?.let { message(ServiceMessageBuilder.testSuiteFinished(it)) }
                suite = test.suite
                suite?.let { message(ServiceMessageBuilder.testSuiteStarted(it).location(test.suiteLocationUrl)) }
            }
            passed = runTest(test) && passed
        }
        // A stopped run reports nothing more: the test runner then shows what was running as terminated.
        if (!stopped) suite?.let { message(ServiceMessageBuilder.testSuiteFinished(it)) }
        return passed
    }

    private fun runTest(test: SimulationTest): Boolean {
        message(ServiceMessageBuilder.testStarted(test.name).location(test.locationUrl))
        val start = System.nanoTime()
        val failure = try {
            runSteps(test.steps { say(it) }, test.simulationListener, test.output())
        } catch (e: ExecutionException) {
            SimulationOutput.Failure(e.message ?: e.toString(), "")
        }
        if (stopped) return false
        if (failure != null) {
            message(
                ServiceMessageBuilder.testFailed(test.name)
                    .addAttribute("message", failure.message).addAttribute("details", failure.details)
            )
        }
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        message(ServiceMessageBuilder.testFinished(test.name).addAttribute("duration", duration.toString()))
        return failure == null
    }

    private fun say(line: String) {
        val prefix = if (atLineStart) "" else "\n"
        atLineStart = true
        notifyTextAvailable("$prefix$line\n", ProcessOutputTypes.SYSTEM)
    }

    /** Runs [steps] until one fails, and says why the test failed if it did. */
    @Throws(ExecutionException::class)
    private fun runSteps(
        steps: List<SimulationStep>, simulationListener: ProcessListener?, output: SimulationOutput,
    ): SimulationOutput.Failure? {
        for ((index, step) in steps.withIndex()) {
            val simulation = index == steps.lastIndex
            val buildOutput = StringBuilder()
            // With no step running, a stop request is noticed here, before the next one is launched.
            val handler = synchronized(lock) {
                if (stopRequested) return null
                OSProcessHandler(step.commandLine).also { current = it }
            }
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when {
                        // The command line.
                        outputType == ProcessOutputTypes.SYSTEM -> show(event.text, outputType)
                        simulation -> {
                            if (ProcessOutputType.isStdout(outputType)) output.simulatorText(event.text)
                            show(event.text, outputType)
                        }
                        else -> {
                            // Kept in any case: it becomes the failure if the step fails.
                            synchronized(buildOutput) { buildOutput.append(event.text) }
                            if (step.output == BuildOutput.LIVE) show(event.text, outputType)
                        }
                    }
                }
            })
            if (simulation && simulationListener != null) handler.addProcessListener(simulationListener)
            handler.startNotify()
            handler.waitFor()
            synchronized(lock) { current = null }
            val exitCode = handler.exitCode ?: FAILED_EXIT_CODE
            if (simulation) {
                step.afterwards?.invoke()
                return output.simulationFailure(exitCode)
            }
            val text = synchronized(buildOutput) { buildOutput.toString() }
            if (exitCode != 0) {
                val tool = step.commandLine.exePath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast(".exe")
                return SimulationOutput.Failure(step.failure, text.trimEnd().ifEmpty { "$tool exited with code $exitCode" })
            }
            // Warnings, which were not shown while the step ran.
            if (step.output == BuildOutput.WARNINGS && text.isNotEmpty()) show(text, ProcessOutputTypes.STDERR)
            step.afterwards?.invoke()
        }
        return null
    }

    private fun show(text: String, outputType: Key<*>) {
        atLineStart = text.endsWith("\n")
        notifyTextAvailable(text, outputType)
    }

    private fun message(message: ServiceMessageBuilder) {
        val prefix = if (atLineStart) "" else "\n"
        atLineStart = true
        notifyTextAvailable("$prefix$message\n", ProcessOutputTypes.STDOUT)
    }

    private fun ServiceMessageBuilder.location(url: String?): ServiceMessageBuilder =
        if (url == null) this else addAttribute("locationHint", url)

    override fun destroyProcessImpl() {
        synchronized(lock) {
            stopRequested = true
            current
        }?.destroyProcess()
    }

    override fun detachProcessImpl() = destroyProcessImpl()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = synchronized(lock) { current }?.processInput

    private companion object {
        const val FAILED_EXIT_CODE = 1
        const val STOPPED_EXIT_CODE = 130
    }
}
