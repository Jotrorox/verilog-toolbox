package com.jotrorox.verilogtoolbox

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

/** A testbench as a node of the test tree. */
class IcarusTestbench(
    val name: String,
    /** What the tree node navigates to and what the result is remembered under, see [VerilogTestLocator]. */
    val locationUrl: String?,
    /** The node the test is shown below, or null for the top level. Tests of a suite follow each other. */
    val suite: String?,
    val suiteLocationUrl: String?,
    /**
     * The command lines to run in order, built when the test starts and given a sink for lines about
     * them. The last one is the simulation, the ones before it compile the design.
     */
    val steps: ((String) -> Unit) -> List<GeneralCommandLine>,
    /** Follows the simulation, not the compilation. */
    val simulationListener: ProcessListener? = null,
)

/**
 * Runs testbenches one after another as a single process whose output carries the service messages
 * the IDE's test runner reads, so each testbench becomes a test that passes or fails as
 * [IcarusTestOutput] decides. [tests] is evaluated on a background thread.
 */
class IcarusTestsProcessHandler(private val tests: () -> List<IcarusTestbench>) : ProcessHandler() {
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

    private fun runAll(tests: List<IcarusTestbench>): Boolean {
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

    private fun runTest(test: IcarusTestbench): Boolean {
        message(ServiceMessageBuilder.testStarted(test.name).location(test.locationUrl))
        val start = System.nanoTime()
        val failure = try {
            runSteps(test.steps { notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }, test.simulationListener)
        } catch (e: ExecutionException) {
            IcarusTestOutput.Failure(e.message ?: e.toString(), "")
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

    /** Runs [steps] until one fails, and says why the test failed if it did. */
    private fun runSteps(steps: List<GeneralCommandLine>, simulationListener: ProcessListener?): IcarusTestOutput.Failure? {
        val output = IcarusTestOutput()
        for ((index, step) in steps.withIndex()) {
            val simulation = index == steps.lastIndex
            // With no step running, a stop request is noticed here, before the next one is launched.
            val handler = synchronized(lock) {
                if (stopRequested) return null
                OSProcessHandler(step).also { current = it }
            }
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when {
                        // The command line.
                        outputType == ProcessOutputTypes.SYSTEM -> notifyTextAvailable(event.text, outputType)
                        // Held back: it becomes the failure message if the design does not compile.
                        !simulation -> synchronized(output) { output.compilerText(event.text) }
                        else -> {
                            if (ProcessOutputType.isStdout(outputType)) {
                                output.simulatorText(event.text)
                                atLineStart = event.text.endsWith("\n")
                            }
                            notifyTextAvailable(event.text, outputType)
                        }
                    }
                }
            })
            if (simulation && simulationListener != null) handler.addProcessListener(simulationListener)
            handler.startNotify()
            handler.waitFor()
            synchronized(lock) { current = null }
            val exitCode = handler.exitCode ?: FAILED_EXIT_CODE
            if (simulation) return output.simulationFailure(exitCode)
            synchronized(output) { output.compilationFailure(exitCode) }?.let { return it }
            val warnings = synchronized(output) { output.compilerMessages }
            if (warnings.isNotEmpty()) notifyTextAvailable(warnings, ProcessOutputTypes.STDERR)
        }
        return null
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
