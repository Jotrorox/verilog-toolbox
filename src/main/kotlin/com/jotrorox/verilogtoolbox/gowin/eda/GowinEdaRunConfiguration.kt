package com.jotrorox.verilogtoolbox.gowin.eda

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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueMatches
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

class GowinEdaConfigurationType : SimpleConfigurationType(
    ID, "GOWIN EDA", "Builds a GOWIN EDA project and programs its device",
    NotNullLazyValue.createValue { GowinEdaIcons.Gowin },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration = GowinEdaRunConfiguration(project, this, "GOWIN EDA")

    override fun getOptionsClass(): Class<out BaseState> = GowinEdaRunConfigurationOptions::class.java

    companion object {
        const val ID = "VerilogToolboxGowinEda"

        fun getInstance(): GowinEdaConfigurationType = ConfigurationTypeUtil.findConfigurationType(GowinEdaConfigurationType::class.java)
    }
}

class GowinEdaRunConfigurationOptions : LocatableRunConfigurationOptions() {
    /** A `.gprj`; empty for the GOWIN EDA project of the IDE project. */
    var projectFile by string("")

    /** The name of a [GowinEdaFlow]. */
    var flow by string(GowinEdaFlow.BUILD.name)

    /** The name of a [GowinEdaTarget]. */
    var target by string(GowinEdaTarget.SRAM.name)
    var operation by string("")
    var verify by property(false)
    var cable by string("")
    var frequency by string("")

    /** A bitstream other than the project's; empty for the one the build writes. */
    var bitstream by string("")
    var programmerOptions by string("")
}

/** Runs a step of the flow of a GOWIN EDA project, all of it, or the programmer. */
class GowinEdaRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<GowinEdaRunConfigurationOptions>(project, factory, name) {

    val settings: GowinEdaRunConfigurationOptions get() = options as GowinEdaRunConfigurationOptions

    var flow: GowinEdaFlow
        get() = GowinEdaFlow.of(settings.flow)
        set(value) {
            settings.flow = value.name
        }

    val programming: GowinEdaProgramming
        get() = GowinEdaProgramming(
            target = GowinEdaTarget.of(settings.target),
            operation = settings.operation.orEmpty().trim(),
            verify = settings.verify,
            cable = settings.cable.orEmpty().trim(),
            frequency = settings.frequency.orEmpty().trim(),
            file = settings.bitstream.orEmpty().trim(),
            options = settings.programmerOptions.orEmpty().trim(),
        )

    /** The configured project file, whether it exists or not; a relative path starts at the project directory. */
    private val configuredProjectFile: Path?
        get() = settings.projectFile?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { project.basePath?.let { Path.of(it).resolve(path) } ?: Path.of(path) }.getOrNull()
        }

    /** The project that is run: the configured one, else the one of the IDE project, as its files on disk say. */
    fun gowinProject(): GowinEdaProject? =
        (configuredProjectFile ?: GowinEdaProjectService.getInstance(project).active)?.let { GowinEdaProject.of(it) }

    override fun suggestedName(): String? = (configuredProjectFile ?: GowinEdaProjectService.getInstance(project).active)
        ?.let { "${flow.verb} ${it.nameWithoutExtension}" }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GowinEdaSettingsEditor(project)

    override fun checkConfiguration() {
        configuredProjectFile?.let {
            if (!Files.isRegularFile(it)) throw RuntimeConfigurationError("Project file '$it' does not exist.")
            if (!GowinEdaProject.isProjectFile(it)) throw RuntimeConfigurationError("'${it.name}' is not a .gprj file.")
        }
        if (gowinProject() == null) {
            throw RuntimeConfigurationError("This project has no GOWIN EDA project (.gprj). Create one with Tools | GOWIN EDA | New GOWIN EDA Project.")
        }
        if (flow.programs && GowinEdaTarget.of(settings.target) == GowinEdaTarget.OPERATION && settings.operation.orEmpty().trim().toIntOrNull() == null) {
            throw RuntimeConfigurationError("The programmer operation is the number programmer_cli --run takes, such as 2 for SRAM Program.")
        }
        if (GowinEda.find() == null) {
            throw RuntimeConfigurationError("GOWIN EDA was not found. Set where it is installed in Settings | Tools | Verilog Toolbox.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (GowinEda.find() == null) {
            GowinEdaRunner.notifyMissing(project)
            throw CantRunException.CustomProcessedCantRunException()
        }
        // The tools read the sources, the project and the constraints from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return GowinEdaRunState(this)
    }
}

