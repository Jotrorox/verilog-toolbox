package com.jotrorox.verilogtoolbox.verilator

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
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.jotrorox.verilogtoolbox.common.OssCadSuite
import com.jotrorox.verilogtoolbox.icarus.VerilogTestbenches
import com.jotrorox.verilogtoolbox.lsp.SlangDesign
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.verilog.VerilogFileType
import com.jotrorox.verilogtoolbox.waveform.WaveformSource
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

class VerilatorConfigurationType : SimpleConfigurationType(
    ID, "Verilator", "Verilates a Verilog or C++ testbench, builds it with CMake and simulates it",
    NotNullLazyValue.createValue { VerilogFileType.icon },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        VerilatorRunConfiguration(project, this, "Verilator")

    override fun getOptionsClass(): Class<out BaseState> = VerilatorRunConfigurationOptions::class.java

    companion object {
        const val ID = "VerilogToolboxVerilator"
    }
}

class VerilatorRunConfigurationOptions : LocatableRunConfigurationOptions() {
    /** A Verilog testbench, a module without ports, or a C++ testbench that drives the Verilated [topModule]. */
    var testbench by string("")
    var topModule by string("")

    /** Set to run every Verilog testbench below this directory; [testbench] and [topModule] are then ignored. */
    var directory by string("")

    /** A `.f` file that defines the design; empty to use the project's. */
    var fileList by string("")
    var verilatorOptions by string(DEFAULT_VERILATOR_OPTIONS)

    /** The other C++ files of a C++ testbench, relative to its directory. */
    var cppSources by string("")
    var simulationArguments by string("")
    var workingDirectory by string("")

    companion object {
        /**
         * Warnings are shown but do not stop the build, as with Icarus Verilog. A testbench with a `timescale`
         * and a design without one, as Generate Testbench writes them, is no error either.
         */
        const val DEFAULT_VERILATOR_OPTIONS = "-Wno-fatal -Wno-TIMESCALEMOD"
    }
}

/**
 * Verilates a testbench, a Verilog one with `--main --timing` or a C++ one that drives the design, builds it with
 * CMake in `build/verilator` and runs the simulation in the test runner.
 */
class VerilatorRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<VerilatorRunConfigurationOptions>(project, factory, name), WaveformSource {

    val settings: VerilatorRunConfigurationOptions
        get() = options as VerilatorRunConfigurationOptions

    override val testbenchFile: Path?
        get() = settings.testbench?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    val testbenchDirectory: Path?
        get() = settings.directory?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    /** Whether the testbench is C++, which drives the design itself, rather than a Verilog module. */
    val isCppTestbench: Boolean
        get() = testbenchDirectory == null && testbenchFile?.let { isCpp(it) } == true

    /** A copy that runs the single Verilog testbench [module] of [file]. */
    fun forTestbench(file: Path, module: String, name: String): VerilatorRunConfiguration =
        (clone() as VerilatorRunConfiguration).also {
            it.name = name
            it.settings.directory = ""
            it.settings.testbench = file.toString()
            it.settings.topModule = module
        }

    private val configuredWorkingDirectory: Path?
        get() = settings.workingDirectory?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    /** Where Verilator runs: the configured directory, by default the one holding the testbench. */
    val workingDirectory: Path?
        get() = configuredWorkingDirectory ?: testbenchFile?.parent

