package com.jotrorox.verilogtoolbox.icarus

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
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.jotrorox.verilogtoolbox.common.OssCadSuite
import com.jotrorox.verilogtoolbox.lsp.SlangDesign
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.verilog.VerilogFileType
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

    /** Set to run every testbench below this directory; [testbench] and [topModule] are then ignored. */
    var directory by string("")

    /** A `.f` file that defines the design; empty to use the project's, see [IcarusRunConfiguration.fileList]. */
    var fileList by string("")

    /** Only used without a file list: the sources to compile in place of those in the testbench's directory. */
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

    /** The directory whose testbenches are all run, or null when the configuration runs a single testbench. */
    val testbenchDirectory: Path?
        get() = settings.directory?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    /**
     * A copy that runs the single testbench [module] of [file]. Its [name] has to be unique among the
     * copies of a run: the compiled design is named after it.
     */
    fun forTestbench(file: Path, module: String, name: String): IcarusRunConfiguration =
        (clone() as IcarusRunConfiguration).also {
            it.name = name
            it.settings.directory = ""
            it.settings.testbench = file.toString()
            it.settings.topModule = module
        }

    /** The working directory the configuration names, if it names one. */
    private val configuredWorkingDirectory: Path?
        get() = settings.workingDirectory?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    /** Where `iverilog` runs: the configured directory, by default the one holding the testbench. */
    val workingDirectory: Path?
        get() = configuredWorkingDirectory ?: testbenchFile?.parent

    /**
     * Where `vvp` runs, so where a relative `$dumpfile` ends up, and also where `$readmemh` and `$fopen`
     * look for a relative path: `vvp` has no option to tell these apart. A configured working directory
     * is used as it is. By default it is `build/sim/<top module>` of the project, which keeps dumps out of
     * the sources and those of testbenches that all write `dump.vcd` apart. It need not exist yet;
     * the testbench file is read to find the top module.
     */
    val simulationDirectory: Path?
        get() = configuredWorkingDirectory ?: testbenchFile?.let { testbench ->
            val root = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: testbench.parent
            val name = resolveTopModule() ?: testbench.fileName.toString().substringBeforeLast('.')
            root?.resolve("build")?.resolve("sim")?.resolve(FileUtil.sanitizeFileName(name))
        }

    /** The configured file list, whether it exists or not; a relative path starts at the project directory. */
    private val configuredFileList: Path?
        get() = settings.fileList?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { project.basePath?.let { Path.of(it).resolve(path) } ?: Path.of(path) }.getOrNull()
        }

    /**
     * The `.f` file that defines the compiled design: the configured one, else the design file list
     * selected for the project, which slang-server elaborates too. Null when there is neither; the
     * design is then the configured sources, or by default the sources in the testbench's directory.
     */
    val fileList: Path?
        get() = configuredFileList ?: SlangDesign.getInstance(project).fileList

    /** The module that is simulated: the configured one, by default the first testbench of the file, which is read. */
    fun resolveTopModule(): String? =
        settings.topModule?.takeIf { it.isNotBlank() } ?: testbenchFile?.let { file ->
            runCatching { VerilogTestbenches.testbenches(Files.readString(file)).firstOrNull()?.name }.getOrNull()
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = IcarusSettingsEditor(project)

    override fun checkConfiguration() {
        val directory = testbenchDirectory
        if (directory != null) {
            // Each testbench runs in its own directory unless one is configured.
            if (!Files.isDirectory(directory)) throw RuntimeConfigurationError("Directory '$directory' does not exist.")
            if (!settings.workingDirectory.isNullOrBlank() && workingDirectory?.let { Files.isDirectory(it) } != true) {
                throw RuntimeConfigurationError("The working directory does not exist.")
            }
        } else {
            val testbench = testbenchFile ?: throw RuntimeConfigurationError("No testbench file is selected.")
            if (!Files.isRegularFile(testbench)) throw RuntimeConfigurationError("Testbench file '$testbench' does not exist.")
            if (workingDirectory?.let { Files.isDirectory(it) } != true) {
                throw RuntimeConfigurationError("The working directory does not exist.")
            }
        }
        configuredFileList?.let {
            if (!Files.isRegularFile(it)) throw RuntimeConfigurationError("File list '$it' does not exist.")
        }
        val toolchain = ToolchainSettings.getInstance()
        if (toolchain.findIverilog() == null || toolchain.findVvp() == null) {
            throw RuntimeConfigurationError(
                "Icarus Verilog (iverilog and vvp) was not found. Download the OSS CAD Suite, which has it, or set " +
                    "its path in Settings | Tools | Verilog Toolbox."
            ).apply { setQuickFix(Runnable { downloadIcarus() }) }
        }
    }

    private fun downloadIcarus() {
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Path, IOException> {
                    OssCadSuite.downloadLatest(ProgressManager.getInstance().progressIndicator)
                },
                "Downloading the OSS CAD Suite", true, project,
            )
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "OSS CAD Suite Download Failed")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val toolchain = ToolchainSettings.getInstance()
        if (toolchain.findIverilog() == null || toolchain.findVvp() == null) {
            OssCadSuite.notifyMissing(project, "Icarus Verilog", "Simulating a testbench needs iverilog and vvp.")
            throw CantRunException.CustomProcessedCantRunException()
        }
        // The compiler reads the sources from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return IcarusRunState(this)
    }
}