class GowinEdaRunState(private val configuration: GowinEdaRunConfiguration) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val installation = GowinEda.find() ?: throw ExecutionException("GOWIN EDA was not found.")
        val gowinProject = configuration.gowinProject() ?: throw ExecutionException("This project has no GOWIN EDA project (.gprj) to run.")
        val flow = configuration.flow
        val steps = GowinEdaSteps.create(installation, gowinProject, flow, configuration.programming)
        val handler = GowinEdaProcessHandler(project, gowinProject, flow, steps, "${flow.title} of ${gowinProject.name} with ${installation.displayName}")
        ProcessTerminatedListener.attach(handler)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.addMessageFilter(GowinEdaConsoleFilter(project, gowinProject.directory))
        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler)
    }
}

/** Starts GOWIN EDA flows from actions and the tool window, as run configurations that stay for running again. */
object GowinEdaRunner {
    const val DOWNLOAD_URL = "https://www.gowinsemi.com/en/support/download_eda/"

    /**
     * Runs [flow] on the GOWIN EDA project of [project] with the configuration called after the flow and the
     * project, which is created the first time. One the user has changed, say to program the flash, is kept.
     */
    fun run(project: Project, flow: GowinEdaFlow) {
        if (GowinEda.find() == null) return notifyMissing(project)
        val file = GowinEdaProjectService.getInstance(project).active ?: return notify(
            project, "This project has no GOWIN EDA project", "Create one with Tools | GOWIN EDA | New GOWIN EDA Project.",
        )
        val manager = RunManager.getInstance(project)
        val type = GowinEdaConfigurationType.getInstance()
        val name = "${flow.verb} ${file.nameWithoutExtension}"
        val existing = manager.findConfigurationByTypeAndName(type, name)
            ?.takeIf { (it.configuration as? GowinEdaRunConfiguration)?.flow == flow }
        val settings = existing ?: manager.createConfiguration(name, type).also {
            (it.configuration as GowinEdaRunConfiguration).flow = flow
            manager.setTemporaryConfiguration(it)
        }
        manager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    fun notifyMissing(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                "GOWIN EDA was not found",
                "It is looked for on PATH and where it installs by default. Set where it is installed in the settings, " +
                    "or install it; the Education edition needs no license.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            })
            .addAction(NotificationAction.createSimple("Download GOWIN EDA") { BrowserUtil.browse(DOWNLOAD_URL) })
            .notify(project)
    }

    private fun notify(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(title, content, NotificationType.WARNING).notify(project)
    }
}

/** Offers to build a GOWIN EDA project from its `.gprj`. */
class GowinEdaRunConfigurationProducer : LazyRunConfigurationProducer<GowinEdaRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = GowinEdaConfigurationType.getInstance()

    override fun setupConfigurationFromContext(
        configuration: GowinEdaRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = projectFile(context) ?: return false
        configuration.settings.projectFile = file.toString()
        configuration.flow = GowinEdaFlow.BUILD
        configuration.name = "${GowinEdaFlow.BUILD.verb} ${file.nameWithoutExtension}"
        return true
    }

    override fun isConfigurationFromContext(configuration: GowinEdaRunConfiguration, context: ConfigurationContext): Boolean {
        val file = projectFile(context) ?: return false
        return configuration.flow == GowinEdaFlow.BUILD && FileUtil.pathsEqual(configuration.settings.projectFile, file.toString())
    }

    private fun projectFile(context: ConfigurationContext): Path? =
        context.location?.virtualFile?.takeIf { !it.isDirectory && it.isInLocalFileSystem }?.toNioPath()
            ?.takeIf { GowinEdaProject.isProjectFile(it) }
}