    private val projectDirectory: Path?
        get() = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }

    /** The CMake project of all the Verilator simulations, `build/verilator` of the project. */
    val cmakeRoot: Path?
        get() = (projectDirectory ?: testbenchFile?.parent)?.resolve("build")?.resolve("verilator")

    /**
     * The name of the simulation's target and directories: the Verilog testbench, or the C++ testbench's file
     * with its extension, as several C++ testbenches may drive one module and `counter_tb.cpp` may sit next to a
     * Verilog `counter_tb`.
     */
    val simulationName: String?
        get() = if (isCppTestbench) testbenchFile?.name else resolveTopModule() ?: testbenchFile?.nameWithoutExtension

    /** Where the simulation runs, as for Icarus Verilog: the configured directory, by default `build/sim/<name>`. */
    val simulationDirectory: Path?
        get() = configuredWorkingDirectory ?: simulationName?.let { name ->
            (projectDirectory ?: testbenchFile?.parent)?.resolve("build")?.resolve("sim")?.resolve(FileUtil.sanitizeFileName(name))
        }

    private val configuredFileList: Path?
        get() = settings.fileList?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { projectDirectory?.resolve(path) ?: Path.of(path) }.getOrNull()
        }

    /** The configured `.f` file, else the project's design file list, else null. */
    val fileList: Path?
        get() = configuredFileList ?: SlangDesign.getInstance(project).fileList

    /**
     * The module Verilator makes the model of: for a Verilog testbench the configured one, by default the first
     * testbench of the file; for a C++ testbench the configured one, by default the one whose model it includes.
     */
    override fun resolveTopModule(): String? {
        settings.topModule?.takeIf { it.isNotBlank() }?.let { return it }
        val file = testbenchFile ?: return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        return if (isCpp(file)) modelIncluded(text) else VerilogTestbenches.testbenches(text).firstOrNull()?.name
    }

    /** Verilator puts the design below a `TOP` scope; the `main` of `--main` names the testbench below it. */
    override fun waveformScope(): String? = if (isCppTestbench) "TOP" else resolveTopModule()?.let { "TOP.$it" }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = VerilatorSettingsEditor(project)

    override fun checkConfiguration() {
        val directory = testbenchDirectory
        if (directory != null) {
            if (!Files.isDirectory(directory)) throw RuntimeConfigurationError("Directory '$directory' does not exist.")
        } else {
            val testbench = testbenchFile ?: throw RuntimeConfigurationError("No testbench file is selected.")
            if (!Files.isRegularFile(testbench)) throw RuntimeConfigurationError("Testbench file '$testbench' does not exist.")
            if (isCppTestbench && resolveTopModule() == null) {
                throw RuntimeConfigurationError(
                    "Set the top module the C++ testbench drives, or include its model in the testbench as " +
                        "#include \"V<module>.h\"."
                )
            }
        }
        if (!settings.workingDirectory.isNullOrBlank() && configuredWorkingDirectory?.let { Files.isDirectory(it) } != true) {
            throw RuntimeConfigurationError("The working directory does not exist.")
        }
        configuredFileList?.let {
            if (!Files.isRegularFile(it)) throw RuntimeConfigurationError("File list '$it' does not exist.")
        }
        if (ToolchainSettings.getInstance().findVerilator() == null) {
            throw RuntimeConfigurationError(
                "Verilator was not found. Download the OSS CAD Suite, which has it, or set its path in " +
                    "Settings | Tools | Verilog Toolbox."
            ).apply { setQuickFix(Runnable { OssCadSuite.downloadInBackground(project) }) }
        }
        if (CppToolchain.find() == null) {
            throw RuntimeConfigurationError("CMake was not found. Install it, or set its path in Settings | Tools | Verilog Toolbox.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (ToolchainSettings.getInstance().findVerilator() == null) {
            OssCadSuite.notifyMissing(project, "Verilator", "Simulating a testbench with Verilator needs it.")
            throw CantRunException.CustomProcessedCantRunException()
        }
        if (CppToolchain.find() == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                .createNotification(
                    "CMake was not found",
                    "A Verilator simulation is built with CMake. Install it, or set its path in Settings | Tools | Verilog Toolbox.",
                    NotificationType.WARNING,
                )
                .notify(project)
            throw CantRunException.CustomProcessedCantRunException()
        }
        // Verilator and the compiler read the sources from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return VerilatorRunState(this)
    }

    companion object {
        val CPP_EXTENSIONS = setOf("cpp", "cc", "cxx", "c++")

        /**
         * `#include "Vcounter.h"`: the model of `counter`, whose prefix is the module's name after a `V`. Verilator's
         * own headers start with a small `v`. `Vcounter__Syms.h` and the like are parts of the model.
         */
        private val MODEL_INCLUDE = Regex("""^\s*#\s*include\s+["<]V(\w+?)(?:__\w+)?\.h[">]""", RegexOption.MULTILINE)

        fun isCpp(file: Path): Boolean = file.extension.lowercase() in CPP_EXTENSIONS

        /** The module whose Verilated model [source], a C++ file, includes; null if it includes none. */
        fun modelIncluded(source: CharSequence): String? = MODEL_INCLUDE.find(source)?.groupValues?.get(1)
    }
}

