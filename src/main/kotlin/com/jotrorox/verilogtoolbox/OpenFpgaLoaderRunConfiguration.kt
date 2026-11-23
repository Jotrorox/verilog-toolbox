package com.jotrorox.verilogtoolbox

import com.intellij.execution.CantRunException
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.RunDialog
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueMatches
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.JBUI
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JList
import kotlin.io.path.name

class OpenFpgaLoaderConfigurationType : SimpleConfigurationType(
    ID, "openFPGALoader", "Loads a bitstream into an FPGA or writes it to the FPGA's flash with openFPGALoader",
    NotNullLazyValue.createValue { OpenFpgaLoaderIcons.OpenFpgaLoader },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        OpenFpgaLoaderRunConfiguration(project, this, "openFPGALoader")

    override fun getOptionsClass(): Class<out BaseState> = OpenFpgaLoaderRunConfigurationOptions::class.java

    companion object {
        const val ID = "VerilogToolboxOpenFpgaLoader"

        fun getInstance(): OpenFpgaLoaderConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(OpenFpgaLoaderConfigurationType::class.java)
    }
}

class OpenFpgaLoaderRunConfigurationOptions : LocatableRunConfigurationOptions() {
    /** The file to program; a relative path starts at the project directory. */
    var bitstream by string("")

    /** The name of a [FpgaDestination]. */
    var destination by string(FpgaDestination.SRAM.name)
    var board by string("")
    var cable by string("")
    var fpgaPart by string("")
    var verify by property(false)

    /** Run in the project directory before programming, so that the bitstream is up to date; empty for none. */
    var buildCommand by string("")
    var options by string("")
}

/** Programs a bitstream with openFPGALoader, after building it when a build command is set. */
class OpenFpgaLoaderRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<OpenFpgaLoaderRunConfigurationOptions>(project, factory, name) {

    val settings: OpenFpgaLoaderRunConfigurationOptions
        get() = options as OpenFpgaLoaderRunConfigurationOptions

    var destination: FpgaDestination
        get() = FpgaDestination.of(settings.destination)
        set(value) {
            settings.destination = value.name
        }

    val target: FpgaTarget
        get() = FpgaTarget(
            board = settings.board.orEmpty().trim(),
            cable = settings.cable.orEmpty().trim(),
            fpgaPart = settings.fpgaPart.orEmpty().trim(),
            destination = destination,
            verify = settings.verify,
            options = settings.options.orEmpty().trim(),
        )

    private val baseDirectory: Path?
        get() = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }

    /** The bitstream, whether it exists or not. */
    fun bitstreamPath(): Path? = settings.bitstream?.takeIf { it.isNotBlank() }?.let { path ->
        runCatching { (baseDirectory?.resolve(path.trim()) ?: Path.of(path.trim())).normalize() }.getOrNull()
    }

    /** Sets everything up as [programming] says. */
    internal fun apply(programming: FpgaProgramming) {
        val base = baseDirectory
        settings.bitstream = programming.bitstream?.let { if (base != null) OpenFpgaLoaderProject.relative(base, it) else it.toString() }.orEmpty()
        destination = programming.target.destination
        settings.board = programming.target.board
        settings.cable = programming.target.cable
        settings.fpgaPart = programming.target.fpgaPart
        settings.verify = programming.target.verify
        settings.options = programming.target.options
        settings.buildCommand = programming.buildCommand
    }

    override fun suggestedName(): String? = bitstreamPath()?.let { "${destination.verb} ${it.name}" }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = OpenFpgaLoaderSettingsEditor(project)

    override fun checkConfiguration() {
        val file = bitstreamPath() ?: throw RuntimeConfigurationError("No bitstream is selected.")
        val tool = ToolchainSettings.getInstance().findOpenFpgaLoader()
            ?: throw RuntimeConfigurationError("openFPGALoader was not found. Download the OSS CAD Suite or set its path in Settings | Tools | Verilog Toolbox.")
        if (Files.isDirectory(file)) throw RuntimeConfigurationError("'${file.name}' is a directory, not a bitstream.")
        val options = ParametersListUtil.parse(settings.options.orEmpty())
        if (!OpenFpgaLoader.isBitstream(file) && options.none { it.startsWith("--file-type") }) {
            throw RuntimeConfigurationWarning(
                "'${file.name}' does not look like a bitstream: openFPGALoader programs " +
                    OpenFpgaLoader.BITSTREAM_EXTENSIONS.joinToString(", ") { ".$it" } + " files. Give the kind with --file-type in the options."
            )
        }
        if (!Files.isRegularFile(file) && settings.buildCommand.isNullOrBlank()) {
            throw RuntimeConfigurationWarning("'${file.name}' does not exist yet. Build it first, or set a build command that builds it.")
        }
        val board = settings.board.orEmpty().trim()
        val boards = OpenFpgaLoader.knownBoards(tool)
        if (board.isNotEmpty() && boards != null && boards.isNotEmpty() && boards.none { it.name == board }) {
            throw RuntimeConfigurationWarning("openFPGALoader does not know the board '$board'.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (ToolchainSettings.getInstance().findOpenFpgaLoader() == null) {
            OpenFpgaLoaderRunner.notifyMissing(project)
            throw CantRunException.CustomProcessedCantRunException()
        }
        // The build reads the sources from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return OpenFpgaLoaderRunState(this)
    }
}

