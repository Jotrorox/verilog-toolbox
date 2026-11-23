package com.jotrorox.verilogtoolbox

import com.intellij.execution.CantRunException
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueMatches
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

class QuartusConfigurationType : SimpleConfigurationType(
    ID, "Quartus Prime", "Compiles a Quartus Prime project and programs its device",
    NotNullLazyValue.createValue { QuartusIcons.Quartus },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        QuartusRunConfiguration(project, this, "Quartus Prime")

    override fun getOptionsClass(): Class<out BaseState> = QuartusRunConfigurationOptions::class.java

    companion object {
        const val ID = "VerilogToolboxQuartus"

        fun getInstance(): QuartusConfigurationType = ConfigurationTypeUtil.findConfigurationType(QuartusConfigurationType::class.java)
    }
}

class QuartusRunConfigurationOptions : LocatableRunConfigurationOptions() {
    /** A `.qpf` or `.qsf`; empty for the Quartus project of the IDE project. */
    var projectFile by string("")

    /** Empty for the revision that is active, or the first one of [projectFile]. */
    var revision by string("")

    /** The name of a [QuartusFlow]. */
    var flow by string(QuartusFlow.COMPILE.name)

    /** Empty for the cable that is connected. */
    var cable by string("")

    /** The name of a [QuartusImage]. */
    var image by string(QuartusImage.SOF.name)

    /** The file to program when [image] is [QuartusImage.FILE]. */
    var programmingFile by string("")
    var mode by string(QuartusProgrammer.DEFAULT_MODE)

    /** The letters `quartus_pgm -o` takes, such as `pv`; empty to choose them by the file. */
    var operation by string("")

    /** The position of the device in the JTAG chain; empty to find it. */
    var deviceIndex by string("")
    var programmerOptions by string("")
}

/** Runs a step of the compilation of a Quartus project, all of it, or the programmer. */
class QuartusRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<QuartusRunConfigurationOptions>(project, factory, name) {

    val settings: QuartusRunConfigurationOptions
        get() = options as QuartusRunConfigurationOptions

    var flow: QuartusFlow
        get() = QuartusFlow.of(settings.flow)
        set(value) {
            settings.flow = value.name
        }

    val programming: QuartusProgramming
        get() = QuartusProgramming(
            cable = settings.cable.orEmpty().trim(),
            image = QuartusImage.of(settings.image),
            file = settings.programmingFile.orEmpty().trim(),
            mode = settings.mode.orEmpty().trim(),
            operation = settings.operation.orEmpty().trim(),
            deviceIndex = settings.deviceIndex.orEmpty().trim(),
            options = settings.programmerOptions.orEmpty().trim(),
        )

    /** The configured project file, whether it exists or not; a relative path starts at the project directory. */
    private val configuredProjectFile: Path?
        get() = settings.projectFile?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { project.basePath?.let { Path.of(it).resolve(path) } ?: Path.of(path) }.getOrNull()
        }

    /** The Quartus project that is run: the configured one, else the one of the IDE project. */
    fun quartusProject(): QuartusProject? =
        configuredProjectFile?.let { QuartusProject.of(it) } ?: QuartusProjectService.getInstance(project).active

    fun revisionName(): String? {
        val quartusProject = quartusProject() ?: return null
        settings.revision?.takeIf { it.isNotBlank() }?.let { return it }
        val file = configuredProjectFile
        // A configured .qsf is its own revision; otherwise the active one, as far as it is one of this project.
        if (file != null && file.toString().endsWith(".qsf", ignoreCase = true)) return file.nameWithoutExtension
        val service = QuartusProjectService.getInstance(project)
        return service.activeRevisionName?.takeIf { service.active == quartusProject && it in quartusProject.revisions }
            ?: quartusProject.defaultRevision
    }

    /** The revision as its settings file on disk describes it. */
    fun revision(): QuartusRevision? = quartusProject()?.let { it.revision(revisionName() ?: return null) }

    override fun suggestedName(): String? = revisionName()?.let { "${flow.verb} $it" }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = QuartusSettingsEditor(project)

    override fun checkConfiguration() {
        configuredProjectFile?.let {
            if (!Files.isRegularFile(it)) throw RuntimeConfigurationError("Project file '$it' does not exist.")
            if (!QuartusProject.isProjectFile(it)) throw RuntimeConfigurationError("'${it.name}' is not a .qpf or .qsf file.")
        }
        val quartusProject = quartusProject()
            ?: throw RuntimeConfigurationError("This project has no Quartus project (.qpf). Create one with Tools | Quartus Prime | New Quartus Project.")
        val revision = revisionName() ?: quartusProject.defaultRevision
        if (!Files.isRegularFile(quartusProject.settingsFile(revision))) {
            throw RuntimeConfigurationError("Revision '$revision' has no settings file ${quartusProject.settingsFile(revision).name}.")
        }
        if (flow.programs && QuartusImage.of(settings.image) == QuartusImage.FILE && settings.programmingFile.isNullOrBlank()) {
            throw RuntimeConfigurationError("No programming file is selected.")
        }
        settings.deviceIndex?.takeIf { it.isNotBlank() }?.let {
            if ((it.trim().toIntOrNull() ?: 0) < 1) throw RuntimeConfigurationError("The device index is a position in the JTAG chain, counted from 1.")
        }
        if (Quartus.find() == null) {
            throw RuntimeConfigurationError("Quartus Prime was not found. Set where it is installed in Settings | Tools | Verilog Toolbox.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (Quartus.find() == null) {
            QuartusRunner.notifyMissing(project)
            throw CantRunException.CustomProcessedCantRunException()
        }
        // The tools read the sources and the settings from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return QuartusRunState(this)
    }
}

