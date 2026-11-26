package com.jotrorox.verilogtoolbox.verilator

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jotrorox.verilogtoolbox.icarus.IcarusConsoleFilter
import com.jotrorox.verilogtoolbox.icarus.SimulationTest
import com.jotrorox.verilogtoolbox.icarus.SimulationTestsProcessHandler
import com.jotrorox.verilogtoolbox.icarus.VerilogTestLocator
import com.jotrorox.verilogtoolbox.icarus.VerilogTestbenches
import com.jotrorox.verilogtoolbox.waveform.OpenWaveformAction
import com.jotrorox.verilogtoolbox.waveform.Waveforms
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Runs the testbenches of a Verilator configuration in the test runner, the one it names or every Verilog
 * testbench below its directory, each verilated, built with CMake and simulated.
 */
class VerilatorRunState(
    private val configuration: VerilatorRunConfiguration,
    /** The location URLs of the testbenches to run, to run the failed ones again; null for all. */
    private val only: Set<String>? = null,
) : RunProfileState {
    @Volatile
    private var testbenches: List<Testbench> = emptyList()

    /** The tree's selection, kept here because console filters run off the UI thread. */
    @Volatile
    private var selectedUrl: String? = null

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = SimulationTestsProcessHandler {
            findTestbenches().also { testbenches = it }.map { it.test }
        }
        ProcessTerminatedListener.attach(handler)
        val properties = ConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createConsole(properties)
        console.attachToProcess(handler)
        val tree = console.resultsViewer.treeView
        tree?.addTreeSelectionListener { selectedUrl = tree.selectedTest?.locationUrl }
        // Verilator names the files as it was given them, the compiler with absolute paths.
        console.addMessageFilter(IcarusConsoleFilter(configuration.project) {
            testbenches.mapNotNull { it.configuration.workingDirectory } + listOfNotNull(configuration.project.basePath?.let { Path.of(it) })
        })
        console.addMessageFilter(WaveformLinkFilter { dump -> testbenches.firstOrNull { it.dump.file == dump }?.configuration ?: configuration })
        val openWaveform = OpenWaveformAction {
            val testbench = waveformSource()
            (testbench?.configuration ?: configuration) to testbench?.dump?.file
        }
        val openProject = LoadCMakeProjectAction(configuration.project) { configuration.cmakeRoot?.let { VerilatorCMakeProject.rootFile(it) } }
        val rerunFailed = properties.createRerunFailedTestsAction(console).apply { setModelProvider { console.resultsViewer } }
        return DefaultExecutionResult(console, handler, openWaveform, openProject).apply { setRestartActions(rerunFailed) }
    }

    @Throws(ExecutionException::class)
    private fun findTestbenches(): List<Testbench> {
        val directory = configuration.testbenchDirectory
        val all =
            if (directory == null) {
                val file = configuration.testbenchFile
                val module = if (configuration.isCppTestbench) null else configuration.resolveTopModule()
                listOf(Testbench(configuration, file, module, null))
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

    /** The testbench selected in the tree if it wrote a waveform, else the last one that wrote any. */
    private fun waveformSource(): Testbench? {
        val dumped = testbenches.filter { it.dump.file != null }
        return dumped.firstOrNull { it.test.locationUrl == selectedUrl } ?: dumped.lastOrNull()
    }

    private class Testbench(val configuration: VerilatorRunConfiguration, file: Path?, module: String?, directory: Path?) {
        val dump = VerilatorSimulation.Dump()

        /** A C++ testbench is told apart by its file: several may drive one module. */
        val test = SimulationTest(
            name = module ?: file?.name ?: configuration.name,
            locationUrl = when {
                file != null && module != null -> VerilogTestLocator.url(file, module)
                file != null -> VerilogTestLocator.fileUrl(file)
                else -> null
            },
            suite = if (file != null && directory != null) FileUtil.toSystemIndependentName(directory.relativize(file).toString()) else null,
            suiteLocationUrl = file?.let { VerilogTestLocator.fileUrl(it) },
            steps = { report -> VerilatorSimulation.steps(configuration, report, dump) },
            output = { VerilatorTestOutput() },
        )
    }

    private class ConsoleProperties(configuration: VerilatorRunConfiguration, executor: Executor) :
        SMTRunnerConsoleProperties(configuration, "Verilator", executor) {

        override fun getTestLocator(): SMTestLocator = VerilogTestLocator

        override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
            RerunFailedAction(consoleView, this)
    }

    private class RerunFailedAction(console: ConsoleView, properties: ConsoleProperties) : AbstractRerunFailedTestsAction(console) {
        init {
            init(properties)
        }

        override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile {
            val configuration = myConsoleProperties.configuration as VerilatorRunConfiguration
            val failed = getFailedTests(configuration.project).mapNotNull { it.locationUrl }.toSet()
            return object : MyRunProfile(configuration) {
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
                    FileDocumentManager.getInstance().saveAllDocuments()
                    return VerilatorRunState(configuration, failed)
                }
            }
        }
    }
}

/** Turns the waveform in `Waveform written to <path>` into a link that opens it in the viewer. */
private class WaveformLinkFilter(private val source: (Path) -> VerilatorRunConfiguration) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val text = line.trimEnd()
        if (!text.startsWith(VerilatorSimulation.WAVEFORM_ANNOUNCEMENT)) return null
        val start = VerilatorSimulation.WAVEFORM_ANNOUNCEMENT.length
        val dump = try {
            Path.of(text.substring(start))
        } catch (_: InvalidPathException) {
            return null
        }
        val lineStart = entireLength - line.length
        return Filter.Result(lineStart + start, lineStart + text.length, HyperlinkInfo { Waveforms.open(source(dump), dump) })
    }
}

/**
 * Loads the CMake project of the Verilator simulations into CLion, whose CMake application and CTest
 * configurations then build, run and debug them with CLion's toolchain and debugger. Done through CLion's own
 * Load CMake Project action, which outside CLion is not there: the file is opened instead.
 */
class LoadCMakeProjectAction(private val project: Project, private val cmakeLists: () -> Path?) :
    DumbAwareAction(
        "Load CMake Project in CLion",
        "Load the CMake project of the Verilator simulations, to build, run and debug them, C++ testbenches included, with CLion's tools",
        AllIcons.Actions.ModuleDirectory,
    ) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = cmakeLists()?.let { Files.isRegularFile(it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val path = cmakeLists() ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return notifyMissing(path)
        val load = ActionManager.getInstance().getAction(CLION_LOAD_ACTION)
        val psiFile = PsiManager.getInstance(project).findFile(file)
        if (load == null || psiFile == null) return OpenFileDescriptor(project, file).navigate(true)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
            .add(CommonDataKeys.PSI_FILE, psiFile)
            .add(CommonDataKeys.PSI_ELEMENT, psiFile)
            .build()
        val event = AnActionEvent.createEvent(load, context, load.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        ActionUtil.performAction(load, event)
    }

    private fun notifyMissing(path: Path) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification("'$path' does not exist yet; it is written by the first run.", NotificationType.WARNING)
            .notify(project)
    }

    private companion object {
        /** CLion's action, in the CMake plugin. */
        const val CLION_LOAD_ACTION = "CMake.LoadCMakeProject"
    }
}