class OpenFpgaLoaderRunState(private val configuration: OpenFpgaLoaderRunConfiguration) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val toolchain = ToolchainSettings.getInstance()
        val tool = toolchain.findOpenFpgaLoader() ?: throw ExecutionException("openFPGALoader was not found.")
        val bitstream = configuration.bitstreamPath() ?: throw ExecutionException("No bitstream is selected in the run configuration.")
        val directory = project.basePath?.let { Path.of(it) } ?: bitstream.parent
        // The build finds the other tools of the suite openFPGALoader and Yosys come from, as `make` expects them on PATH.
        val toolDirectories = listOfNotNull(tool.parent, toolchain.findYosys()?.parent).distinct()
        val handler = OpenFpgaLoaderProcessHandler(
            tool, directory, bitstream, configuration.target, configuration.settings.buildCommand.orEmpty().trim(), toolDirectories,
        )
        ProcessTerminatedListener.attach(handler)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        // Links the `file:line` locations of the build's messages, such as Yosys's and nextpnr's.
        console.addMessageFilter(IcarusConsoleFilter(project) { listOf(directory) })
        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler)
    }
}

/**
 * Builds the bitstream when a build command is set, then programs it. openFPGALoader runs in terminal mode,
 * where it redraws its progress bar in place, which the console shows as one line; its warnings are coloured
 * and the run ends with what was programmed where, or what went wrong and what to do about it.
 */
