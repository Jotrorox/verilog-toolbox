package com.jotrorox.verilogtoolbox

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.execution.ParametersListUtil
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** Runs `iverilog` and, if the design compiles, `vvp` in one console. */
class IcarusRunState(private val configuration: IcarusRunConfiguration, environment: ExecutionEnvironment) :
    CommandLineState(environment) {

    private val workingDirectory = configuration.workingDirectory
    private val dumps = workingDirectory?.let { WaveformDumpListener(it) }

    init {
        if (workingDirectory != null) {
            addConsoleFilters(
                IcarusConsoleFilter(configuration.project, workingDirectory),
                WaveformConsoleFilter(configuration, workingDirectory),
            )
        }
    }

    override fun startProcess(): ProcessHandler =
        StepsProcessHandler { IcarusCommandLines.create(configuration) }.also {
            ProcessTerminatedListener.attach(it)
            if (dumps != null) it.addProcessListener(dumps)
        }

    override fun createActions(console: ConsoleView, processHandler: ProcessHandler, executor: Executor): Array<AnAction> =
        super.createActions(console, processHandler, executor) +
            listOfNotNull(dumps?.let { OpenWaveformAction(configuration, it) })
}

/** Builds the compile and simulate command lines of a run configuration. */
object IcarusCommandLines {
    /** Extensions `iverilog` is given to compile; `.vh` and `.svh` are headers that only get included. */
    private val SOURCE_EXTENSIONS = setOf("v", "sv")

    @Throws(ExecutionException::class)
    fun create(configuration: IcarusRunConfiguration): List<GeneralCommandLine> {
        val toolchain = ToolchainSettings.getInstance()
        val iverilog = toolchain.findIverilog() ?: throw ExecutionException("iverilog was not found.")
        val vvp = toolchain.findVvp() ?: throw ExecutionException("vvp was not found.")
        val settings = configuration.settings
        val testbench = configuration.testbenchFile?.takeIf { it.isRegularFile() }
            ?: throw ExecutionException("Testbench file '${settings.testbench.orEmpty()}' does not exist.")
        val workingDirectory = configuration.workingDirectory?.takeIf { Files.isDirectory(it) }
            ?: throw ExecutionException("The working directory does not exist.")

        val topModule = configuration.resolveTopModule()
        val design = compiledDesign(configuration)
        val libraries = libraryDirectories(configuration.project)

        val compile = ToolchainSettings.icarusCommandLine(iverilog).withWorkingDirectory(workingDirectory)
        compile.addParameters(ParametersListUtil.parse(settings.compilerOptions.orEmpty()))
        compile.addParameters("-o", design.toString())
        if (topModule != null) compile.addParameters("-s", topModule)
        for (directory in libraries) compile.addParameters("-I", directory.toString(), "-y", directory.toString())
        // Library modules are looked up as <module>.v; look for <module>.sv as well.
        if (libraries.isNotEmpty()) compile.addParameters("-Y", ".sv")
        compile.addParameters(sources(testbench, workingDirectory, settings.sources.orEmpty()).map { it.toString() })

        val simulate = ToolchainSettings.icarusCommandLine(vvp).withWorkingDirectory(workingDirectory)
        simulate.addParameters(ParametersListUtil.parse(settings.simulatorOptions.orEmpty()))
        simulate.addParameter(design.toString())
        simulate.addParameters(ParametersListUtil.parse(settings.simulationArguments.orEmpty()))
        return listOf(compile, simulate)
    }

    /** The testbench plus either the [configured] sources or the other sources in the testbench's directory. */
    internal fun sources(testbench: Path, workingDirectory: Path, configured: String): List<Path> {
        val others =
            if (configured.isBlank()) {
                testbench.parent?.listDirectoryEntries()
                    ?.filter { it.isRegularFile() && it.extension.lowercase() in SOURCE_EXTENSIONS }?.sorted().orEmpty()
            } else {
                ParametersListUtil.parse(configured).map { workingDirectory.resolve(it).normalize() }
            }
        return (listOf(testbench) + others).distinctBy { it.toAbsolutePath().normalize() }
    }

    /** The compiled design is an intermediate file, kept out of the project. */
    private fun compiledDesign(configuration: IcarusRunConfiguration): Path {
        val directory = Path.of(FileUtil.getTempDirectory(), "verilog-toolbox", configuration.project.locationHash)
        try {
            Files.createDirectories(directory)
        } catch (e: IOException) {
            throw ExecutionException("Cannot create '$directory': ${e.message}", e)
        }
        return directory.resolve(FileUtil.sanitizeFileName(configuration.name) + ".vvp")
    }

    /** Every project directory with Verilog files, searched for the modules and headers the sources refer to. */
    private fun libraryDirectories(project: Project): List<Path> =
        ReadAction.nonBlocking(Callable {
            FileTypeIndex.getFiles(VerilogFileType, GlobalSearchScope.projectScope(project))
                .mapNotNull { it.parent?.takeIf { parent -> parent.isInLocalFileSystem }?.toNioPath() }
                .distinct().sorted()
        }).inSmartMode(project).executeSynchronously()
}

/**
 * Runs command lines one after another as a single process: output is forwarded as it arrives and the
 * chain stops at the first step that fails. [steps] is evaluated on a background thread.
 */
class StepsProcessHandler(private val steps: () -> List<GeneralCommandLine>) : ProcessHandler() {
    private val lock = Any()
    private var current: OSProcessHandler? = null
    private var stopRequested = false

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launch(steps(), 0)
            } catch (e: ExecutionException) {
                fail(e)
            }
        }
    }

    private fun launch(steps: List<GeneralCommandLine>, index: Int) {
        val step = try {
            synchronized(lock) {
                if (stopRequested || index > steps.lastIndex) null
                else OSProcessHandler(steps[index]).also { current = it }
            }
        } catch (e: ExecutionException) {
            return fail(e)
        } ?: return notifyProcessTerminated(STOPPED_EXIT_CODE)

        step.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
                notifyTextAvailable(event.text, outputType)

            override fun processTerminated(event: ProcessEvent) {
                val proceed = synchronized(lock) {
                    current = null
                    !stopRequested && event.exitCode == 0 && index < steps.lastIndex
                }
                if (proceed) launch(steps, index + 1) else notifyProcessTerminated(event.exitCode)
            }
        })
        step.startNotify()
    }

    private fun fail(e: ExecutionException) {
        notifyTextAvailable((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
        notifyProcessTerminated(FAILED_EXIT_CODE)
    }

    override fun destroyProcessImpl() {
        // With no step running, the chain notices the request before it launches the next one.
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
