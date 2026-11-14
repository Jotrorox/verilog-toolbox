package com.jotrorox.verilogtoolbox

import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

class IcarusConfigurationType : SimpleConfigurationType(
    ID, "Icarus Verilog", "Compiles a Verilog testbench with iverilog and simulates it with vvp",
    NotNullLazyValue.createValue { VerilogFileType.icon },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        IcarusRunConfiguration(project, this, "Icarus Verilog")

    override fun getOptionsClass(): Class<out BaseState> = IcarusRunConfigurationOptions::class.java

    companion object {
        const val ID = "VerilogToolboxIcarus"
    }
}

class IcarusRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var testbench by string("")
    var topModule by string("")
    var sources by string("")
    var compilerOptions by string(DEFAULT_COMPILER_OPTIONS)
    var simulatorOptions by string(DEFAULT_SIMULATOR_OPTIONS)
    var simulationArguments by string("")
    var workingDirectory by string("")

    companion object {
        /** SystemVerilog 2012, the newest standard Icarus knows; plain Verilog compiles under it too. */
        const val DEFAULT_COMPILER_OPTIONS = "-g2012"

        /** Without `-n`, `$stop` drops vvp into an interactive prompt that waits for input. */
        const val DEFAULT_SIMULATOR_OPTIONS = "-n"
    }
}

/** Compiles a testbench with `iverilog` and runs the result with `vvp`. */
class IcarusRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<IcarusRunConfigurationOptions>(project, factory, name) {

    val settings: IcarusRunConfigurationOptions
        get() = options as IcarusRunConfigurationOptions

    /** The testbench file, or null while none is configured. */
    val testbenchFile: Path?
        get() = settings.testbench?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    /** Where both tools run: the configured directory, by default the one holding the testbench. */
    val workingDirectory: Path?
        get() = settings.workingDirectory?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: testbenchFile?.parent

    /** The module that is simulated: the configured one, by default the first testbench of the file, which is read. */
    fun resolveTopModule(): String? =
        settings.topModule?.takeIf { it.isNotBlank() } ?: testbenchFile?.let { file ->
            runCatching { VerilogTestbenches.testbenches(Files.readString(file)).firstOrNull()?.name }.getOrNull()
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = IcarusSettingsEditor(project)

    override fun checkConfiguration() {
        val testbench = testbenchFile ?: throw RuntimeConfigurationError("No testbench file is selected.")
        if (!Files.isRegularFile(testbench)) throw RuntimeConfigurationError("Testbench file '$testbench' does not exist.")
        if (workingDirectory?.let { Files.isDirectory(it) } != true) {
            throw RuntimeConfigurationError("The working directory does not exist.")
        }
        val toolchain = ToolchainSettings.getInstance()
        if (toolchain.findIverilog() == null || toolchain.findVvp() == null) {
            throw RuntimeConfigurationError(
                "Icarus Verilog (iverilog and vvp) was not found. Download it, or set its path in Settings | Tools | Verilog Toolbox."
            ).apply { setQuickFix(Runnable { downloadIcarus() }) }
        }
    }

    private fun downloadIcarus() {
        try {
            val iverilog = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Path, IOException> {
                    IcarusDownloader.downloadLatest(ProgressManager.getInstance().progressIndicator)
                },
                "Downloading Icarus Verilog", true, project,
            )
            ToolchainSettings.getInstance().iverilogPath = iverilog.toString()
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Icarus Verilog Download Failed")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val toolchain = ToolchainSettings.getInstance()
        if (toolchain.findIverilog() == null || toolchain.findVvp() == null) {
            IcarusDownloader.notifyMissing(project)
            throw CantRunException.CustomProcessedCantRunException()
        }
        // The compiler reads the sources from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return IcarusRunState(this, environment)
    }
}

private class IcarusSettingsEditor(project: Project) : SettingsEditor<IcarusRunConfiguration>() {
    private lateinit var testbench: TextFieldWithBrowseButton
    private lateinit var topModule: JBTextField
    private lateinit var sources: JBTextField
    private lateinit var compilerOptions: JBTextField
    private lateinit var simulatorOptions: JBTextField
    private lateinit var simulationArguments: JBTextField
    private lateinit var workingDirectory: TextFieldWithBrowseButton

    private val panel: DialogPanel = panel {
        row("Testbench file:") {
            testbench = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withTitle("Select Testbench"), project
            ).align(AlignX.FILL).component
        }
        row("Top module:") {
            topModule = textField().align(AlignX.FILL)
                .comment("Leave empty to simulate the first module without ports in the testbench file.").component
        }
        row("Source files:") {
            sources = textField().align(AlignX.FILL)
                .comment(
                    "Compiled together with the testbench; relative paths start at the working directory. " +
                        "Leave empty to compile the other Verilog files in the testbench's directory. Modules " +
                        "from other project directories are found by file name."
                ).component
        }
        row("Compiler options:") {
            compilerOptions = textField().align(AlignX.FILL).comment("Passed to iverilog.").component
        }
        row("Simulator options:") {
            simulatorOptions = textField().align(AlignX.FILL).comment("Passed to vvp before the compiled design.").component
        }
        row("Simulation arguments:") {
            simulationArguments = textField().align(AlignX.FILL)
                .comment("Passed to the simulation, such as +plusargs and -vcd, -fst or -lxt2.").component
        }
        row("Working directory:") {
            workingDirectory = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select Working Directory"), project
            ).align(AlignX.FILL).comment("Leave empty to run in the testbench's directory.").component
        }
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(configuration: IcarusRunConfiguration) {
        val settings = configuration.settings
        testbench.text = settings.testbench.orEmpty()
        topModule.text = settings.topModule.orEmpty()
        sources.text = settings.sources.orEmpty()
        compilerOptions.text = settings.compilerOptions.orEmpty()
        simulatorOptions.text = settings.simulatorOptions.orEmpty()
        simulationArguments.text = settings.simulationArguments.orEmpty()
        workingDirectory.text = settings.workingDirectory.orEmpty()
    }

    override fun applyEditorTo(configuration: IcarusRunConfiguration) {
        val settings = configuration.settings
        settings.testbench = testbench.text.trim()
        settings.topModule = topModule.text.trim()
        settings.sources = sources.text.trim()
        settings.compilerOptions = compilerOptions.text.trim()
        settings.simulatorOptions = simulatorOptions.text.trim()
        settings.simulationArguments = simulationArguments.text.trim()
        settings.workingDirectory = workingDirectory.text.trim()
    }
}