internal class OpenFpgaLoaderProcessHandler(
    private val tool: Path,
    private val directory: Path,
    private val bitstream: Path,
    private val target: FpgaTarget,
    private val buildCommand: String,
    /** Put first on PATH for the build. */
    private val toolDirectories: List<Path>,
) : ProcessHandler() {
    @Volatile
    private var process: OSProcessHandler? = null

    @Volatile
    private var stopped = false

    private val finished = AtomicBoolean()
    private val loaderOutput = StringBuilder()

    /** Text of a segment that has not ended yet, per stream. */
    private val pending = HashMap<Key<*>, StringBuilder>()

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread { if (buildCommand.isNotEmpty()) build() else program() }
    }

    private fun build() {
        val commandLine = try {
            buildCommandLine()
        } catch (e: ExecutionException) {
            print((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
            return finish(1)
        }
        print("Building: $buildCommand\n", ProcessOutputTypes.SYSTEM)
        start(commandLine, loader = false) { exitCode ->
            if (exitCode == 0) {
                program()
            } else {
                if (!stopped) print("\nThe build failed (exit code $exitCode), so the FPGA was not programmed.\n", ProcessOutputTypes.STDERR)
                finish(exitCode)
            }
        }
    }

    /** The build command, with its program looked up on the PATH that it runs with. */
    @Throws(ExecutionException::class)
    private fun buildCommandLine(): GeneralCommandLine {
        val words = ParametersListUtil.parse(buildCommand)
        val program = words.firstOrNull() ?: throw ExecutionException("The build command is empty.")
        val path = (toolDirectories.map { it.toString() } + listOfNotNull(EnvironmentUtil.getValue("PATH")))
            .distinct().joinToString(File.pathSeparator)
        val executable = if ('/' in program || '\\' in program) {
            directory.resolve(program).takeIf { Files.isExecutable(it) }
        } else {
            executableNames(program).firstNotNullOfOrNull { findExecutableOnPath(it, path) ?: findInstalledExecutable(it) }
        } ?: throw ExecutionException(
            "$program was not found, so the bitstream cannot be built." +
                (if (program == "make" && SystemInfo.isWindows) " Install make, for example with MSYS2 or Chocolatey, or change the build command." else " Change the build command in the run configuration.")
        )
        return GeneralCommandLine(executable.toString())
            .withParameters(words.drop(1))
            .withWorkingDirectory(directory)
            .withEnvironment("PATH", path)
            .withCharset(Charsets.UTF_8)
    }

    private fun executableNames(program: String): List<String> =
        if (SystemInfo.isWindows && '.' !in program) listOf("$program.exe", "$program.cmd", "$program.bat", program) else listOf(program)

    private fun program() {
        if (stopped) return finish(1)
        val name = bitstream.name
        if (!Files.isRegularFile(bitstream)) {
            print(
                "$bitstream does not exist. " +
                    (if (buildCommand.isEmpty()) "Build it first, or set a build command in the run configuration that builds it before programming.\n"
                    else "The build command did not write it; check the bitstream in the run configuration.\n"),
                ProcessOutputTypes.STDERR,
            )
            return finish(1)
        }
        if (buildCommand.isEmpty()) {
            OpenFpgaLoaderProject.newerSource(directory, bitstream)?.let { source ->
                print(
                    "${OpenFpgaLoaderProject.relative(directory, source)} was changed after $name was built, so the FPGA gets " +
                        "the design without that change. A build command in the run configuration builds it first.\n",
                    QuartusOutput.WARNING,
                )
            }
        }
        val info = try {
            OpenFpgaLoader.info(tool)
        } catch (_: ExecutionException) {
            null
        }
        val where = if (target.destination.writesFlash) "Writing $name to the flash" else "Loading $name into the FPGA"
        print("$where" + (target.connection?.let { " on $it" }.orEmpty()) + " with ${info?.displayName ?: OpenFpgaLoader.TOOL}\n", ProcessOutputTypes.SYSTEM)
        val commandLine = ToolchainSettings.toolCommandLine(tool)
            .withWorkingDirectory(directory)
            .withParameters(target.programArguments(bitstream, info?.hasTerminalMode == true))
        val started = System.currentTimeMillis()
        start(commandLine, loader = true) { exitCode ->
            if (!stopped) conclude(exitCode, System.currentTimeMillis() - started)
            finish(exitCode)
        }
    }

    /** Starts [commandLine] and calls [then] with its exit code once it has ended. */
    private fun start(commandLine: GeneralCommandLine, loader: Boolean, then: (Int) -> Unit) {
        if (stopped) return finish(1)
        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            print((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
            return finish(1)
        }
        process = handler
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType === ProcessOutputTypes.SYSTEM) {
                    // The command line the handler prints.
                    print(event.text, outputType)
                    return
                }
                if (loader) synchronized(loaderOutput) { loaderOutput.append(event.text) }
                text(event.text, outputType)
            }

            override fun processTerminated(event: ProcessEvent) {
                flush()
                // Each process ends on a thread of its own, so the next one may be started from here.
                then(event.exitCode)
            }
        })
        if (stopped) handler.destroyProcess()
        handler.startNotify()
    }

    /**
     * Passes output on in segments that end with a line break or a carriage return. A segment ending in a
     * carriage return is a state of the progress bar, which the console then overwrites with the next one.
     */
    @Synchronized
    private fun text(text: String, outputType: Key<*>) {
        val buffer = pending.getOrPut(outputType) { StringBuilder() }
        buffer.append(text)
        while (true) {
            val end = buffer.indexOfFirst { it == '\n' || it == '\r' }
            if (end < 0) break
            segment(buffer.substring(0, end + 1), outputType)
            buffer.delete(0, end + 1)
        }
    }

    @Synchronized
    private fun flush() {
        for ((outputType, buffer) in pending) if (buffer.isNotEmpty()) segment(buffer.toString(), outputType)
        pending.clear()
    }

    private fun segment(text: String, outputType: Key<*>) {
        val type = when {
            outputType === ProcessOutputTypes.STDOUT && OpenFpgaLoaderMessages.isError(text) -> ProcessOutputTypes.STDERR
            outputType === ProcessOutputTypes.STDOUT && OpenFpgaLoaderMessages.isWarning(text) -> QuartusOutput.WARNING
            else -> outputType
        }
        print(text, type)
    }

    private fun print(text: String, outputType: Key<*>) = notifyTextAvailable(text, outputType)

    /** Says what was programmed where, or what went wrong and what to do about it. */
    private fun conclude(exitCode: Int, elapsedMs: Long) {
        val lines = synchronized(loaderOutput) { loaderOutput.split('\n', '\r') }
        val name = bitstream.name
        val on = target.connection?.let { " on $it" }.orEmpty()
        val elapsed = QuartusConsole.elapsed(elapsedMs)
        if (exitCode == 0) {
            val result = if (target.destination.writesFlash) {
                "$name is written to the flash$on in $elapsed. The FPGA loads it at every power-up."
            } else {
                "$name is loaded into the FPGA$on in $elapsed. It runs until the board loses power; write it to the flash to keep it."
            }
            print("\n$result\n", ProcessOutputTypes.STDOUT)
            return
        }
        val error = OpenFpgaLoaderMessages.firstError(lines)
        print("\nProgramming $name failed" + (error?.let { ": $it" } ?: " (exit code $exitCode).") + "\n", ProcessOutputTypes.STDERR)
        for (hint in OpenFpgaLoaderMessages.hints(lines, target)) print("  $hint\n", ProcessOutputTypes.STDOUT)
    }

    private fun finish(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        notifyProcessTerminated(exitCode)
    }

    override fun destroyProcessImpl() {
        stopped = true
        val handler = process
        if (handler != null && !handler.isProcessTerminated) handler.destroyProcess() else finish(1)
    }

    override fun detachProcessImpl() = destroyProcessImpl()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = process?.processInput
}