private class GowinEdaSettingsEditor(private val project: Project) : SettingsEditor<GowinEdaRunConfiguration>() {
    private lateinit var flow: ComboBox<GowinEdaFlow>
    private lateinit var projectFile: TextFieldWithBrowseButton
    private lateinit var target: ComboBox<GowinEdaTarget>
    private lateinit var operation: JBTextField
    private lateinit var verify: JBCheckBox
    private lateinit var cable: ComboBox<String>
    private lateinit var frequency: ComboBox<String>
    private lateinit var bitstream: TextFieldWithBrowseButton
    private lateinit var programmerOptions: JBTextField

    private val panel: DialogPanel = panel {
        row("Run:") {
            flow = comboBox(GowinEdaFlow.entries).component
        }
        row("Project:") {
            projectFile = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withExtensionFilter("GOWIN EDA project", "gprj").withTitle("Select GOWIN EDA Project"),
                project,
            ).align(AlignX.FILL)
                .comment(
                    "A .gprj file. Leave empty to run the GOWIN EDA project of this project" +
                        (GowinEdaProjectService.getInstance(project).active?.let { ", ${it.name}" } ?: "") + "."
                ).component
        }
        group("Programming") {
            row("Program:") {
                target = comboBox(GowinEdaTarget.entries).align(AlignX.FILL).component
            }
            row("Operation:") {
                operation = textField()
                    .comment("The number <code>programmer_cli --run</code> takes, such as 0 to read the device codes or 24 to erase the SRAM.").component
            }.visibleIf(target.selectedValueMatches { it == GowinEdaTarget.OPERATION })
            row {
                verify = checkBox("Verify after programming").component
            }.visibleIf(target.selectedValueMatches { it != GowinEdaTarget.OPERATION })
            row("Cable:") {
                cable = comboBox(listOf("") + GowinEdaProgrammer.CABLES).align(AlignX.FILL).component.apply { isEditable = true }
            }.rowComment("Leave empty for the programmer's default, Gowin USB Cable(FT2CH), which the Sipeed Tang boards emulate.")
            collapsibleGroup("Advanced") {
                row("Frequency:") {
                    frequency = comboBox(listOf("") + GowinEdaProgrammer.FREQUENCIES).component.apply { isEditable = true }
                }.rowComment("The JTAG clock. Leave empty for 2.5 MHz; lower it when programming fails on a long cable.")
                row("Bitstream:") {
                    bitstream = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.singleFile().withExtensionFilter("Bitstream", "fs").withTitle("Select Bitstream"), project,
                    ).align(AlignX.FILL).comment("Leave empty for the one the build writes, impl/pnr/&lt;name&gt;.fs.").component
                }
                row("Programmer options:") {
                    programmerOptions = textField().align(AlignX.FILL).comment("Passed to programmer_cli, such as --channel 1.").component
                }
            }
        }.visibleIf(flow.selectedValueMatches { it?.programs == true })
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(configuration: GowinEdaRunConfiguration) {
        val settings = configuration.settings
        flow.selectedItem = configuration.flow
        projectFile.text = settings.projectFile.orEmpty()
        target.selectedItem = GowinEdaTarget.of(settings.target)
        operation.text = settings.operation.orEmpty()
        verify.isSelected = settings.verify
        cable.editor.item = settings.cable.orEmpty()
        frequency.editor.item = settings.frequency.orEmpty()
        bitstream.text = settings.bitstream.orEmpty()
        programmerOptions.text = settings.programmerOptions.orEmpty()
    }

    override fun applyEditorTo(configuration: GowinEdaRunConfiguration) {
        val settings = configuration.settings
        configuration.flow = flow.selectedItem as? GowinEdaFlow ?: GowinEdaFlow.BUILD
        settings.projectFile = projectFile.text.trim()
        settings.target = (target.selectedItem as? GowinEdaTarget ?: GowinEdaTarget.SRAM).name
        settings.operation = operation.text.trim()
        settings.verify = verify.isSelected
        settings.cable = cable.editor.item?.toString().orEmpty().trim()
        settings.frequency = frequency.editor.item?.toString().orEmpty().trim()
        settings.bitstream = bitstream.text.trim()
        settings.programmerOptions = programmerOptions.text.trim()
    }
}
