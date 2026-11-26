package com.jotrorox.verilogtoolbox.sby

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.waveform.WaveformSource
import com.jotrorox.verilogtoolbox.waveform.Waveforms
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Runs the tasks of a configuration in the test runner, with the console linking sources and traces, and
 * "Open Trace" showing the trace of the selected task, or of the last one that failed, in the waveform viewer.
 */
class SbyRunState(private val configuration: SbyRunConfiguration) : RunProfileState {
    /** The tree's selection, kept here because the toolbar's action is updated off the UI thread. */
    @Volatile
    private var selectedTest: String? = null

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val sby = ToolchainSettings.getInstance().findSby() ?: throw ExecutionException("sby was not found.")
        val workingDirectory = configuration.workingDirectory ?: throw ExecutionException("No working directory.")
        val text = configuration.sbyFile?.let { runCatching { Files.readString(it) }.getOrNull() }.orEmpty()
        val waveform = SbyWaveform(configuration.project, configuration.sbyFile ?: workingDirectory, SbyConfig.topModule(text))

        val handler = SbyProcessHandler(configuration.commandLine(sby), configuration::taskOf, configuration.runName)
        ProcessTerminatedListener.attach(handler)
        handler.addProcessListener(object : ProcessListener {
            /** A viewer that shows a trace of the previous run shows this run's. */
            override fun processTerminated(event: ProcessEvent) {
                for (result in handler.results()) {
                    result.traces.mapNotNull { resolve(workingDirectory, it.path) }.forEach(Waveforms::reloadIfOpen)
                }
            }
        })
        val properties = ConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createConsole(properties)
        console.attachToProcess(handler)
        val tree = console.resultsViewer.treeView
        tree?.addTreeSelectionListener { selectedTest = tree.selectedTest?.takeIf { it.isLeaf }?.name }
        val files = SbyConfig.files(text)
        console.addMessageFilter(
            SbyConsoleFilter(
                configuration.project, workingDirectory,
                resolveSource = { name -> SbyConsoleFilter.resolveSource(name, workingDirectory, files) { handler.results().map { it.workdir } } },
                openTrace = { trace -> Waveforms.open(waveform, trace) },
            )
        )
        val openTrace = OpenTraceAction {
            val result = selectedTest?.let(handler::result)
                ?: handler.results().lastOrNull { it.failure != null && it.mainTrace != null }
                ?: handler.results().lastOrNull { it.mainTrace != null }
            val trace = result?.mainTrace?.let { resolve(workingDirectory, it) }
            if (trace == null) {
                val which = result?.let { handler.task(it)?.name }?.let { "Task '$it'" } ?: "No task"
                Waveforms.notify(
                    configuration.project, "No trace to show",
                    "$which wrote no trace. Engines write a counterexample when an assertion fails and a trace for each " +
                        "cover statement they reach; a proof that holds has none.",
                    NotificationType.INFORMATION,
                )
            } else {
                Waveforms.open(waveform, trace)
            }
        }
        val rerunFailed = properties.createRerunFailedTestsAction(console).apply { setModelProvider { console.resultsViewer } }
        return DefaultExecutionResult(console, handler, openTrace).apply { setRestartActions(rerunFailed) }
    }

    private fun resolve(directory: Path, path: String): Path? =
        try {
            directory.resolve(path).normalize()
        } catch (_: InvalidPathException) {
            null
        }

    internal companion object {
        /**
         * The configuration that runs the tasks of [failed] tests again. The test of a file without tasks, or of
         * a run that failed before any task started, stands for all of it.
         */
        internal fun rerunConfiguration(configuration: SbyRunConfiguration, failed: List<String>): SbyRunConfiguration =
            configuration.forTasks(if (configuration.runName in failed) emptyList() else failed)
    }

    /**
     * The traces of a `.sby` file share a layout next to it, named after the top module of its script, as
     * `counter.gtkw`. The top module is also the scope at the top of every trace.
     */
    private class SbyWaveform(private val project: Project, sbyFile: Path, private val top: String?) : WaveformSource {
        override fun getProject(): Project = project

        override val testbenchFile: Path = sbyFile

        override fun resolveTopModule(): String? = top
    }

    private class ConsoleProperties(configuration: SbyRunConfiguration, executor: Executor) :
        SMTRunnerConsoleProperties(configuration, "SymbiYosys", executor) {

        override fun getTestLocator(): SMTestLocator = SbyTestLocator

        override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
            RerunFailedAction(consoleView, this)
    }

    private class RerunFailedAction(console: ConsoleView, properties: ConsoleProperties) : AbstractRerunFailedTestsAction(console) {
        init {
            init(properties)
        }

        override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile {
            val configuration = myConsoleProperties.configuration as SbyRunConfiguration
            val rerun = rerunConfiguration(configuration, getFailedTests(configuration.project).map { it.name })
            return object : MyRunProfile(configuration) {
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
                    FileDocumentManager.getInstance().saveAllDocuments()
                    return SbyRunState(rerun)
                }
            }
        }
    }
}

/** "Open Trace" in the toolbar of a run. */
private class OpenTraceAction(private val open: () -> Unit) :
    DumbAwareAction(
        "Open Trace",
        "Open the counterexample or cover trace of the selected task, or of the last task that failed, in GTKWave or Surfer",
        IconLoader.getIcon("/icons/waveform.svg", OpenTraceAction::class.java),
    ) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = open()
}