/** Starts programming from actions, with run configurations that stay for running again. */
object OpenFpgaLoaderRunner {
    /**
     * Programs [destination] with the configuration for it: the one selected or else the first one of this
     * project, or one set up from the project's Makefile and bitstreams. When [file] is given, the
     * configuration for that file.
     */
    fun run(project: Project, destination: FpgaDestination, file: Path? = null) {
        if (ToolchainSettings.getInstance().findOpenFpgaLoader() == null) return notifyMissing(project)
        existing(project, destination, file)?.let { return execute(project, it) }
        val base = project.basePath?.let { Path.of(it) } ?: file?.parent ?: return
        object : Task.Backgroundable(project, "Looking for the bitstream", true) {
            override fun run(indicator: ProgressIndicator) {
                val programming = OpenFpgaLoaderProject.detect(base, destination, file)
                ApplicationManager.getApplication().invokeLater({ create(project, programming) }, project.disposed)
            }
        }.queue()
    }

    private fun existing(project: Project, destination: FpgaDestination, file: Path?): RunnerAndConfigurationSettings? {
        val manager = RunManager.getInstance(project)
        val all = manager.getConfigurationSettingsList(OpenFpgaLoaderConfigurationType.getInstance())
        fun RunnerAndConfigurationSettings.fits(): Boolean {
            val configuration = configuration as? OpenFpgaLoaderRunConfiguration ?: return false
            return configuration.destination == destination && (file == null || configuration.bitstreamPath() == file.normalize())
        }
        return manager.selectedConfiguration?.takeIf { it.fits() } ?: all.firstOrNull { it.fits() }
    }