private class IcarusSettingsEditor(project: Project) : SettingsEditor<IcarusRunConfiguration>() {
    private lateinit var singleTestbench: JBRadioButton
    private lateinit var allTestbenches: JBRadioButton
    private lateinit var directory: TextFieldWithBrowseButton
    private lateinit var testbench: TextFieldWithBrowseButton
    private lateinit var topModule: JBTextField
    private lateinit var fileList: TextFieldWithBrowseButton
    private lateinit var sources: JBTextField
    private lateinit var compilerOptions: JBTextField
    private lateinit var simulatorOptions: JBTextField
    private lateinit var simulationArguments: JBTextField
    private lateinit var workingDirectory: TextFieldWithBrowseButton

    private val panel: DialogPanel = panel {
        buttonsGroup {
            row("Run:") {
                singleTestbench = radioButton("Testbench").component
                allTestbenches = radioButton("All testbenches in directory").component
            }
        }
        row("Directory:") {
            directory = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select Directory"), project
            ).align(AlignX.FILL)
                .comment("Every module without ports in the Verilog files below it is compiled and simulated on its own.")
                .component
        }.visibleIf(allTestbenches.selected)
        row("Testbench file:") {
            testbench = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withTitle("Select Testbench"), project
            ).align(AlignX.FILL).component
        }.visibleIf(singleTestbench.selected)
        row("Top module:") {
            topModule = textField().align(AlignX.FILL)
                .comment("Leave empty to simulate the first module without ports in the testbench file.").component
        }.visibleIf(singleTestbench.selected)
        row("File list:") {
            fileList = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withExtensionFilter("f").withTitle("Select File List"), project
            ).align(AlignX.FILL)
                .comment(
                    "A .f file with the sources, include directories, defines and libraries of the design. " +
                        "Leave empty to use the project's design file list, chosen with Use as Design File List " +
                        "on a .f file. The testbench is compiled in any case."
                ).component
        }
        row("Source files:") {
            sources = textField().align(AlignX.FILL)
                .comment(
                    "Only used when there is no file list. Compiled together with the testbench; relative " +
                        "paths start at the working directory. Leave empty to compile the other Verilog files " +
                        "in the testbench's directory. Modules from other project directories are found by file name."
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
            ).align(AlignX.FILL)
                .comment(
                    "Leave empty to compile in the testbench's directory and simulate in build/sim/&lt;top module&gt; " +
                        "of the project, where the dump is written. Relative paths in \$readmemh and \$fopen " +
                        "start where the simulation runs: set a directory here to run both tools in it."
                ).component
        }
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(configuration: IcarusRunConfiguration) {
        val settings = configuration.settings
        allTestbenches.isSelected = !settings.directory.isNullOrBlank()
        singleTestbench.isSelected = !allTestbenches.isSelected
        directory.text = settings.directory.orEmpty()
        testbench.text = settings.testbench.orEmpty()
        topModule.text = settings.topModule.orEmpty()
        fileList.text = settings.fileList.orEmpty()
        sources.text = settings.sources.orEmpty()
        compilerOptions.text = settings.compilerOptions.orEmpty()
        simulatorOptions.text = settings.simulatorOptions.orEmpty()
        simulationArguments.text = settings.simulationArguments.orEmpty()
        workingDirectory.text = settings.workingDirectory.orEmpty()
    }

    override fun applyEditorTo(configuration: IcarusRunConfiguration) {
        val settings = configuration.settings
        // An empty directory means a single testbench, so the choice needs no option of its own.
        settings.directory = if (allTestbenches.isSelected) directory.text.trim() else ""
        settings.testbench = testbench.text.trim()
        settings.topModule = topModule.text.trim()
        settings.fileList = fileList.text.trim()
        settings.sources = sources.text.trim()
        settings.compilerOptions = compilerOptions.text.trim()
        settings.simulatorOptions = simulatorOptions.text.trim()
        settings.simulationArguments = simulationArguments.text.trim()
        settings.workingDirectory = workingDirectory.text.trim()
    }
}