class QuartusRunState(private val configuration: QuartusRunConfiguration) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val installation = Quartus.find() ?: throw ExecutionException("Quartus Prime was not found.")
        val revision = configuration.revision()
            ?: throw ExecutionException("This project has no Quartus project (.qpf) to run.")
        if (!Files.isRegularFile(revision.settingsFile)) {
            throw ExecutionException("The settings file ${revision.settingsFile} does not exist.")
        }
        val flow = configuration.flow
        val steps = QuartusSteps.create(installation, revision, flow, configuration.programming)
        val header = "${flow.title} of ${revision.project.name}" +
            (if (revision.name != revision.project.name) ", revision ${revision.name}," else "") +
            " with ${installation.displayName}"
        val handler = QuartusProcessHandler(project, revision, flow, steps, header)
        ProcessTerminatedListener.attach(handler)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.addMessageFilter(QuartusConsoleFilter(project, revision.project.directory))
        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler)
    }
}

/** Starts Quartus flows from actions and the tool window, as run configurations that stay for running again. */
object QuartusRunner {
    const val DOWNLOAD_URL = "https://www.altera.com/downloads"

    /**
     * Runs [flow] on the Quartus project of [project] with the configuration called after the flow and the
     * revision, which is created the first time. One the user has changed, say to choose a cable, is kept.
     */
    fun run(project: Project, flow: QuartusFlow) {
        if (Quartus.find() == null) return notifyMissing(project)
        val service = QuartusProjectService.getInstance(project)
        val revision = service.activeRevisionName ?: return notify(
            project, "This project has no Quartus project", "Create one with Tools | Quartus Prime | New Quartus Project.",
        )
        val manager = RunManager.getInstance(project)
        val type = QuartusConfigurationType.getInstance()
        val name = "${flow.verb} $revision"
        val existing = manager.findConfigurationByTypeAndName(type, name)
            ?.takeIf { (it.configuration as? QuartusRunConfiguration)?.flow == flow }
        val settings = existing ?: manager.createConfiguration(name, type).also {
            (it.configuration as QuartusRunConfiguration).flow = flow
            manager.setTemporaryConfiguration(it)
        }
        manager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    fun notifyMissing(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                "Quartus Prime was not found",
                "It is looked for where QUARTUS_ROOTDIR points, on PATH and where it installs by default. " +
                    "Set where it is installed in the settings, or install it; the Lite Edition is free.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            })
            .addAction(NotificationAction.createSimple("Download Quartus Prime") { BrowserUtil.browse(DOWNLOAD_URL) })
            .notify(project)
    }

    private fun notify(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(title, content, NotificationType.WARNING).notify(project)
    }
}

/** Offers to compile a Quartus project from its `.qpf` or `.qsf` file. */
class QuartusRunConfigurationProducer : LazyRunConfigurationProducer<QuartusRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = QuartusConfigurationType.getInstance()

    override fun setupConfigurationFromContext(
        configuration: QuartusRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = projectFile(context) ?: return false
        val quartusProject = QuartusProject.of(file) ?: return false
        configuration.settings.projectFile = file.toString()
        configuration.flow = QuartusFlow.COMPILE
        val revision = if (file.toString().endsWith(".qsf", ignoreCase = true)) file.nameWithoutExtension else quartusProject.defaultRevision
        configuration.name = "${QuartusFlow.COMPILE.verb} $revision"
        return true
    }

    override fun isConfigurationFromContext(configuration: QuartusRunConfiguration, context: ConfigurationContext): Boolean {
        val file = projectFile(context) ?: return false
        return configuration.flow == QuartusFlow.COMPILE && FileUtil.pathsEqual(configuration.settings.projectFile, file.toString())
    }

    private fun projectFile(context: ConfigurationContext): Path? =
        context.location?.virtualFile?.takeIf { !it.isDirectory && it.isInLocalFileSystem }?.toNioPath()
            ?.takeIf { QuartusProject.isProjectFile(it) }
}

private class QuartusSettingsEditor(private val project: Project) : SettingsEditor<QuartusRunConfiguration>() {
    private lateinit var projectFile: TextFieldWithBrowseButton
    private lateinit var revision: JBTextField
    private lateinit var flow: ComboBox<QuartusFlow>
    private lateinit var cable: ComboBox<String>
    private lateinit var image: ComboBox<QuartusImage>
    private lateinit var programmingFile: TextFieldWithBrowseButton
    private lateinit var mode: ComboBox<String>
    private lateinit var operation: JBTextField
    private lateinit var deviceIndex: JBTextField
    private lateinit var programmerOptions: JBTextField