    private fun create(project: Project, programming: FpgaProgramming) {
        val manager = RunManager.getInstance(project)
        val type = OpenFpgaLoaderConfigurationType.getInstance()
        val destination = programming.target.destination
        val name = programming.bitstream?.let { "${destination.verb} ${it.name}" } ?: "${destination.verb} FPGA"
        val settings = manager.createConfiguration(name, type)
        (settings.configuration as OpenFpgaLoaderRunConfiguration).apply(programming)
        if (programming.bitstream == null) {
            // Nothing to go by: the user says what to program.
            if (!RunDialog.editConfiguration(project, settings, "Program FPGA")) return
        }
        manager.setTemporaryConfiguration(settings)
        execute(project, settings)
    }

    private fun execute(project: Project, settings: RunnerAndConfigurationSettings) {
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    /** The target the project's programming uses: the selected or first configuration's, or the Makefile's. */
    private fun projectTarget(project: Project): FpgaTarget {
        val manager = RunManager.getInstance(project)
        val configurations = listOfNotNull(manager.selectedConfiguration) +
            manager.getConfigurationSettingsList(OpenFpgaLoaderConfigurationType.getInstance())
        configurations.firstNotNullOfOrNull { it.configuration as? OpenFpgaLoaderRunConfiguration }?.let { return it.target }
        val base = project.basePath?.let { Path.of(it) } ?: return FpgaTarget()
        return OpenFpgaLoaderProject.detect(base, FpgaDestination.SRAM).target
    }

    /** Looks for the probe and the FPGA the project's programming would use, and says what it found in a balloon. */
    fun detect(project: Project) {
        val tool = ToolchainSettings.getInstance().findOpenFpgaLoader() ?: return notifyMissing(project)
        object : Task.Backgroundable(project, "Detecting the FPGA", true) {
            override fun run(indicator: ProgressIndicator) {
                val target = projectTarget(project)
                val group = NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                val detection = try {
                    OpenFpgaLoader.detect(tool, target)
                } catch (e: ExecutionException) {
                    group.createNotification("openFPGALoader failed", e.message ?: e.toString(), NotificationType.ERROR).notify(project)
                    return
                }
                val text = OpenFpgaLoader.describe(detection, target)
                if (detection.devices.isEmpty()) {
                    val hints = OpenFpgaLoaderMessages.hints(listOfNotNull(detection.error), target)
                    group.createNotification("No FPGA found", (listOf(text) + hints).joinToString("<br>"), NotificationType.WARNING)
                        .notify(project)
                } else {
                    group.createNotification("FPGA found", text, NotificationType.INFORMATION)
                        .addAction(NotificationAction.createSimpleExpiring("Load Bitstream") { run(project, FpgaDestination.SRAM) })
                        .notify(project)
                }
            }
        }.queue()
    }

    fun notifyMissing(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                "openFPGALoader was not found",
                "openFPGALoader programs the FPGA. The OSS CAD Suite has it, together with Yosys, nextpnr and Icarus Verilog " +
                    "(${OssCadSuite.DOWNLOAD_SIZE})" +
                    (if (SystemInfo.isMac) "; Homebrew installs it alone with <code>brew install openfpgaloader</code>." else ".") +
                    " Set its path in the settings when it is installed elsewhere.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Download OSS CAD Suite") { OssCadSuite.downloadInBackground(project) })
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            })
            .addAction(NotificationAction.createSimple("Documentation") { BrowserUtil.browse(OpenFpgaLoader.DOCUMENTATION_URL) })
            .notify(project)
    }
}