private class VerilatorSettingsEditor(project: Project) : SettingsEditor<VerilatorRunConfiguration>() {
    private lateinit var singleTestbench: JBRadioButton
    private lateinit var allTestbenches: JBRadioButton
    private lateinit var directory: TextFieldWithBrowseButton
    private lateinit var testbench: TextFieldWithBrowseButton
    private lateinit var topModule: JBTextField
    private lateinit var fileList: TextFieldWithBrowseButton
    private lateinit var verilatorOptions: JBTextField
    private lateinit var cppSources: JBTextField
    private lateinit var simulationArguments: JBTextField
    private lateinit var workingDirectory: TextFieldWithBrowseButton

    private val panel: DialogPanel = panel {
        buttonsGroup {
            row("Run:") {
                singleTestbench = radioButton("Testbench").component
                allTestbenches = radioButton("All Verilog testbenches in directory").component
            }
        }
        row("Directory:") {
            directory = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select Directory"), project
            ).align(AlignX.FILL)
                .comment("Every module without ports in the Verilog files below it is verilated and simulated on its own.")
                .component
        }.visibleIf(allTestbenches.selected)
        row("Testbench file:") {
            testbench = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withTitle("Select Testbench"), project
            ).align(AlignX.FILL)
                .comment(
                    "A Verilog testbench, which Verilator simulates with --main --timing, or a C++ testbench " +
                        "with a main() that drives the Verilated model of the top module."
                ).component
        }.visibleIf(singleTestbench.selected)
        row("Top module:") {
            topModule = textField().align(AlignX.FILL)
                .comment(
                    "Leave empty to simulate the first module without ports of a Verilog testbench, or the module " +
                        "whose model a C++ testbench includes as #include \"V&lt;module&gt;.h\"."
                ).component
        }.visibleIf(singleTestbench.selected)
        row("File list:") {
            fileList = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withExtensionFilter("f").withTitle("Select File List"), project
            ).align(AlignX.FILL)
                .comment(
                    "A .f file with the sources, include directories, defines and libraries of the design. Leave " +
                        "empty to use the project's design file list, or else the Verilog files of the testbench's " +
                        "directory, with modules of other project directories found by file name."
                ).component
        }
        row("Verilator options:") {
            verilatorOptions = textField().align(AlignX.FILL)
                .comment("Such as -Wall, --assert, --x-initial unique or -GWIDTH=8.").component
        }
        row("Other C++ files:") {
            cppSources = textField().align(AlignX.FILL)
                .comment("Only for a C++ testbench: built with it. Relative paths start at the testbench's directory.").component
        }
        row("Simulation arguments:") {
            simulationArguments = textField().align(AlignX.FILL)
                .comment("Passed to the simulation, such as +plusargs or +verilator+seed+7.").component
        }
        row("Working directory:") {
            workingDirectory = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select Working Directory"), project
            ).align(AlignX.FILL)
                .comment(
                    "Leave empty to verilate in the testbench's directory and simulate in build/sim/&lt;testbench&gt; " +
                        "of the project, where the waveform is written."
                ).component
        }
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(configuration: VerilatorRunConfiguration) {
        val settings = configuration.settings
        allTestbenches.isSelected = !settings.directory.isNullOrBlank()
        singleTestbench.isSelected = !allTestbenches.isSelected
        directory.text = settings.directory.orEmpty()
        testbench.text = settings.testbench.orEmpty()
        topModule.text = settings.topModule.orEmpty()
        fileList.text = settings.fileList.orEmpty()
        verilatorOptions.text = settings.verilatorOptions.orEmpty()
        cppSources.text = settings.cppSources.orEmpty()
        simulationArguments.text = settings.simulationArguments.orEmpty()
        workingDirectory.text = settings.workingDirectory.orEmpty()
    }

    override fun applyEditorTo(configuration: VerilatorRunConfiguration) {
        val settings = configuration.settings
        settings.directory = if (allTestbenches.isSelected) directory.text.trim() else ""
        settings.testbench = testbench.text.trim()
        settings.topModule = topModule.text.trim()
        settings.fileList = fileList.text.trim()
        settings.verilatorOptions = verilatorOptions.text.trim()
        settings.cppSources = cppSources.text.trim()
        settings.simulationArguments = simulationArguments.text.trim()
        settings.workingDirectory = workingDirectory.text.trim()
    }
}
