package com.jotrorox.verilogtoolbox

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.Filter
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Runs the testbenches of a configuration in the test runner: the one it names, or every one below
 * its directory. Each is compiled with `iverilog` and, if it compiles, simulated with `vvp`.
 */
class IcarusRunState(
    private val configuration: IcarusRunConfiguration,
    /** The location URLs of the testbenches to run, to run the failed ones again; null for all. */
    private val only: Set<String>? = null,
) : RunProfileState {
    /** Known once the run has looked for them, which it does in the background. */
    @Volatile
    private var testbenches: List<Testbench> = emptyList()

    /** The tree's selection, kept here because console filters run off the UI thread. */
    @Volatile
    private var selectedUrl: String? = null

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = IcarusTestsProcessHandler {
            findTestbenches().also { testbenches = it }.map { it.test }
        }
        ProcessTerminatedListener.attach(handler)
        val properties = ConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createConsole(properties)
        console.attachToProcess(handler)
        val tree = console.resultsViewer.treeView
        tree?.addTreeSelectionListener { selectedUrl = tree.selectedTest?.locationUrl }
        console.addMessageFilter(
            IcarusConsoleFilter(configuration.project) { testbenches.mapNotNull { it.workingDirectory } }
        )
        console.addMessageFilter(Filter { line, entireLength ->
            val dump = Waveforms.dumpFileRange(line)?.let { line.substring(it) } ?: return@Filter null
            val testbench = waveformSource(dump) ?: return@Filter null
            WaveformConsoleFilter(testbench.configuration, testbench.simulationDirectory ?: return@Filter null)
                .applyFilter(line, entireLength)
        })
        val openWaveform = OpenWaveformAction {
            val testbench = waveformSource()
            // Without a dump the action tells how to make the testbench write one.
            (testbench?.configuration ?: configuration) to testbench?.dumps?.dumpFile
        }
        val rerunFailed = properties.createRerunFailedTestsAction(console).apply { setModelProvider { console.resultsViewer } }
        return DefaultExecutionResult(console, handler, openWaveform).apply { setRestartActions(rerunFailed) }
    }

    @Throws(ExecutionException::class)
    private fun findTestbenches(): List<Testbench> {
        val directory = configuration.testbenchDirectory
        val all =
            if (directory == null) {
                listOf(Testbench(configuration, configuration.testbenchFile, configuration.resolveTopModule(), null))
            } else {
                VerilogTestbenches.inDirectory(directory).toList().mapIndexed { index, (file, module) ->
                    val name = "${configuration.name} ${index + 1} ${module.name}"
                    Testbench(configuration.forTestbench(file, module.name, name), file, module.name, directory)
                }.ifEmpty {
                    throw ExecutionException("No testbench was found in '$directory': no Verilog file below it has a module without ports.")
                }
            }
        return all.filter { only == null || it.test.locationUrl in only }
    }

    /**
     * The testbench whose waveform is meant: the one selected in the tree, else the last one that dumped
     * any, or a file called like [dump] if that is given. A run of several has no single waveform.
     */
    private fun waveformSource(dump: String? = null): Testbench? {
        val dumped = testbenches.filter { testbench ->
            val file = testbench.dumps?.dumpFile
            file != null && (dump == null || file.name == FileUtil.toSystemIndependentName(dump).substringAfterLast('/'))
        }
        return dumped.firstOrNull { it.test.locationUrl == selectedUrl } ?: dumped.lastOrNull()
    }

    /** A testbench of the run, with the configuration that compiles and simulates it alone. */
    private class Testbench(val configuration: IcarusRunConfiguration, file: Path?, module: String?, directory: Path?) {
        val workingDirectory = configuration.workingDirectory
        val simulationDirectory = configuration.simulationDirectory
        val dumps = simulationDirectory?.let { WaveformDumpListener(it) }

        /** In a run of a directory the tree groups the testbenches by their file. */
        val test = IcarusTestbench(
            name = module ?: file?.name ?: configuration.name,
            locationUrl = if (file != null && module != null) VerilogTestLocator.url(file, module) else null,
            suite = if (file != null && directory != null) FileUtil.toSystemIndependentName(directory.relativize(file).toString()) else null,
            suiteLocationUrl = file?.let { VerilogTestLocator.fileUrl(it) },
            steps = { report -> IcarusCommandLines.create(configuration, report) },
            simulationListener = dumps,
        )
    }

    private class ConsoleProperties(configuration: IcarusRunConfiguration, executor: Executor) :
        SMTRunnerConsoleProperties(configuration, "Icarus Verilog", executor) {

        override fun getTestLocator(): SMTestLocator = VerilogTestLocator

        override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
            RerunFailedAction(consoleView, this)
    }

    private class RerunFailedAction(console: ConsoleView, properties: ConsoleProperties) :
        AbstractRerunFailedTestsAction(console) {
        init {
            init(properties)
        }

        override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile {
            val configuration = myConsoleProperties.configuration as IcarusRunConfiguration
            val failed = getFailedTests(configuration.project).mapNotNull { it.locationUrl }.toSet()
            return object : MyRunProfile(configuration) {
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
                    // The compiler reads the sources from disk.
                    FileDocumentManager.getInstance().saveAllDocuments()
                    return IcarusRunState(configuration, failed)
                }
            }
        }
    }
}