/** Offers to load a bitstream file, from the project view or its editor tab. */
class OpenFpgaLoaderRunConfigurationProducer : LazyRunConfigurationProducer<OpenFpgaLoaderRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = OpenFpgaLoaderConfigurationType.getInstance()

    override fun setupConfigurationFromContext(
        configuration: OpenFpgaLoaderRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = bitstream(context) ?: return false
        val base = context.project.basePath?.let { Path.of(it) } ?: file.parent
        configuration.apply(OpenFpgaLoaderProject.detect(base, FpgaDestination.SRAM, file))
        configuration.name = "${FpgaDestination.SRAM.verb} ${file.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: OpenFpgaLoaderRunConfiguration, context: ConfigurationContext): Boolean {
        val file = bitstream(context) ?: return false
        return configuration.destination == FpgaDestination.SRAM && configuration.bitstreamPath()?.let { FileUtil.pathsEqual(it.toString(), file.toString()) } == true
    }

    private fun bitstream(context: ConfigurationContext): Path? =
        context.location?.virtualFile?.takeIf { !it.isDirectory && it.isInLocalFileSystem }?.toNioPath()?.normalize()
            ?.takeIf { OpenFpgaLoader.isBitstream(it) }
}

private class OpenFpgaLoaderSettingsEditor(private val project: Project) : SettingsEditor<OpenFpgaLoaderRunConfiguration>() {
    private lateinit var bitstream: TextFieldWithBrowseButton
    private lateinit var destination: ComboBox<FpgaDestination>
    private lateinit var verify: JBCheckBox
    private lateinit var board: QuartusFilteringComboBox
    private lateinit var buildCommand: JBTextField
    private lateinit var cable: QuartusFilteringComboBox
    private lateinit var fpgaPart: JBTextField
    private lateinit var options: JBTextField

    /** What the lists say about each board and cable, shown next to its name. */
    @Volatile
    private var details: Map<String, String> = emptyMap()