    private val panel: DialogPanel = panel {
        row("Run:") {
            flow = comboBox(QuartusFlow.entries).component
        }
        row("Project:") {
            projectFile = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withExtensionFilter("Quartus project", "qpf", "qsf")
                    .withTitle("Select Quartus Project"),
                project,
            ).align(AlignX.FILL)
                .comment(
                    "A .qpf or .qsf file. Leave empty to run the Quartus project of this project" +
                        (QuartusProjectService.getInstance(project).active?.let { ", ${it.file.name}" } ?: "") + "."
                ).component
        }
        row("Revision:") {
            revision = textField().align(AlignX.FILL)
                .comment("Leave empty for the revision chosen in the Quartus tool window, or the project's first.").component
        }
        val programs = flow.selectedValueMatches { it?.programs == true }
        group("Programming") {
            row("Cable:") {
                cable = comboBox(emptyList<String>()).align(AlignX.FILL).resizableColumn().component
                    .apply { isEditable = true }
                button("Detect") { detectCables() }
            }.rowComment("Leave empty to use the cable that is connected.")
            row("Program:") {
                image = comboBox(QuartusImage.entries).align(AlignX.FILL).component
            }
            row("File:") {
                programmingFile = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select Programming File"), project
                ).align(AlignX.FILL).comment("Such as a .jic or .rbf; a relative path starts at the Quartus project.").component
            }.visibleIf(image.selectedValueMatches { it == QuartusImage.FILE })
            collapsibleGroup("Advanced") {
                row("Mode:") {
                    mode = comboBox(QuartusProgrammer.MODES).component
                }
                row("Operation:") {
                    operation = textField().align(AlignX.FILL)
                        .comment(
                            "The letters of <code>quartus_pgm -o</code>: p program, v verify, b blank-check, r erase, " +
                                "i initialize. Leave empty for p with a .sof and pv with any other file."
                        ).component
                }
                row("Device index:") {
                    deviceIndex = textField()
                        .comment(
                            "The position of the device in the JTAG chain, counted from 1. Leave empty to find the " +
                                "project's device in a chain of several."
                        ).component
                }
                row("Programmer options:") {
                    programmerOptions = textField().align(AlignX.FILL).comment("Passed to quartus_pgm, such as --bgp.").component
                }
            }
        }.visibleIf(programs)
    }

    /** Asks the programmer for the connected cables, which takes a moment, and offers them. */
    private fun detectCables() {
        val installation = Quartus.find()
            ?: return Messages.showErrorDialog(panel, "Quartus Prime was not found.", "Detect Cables")
        val directory = project.basePath?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"))
        val cables = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<QuartusProgrammer.Cable>, ExecutionException> {
                    QuartusProgrammer.cables(installation, directory)
                },
                "Looking for Programming Cables", true, project,
            )
        } catch (e: ExecutionException) {
            return Messages.showErrorDialog(panel, e.message ?: e.toString(), "Detect Cables")
        }
        if (cables.isEmpty()) {
            return Messages.showInfoMessage(
                panel, "No programming cable is connected. Connect the board and switch it on.", "Detect Cables"
            )
        }
        val current = cable.editor.item?.toString().orEmpty()
        cable.removeAllItems()
        cables.forEach { cable.addItem(it.name) }
        cable.editor.item = current.ifBlank { cables.first().name }
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(configuration: QuartusRunConfiguration) {
        val settings = configuration.settings
        flow.selectedItem = configuration.flow
        projectFile.text = settings.projectFile.orEmpty()
        revision.text = settings.revision.orEmpty()
        cable.editor.item = settings.cable.orEmpty()
        image.selectedItem = QuartusImage.of(settings.image)
        programmingFile.text = settings.programmingFile.orEmpty()
        mode.selectedItem = settings.mode?.takeIf { it in QuartusProgrammer.MODES } ?: QuartusProgrammer.DEFAULT_MODE
        operation.text = settings.operation.orEmpty()
        deviceIndex.text = settings.deviceIndex.orEmpty()
        programmerOptions.text = settings.programmerOptions.orEmpty()
    }

    override fun applyEditorTo(configuration: QuartusRunConfiguration) {
        val settings = configuration.settings
        configuration.flow = flow.selectedItem as? QuartusFlow ?: QuartusFlow.COMPILE
        settings.projectFile = projectFile.text.trim()
        settings.revision = revision.text.trim()
        settings.cable = cable.editor.item?.toString().orEmpty().trim()
        settings.image = (image.selectedItem as? QuartusImage ?: QuartusImage.SOF).name
        settings.programmingFile = programmingFile.text.trim()
        settings.mode = mode.selectedItem as? String ?: QuartusProgrammer.DEFAULT_MODE
        settings.operation = operation.text.trim()
        settings.deviceIndex = deviceIndex.text.trim()
        settings.programmerOptions = programmerOptions.text.trim()
    }
}