    // Comments wrap at the width of the dialog: at their usual fixed width they make the form wider than the
    // dialog that edits a single configuration.
    private val panel: DialogPanel = panel {
        row("Bitstream:") {
            bitstream = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withTitle("Select Bitstream"), project,
            ).align(AlignX.FILL)
                .comment("Such as a .bit, .fs, .bin or .jed file. A relative path starts at the project directory.", WRAP).component
        }
        row("Write to:") {
            destination = comboBox(FpgaDestination.entries).component
        }
        row {
            verify = checkBox("Verify the flash after writing").component
        }.visibleIf(destination.selectedValueMatches { it?.writesFlash == true })
        row("Board:") {
            board = cell(QuartusFilteringComboBox()).align(AlignX.FILL).resizableColumn().component
            button("Detect") { detect() }
        }.rowComment(
            "openFPGALoader knows the cable, and often the FPGA, of each board it lists. Leave empty for a board it does " +
                "not know, and set the cable instead; with neither it uses an FTDI FT2232.",
            WRAP,
        )
        row("Build first:") {
            buildCommand = textField().align(AlignX.FILL)
                .comment(
                    "Run in the project directory before programming, so that the FPGA gets the latest design, such as " +
                        "<code>make build/top.fs</code>. Leave empty to program the file as it is.",
                    WRAP,
                ).component
        }
        collapsibleGroup("Advanced") {
            row("Cable:") {
                cable = cell(QuartusFilteringComboBox()).align(AlignX.FILL).comment("Overrides the cable of the board.", WRAP).component
            }
            row("FPGA part:") {
                fpgaPart = textField().align(AlignX.FILL)
                    .comment("The part with its package, such as <code>xc7a35tcsg324</code>; some FPGAs need it to write their flash.", WRAP)
                    .component
            }
            row("Options:") {
                options = textField().align(AlignX.FILL)
                    .comment(
                        "Passed to openFPGALoader, such as <code>--freq 1000000</code>, <code>--ftdi-channel 1</code> or " +
                            "<code>--cable-index 1</code>. See the <a href=\"${OpenFpgaLoader.DOCUMENTATION_URL}\">documentation</a>.",
                        WRAP,
                    ).component
            }
        }
    }

    init {
        val renderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.orEmpty())
                details[value]?.takeIf { it.isNotEmpty() }?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
        for (list in listOf(board, cable)) {
            list.renderer = renderer
            // Sized by its longest item with its details, the list would make the form wider than the dialog.
            list.setMinimumAndPreferredWidth(JBUI.scale(LIST_WIDTH))
        }
        loadLists()
    }

    /** Fills the board and cable lists from openFPGALoader, which takes a moment the first time. */
    private fun loadLists() {
        val tool = ToolchainSettings.getInstance().findOpenFpgaLoader() ?: return
        // The editor is built before its dialog is shown, so the dialog's modality is not known yet; only the lists change.
        val modality = ModalityState.any()
        ApplicationManager.getApplication().executeOnPooledThread {
            val boards = OpenFpgaLoader.boards(tool)
            val cables = OpenFpgaLoader.cables(tool)
            ApplicationManager.getApplication().invokeLater({
                if (Disposer.isDisposed(this)) return@invokeLater
                details = boards.associate { it.name to it.details } + cables.associate { it.name to it.usbId.orEmpty() }
                board.items = boards.map { it.name }
                cable.items = cables.map { it.name }
            }, modality)
        }
    }

    /** Asks openFPGALoader what is connected over the board or cable in the fields. */
    private fun detect() {
        val tool = ToolchainSettings.getInstance().findOpenFpgaLoader()
            ?: return Messages.showErrorDialog(panel, "openFPGALoader was not found.", "Detect FPGA")
        val target = FpgaTarget(board = board.text.trim(), cable = cable.text.trim(), fpgaPart = fpgaPart.text.trim())
        val detection = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<OpenFpgaLoader.Detection, ExecutionException> { OpenFpgaLoader.detect(tool, target) },
                "Detecting the FPGA", true, project,
            )
        } catch (e: ExecutionException) {
            return Messages.showErrorDialog(panel, e.message ?: e.toString(), "Detect FPGA")
        }
        val text = OpenFpgaLoader.describe(detection, target)
        if (detection.devices.isEmpty()) {
            val hints = OpenFpgaLoaderMessages.hints(listOfNotNull(detection.error), target)
            Messages.showWarningDialog(panel, (listOf(text) + hints).joinToString("\n\n"), "Detect FPGA")
        } else {
            Messages.showInfoMessage(panel, text, "Detect FPGA")
        }
    }

    override fun createEditor(): JComponent = panel

    private companion object {
        const val LIST_WIDTH = 220
        const val WRAP = MAX_LINE_LENGTH_WORD_WRAP
    }

    override fun resetEditorFrom(configuration: OpenFpgaLoaderRunConfiguration) {
        val settings = configuration.settings
        bitstream.text = settings.bitstream.orEmpty()
        destination.selectedItem = configuration.destination
        verify.isSelected = settings.verify
        board.text = settings.board.orEmpty()
        buildCommand.text = settings.buildCommand.orEmpty()
        cable.text = settings.cable.orEmpty()
        fpgaPart.text = settings.fpgaPart.orEmpty()
        options.text = settings.options.orEmpty()
    }

    override fun applyEditorTo(configuration: OpenFpgaLoaderRunConfiguration) {
        val settings = configuration.settings
        settings.bitstream = bitstream.text.trim()
        configuration.destination = destination.selectedItem as? FpgaDestination ?: FpgaDestination.SRAM
        settings.verify = verify.isSelected
        settings.board = board.text.trim()
        settings.buildCommand = buildCommand.text.trim()
        settings.cable = cable.text.trim()
        settings.fpgaPart = fpgaPart.text.trim()
        settings.options = options.text.trim()
    }
}
