package com.jotrorox.verilogtoolbox.sby

import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
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
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.common.OssCadSuite
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.name

class SbyConfigurationType : SimpleConfigurationType(
    ID, "SymbiYosys", "Runs the formal verification tasks of a .sby file with SymbiYosys",
    NotNullLazyValue.createValue { SbyFileType.ICON },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        SbyRunConfiguration(project, this, "SymbiYosys")

    override fun getOptionsClass(): Class<out BaseState> = SbyRunConfigurationOptions::class.java

    companion object {
        const val ID = "VerilogToolboxSby"
    }
}

class SbyRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var sbyFile by string("")

    /** The tasks to run, separated by spaces; empty for those SymbiYosys runs by default. */
    var tasks by string("")

    /** Without `-f`, SymbiYosys refuses to run a task again whose directory is still there. */
    var options by string(DEFAULT_OPTIONS)
    var workingDirectory by string("")

    companion object {
        const val DEFAULT_OPTIONS = "-f"
    }
}

/** Runs `sby` on a `.sby` file, each task a test in the test runner. */
class SbyRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<SbyRunConfigurationOptions>(project, factory, name) {

    val settings: SbyRunConfigurationOptions
        get() = options as SbyRunConfigurationOptions

    val sbyFile: Path?
        get() = settings.sbyFile?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    val taskNames: List<String>
        get() = settings.tasks.orEmpty().split(' ', '\t', ',').filter { it.isNotEmpty() }

    /**
     * Where `sby` runs: the configured directory, by default the one holding the `.sby` file. The files of
     * `[files]` are found from there, and the tasks' directories are made there.
     */
    val workingDirectory: Path?
        get() = settings.workingDirectory?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: sbyFile?.parent

    private val extraOptions: List<String>
        get() = ParametersListUtil.parse(settings.options.orEmpty())

    /** The `.sby` file as `sby` is given it: from the working directory, which keeps the tasks' directories short. */
    private val fileArgument: String?
        get() {
            val file = sbyFile ?: return null
            val directory = workingDirectory
            val inDirectory = directory != null && file.toAbsolutePath().normalize().parent == directory.toAbsolutePath().normalize()
            return if (inDirectory) file.name else file.toString()
        }

    /**
     * What the directory of each task is named after: `-d` names it, `--prefix` begins it, by default it is
     * the `.sby` file without its extension, followed by `_` and the task.
     */
    private val workdirBase: Pair<String, Boolean>?
        get() {
            val options = extraOptions
            options.zipWithNext().lastOrNull { it.first == "-d" }?.let { return it.second to true }
            options.zipWithNext().lastOrNull { it.first == "--prefix" }?.let { return it.second to false }
            return fileArgument?.removeSuffix(".sby")?.let { it to false }
        }

    /** The test of the task SymbiYosys runs in [workdir]. */
    fun taskOf(workdir: String): SbyTask {
        val name = taskName(workdir)
        return SbyTask(name ?: runName, sbyFile?.let { SbyTestLocator.url(it, name) })
    }

    /** The task SymbiYosys runs in [workdir], null for the run of a file without tasks. */
    internal fun taskName(workdir: String): String? {
        // SymbiYosys on Windows writes either separator, depending on where the path came from.
        val directory = workdir.replace('\\', '/')
        val (base, exact) = workdirBase?.let { it.first.replace('\\', '/') to it.second }
            ?: return directory.substringAfterLast('/')
        return when {
            exact -> taskNames.singleOrNull()
            directory == base -> null
            directory.startsWith(base + "_") -> directory.removePrefix(base + "_")
            else -> directory.substringAfterLast('/')
        }
    }

    /** The name of the test that stands for the whole run: the `.sby` file without its extension. */
    val runName: String
        get() = sbyFile?.name?.removeSuffix(".sby") ?: name

    /** A copy that runs [tasks] only, such as the failed ones; all, if it is empty. */
    fun forTasks(tasks: Collection<String>): SbyRunConfiguration =
        (clone() as SbyRunConfiguration).also { it.settings.tasks = tasks.joinToString(" ") }

    fun commandLine(sby: Path): GeneralCommandLine =
        ToolchainSettings.toolCommandLine(sby)
            .withParameters(extraOptions)
            .withParameters(listOfNotNull(fileArgument))
            .withParameters(taskNames)
            .withWorkDirectory(workingDirectory?.toFile())
            // SymbiYosys is Python, which holds back what it prints when it writes into a pipe.
            .withEnvironment("PYTHONUNBUFFERED", "1")

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SbySettingsEditor(project)

    override fun checkConfiguration() {
        val file = sbyFile ?: throw RuntimeConfigurationError("No .sby file is selected.")
        if (!Files.isRegularFile(file)) throw RuntimeConfigurationError("'$file' does not exist.")
        if (workingDirectory?.let { Files.isDirectory(it) } != true) {
            throw RuntimeConfigurationError("The working directory does not exist.")
        }
        val text = runCatching { Files.readString(file) }.getOrNull()
        if (text != null) {
            val known = SbyConfig.tasks(text).map { it.name }.toSet()
            // Python code may make up tasks the file does not list.
            val unknown = taskNames.filter { it !in known }
            if (unknown.isNotEmpty() && "--pycode-begin--" !in text) {
                throw RuntimeConfigurationError(
                    "${file.name} has no task ${unknown.joinToString { "'$it'" }}." +
                        if (known.isEmpty()) "" else " It has ${known.joinToString { "'$it'" }}."
                )
            }
        }
        if (ToolchainSettings.getInstance().findSby() == null) {
            throw RuntimeConfigurationError(
                "SymbiYosys (sby) was not found. Download the OSS CAD Suite, which has it with Yosys and the solvers, " +
                    "or set its path in Settings | Tools | Verilog Toolbox."
            ).apply { setQuickFix(Runnable { downloadSuite() }) }
        }
    }

    private fun downloadSuite() {
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
        if (ToolchainSettings.getInstance().findSby() == null) {
            OssCadSuite.notifyMissing(project, "SymbiYosys", "Formal verification needs sby, Yosys and a solver.")
            throw CantRunException.CustomProcessedCantRunException()
        }
        // SymbiYosys copies the sources from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        return SbyRunState(this)
    }
}

private class SbySettingsEditor(project: Project) : SettingsEditor<SbyRunConfiguration>() {
    private lateinit var sbyFile: TextFieldWithBrowseButton
    private lateinit var tasks: JBTextField
    private lateinit var options: JBTextField
    private lateinit var workingDirectory: TextFieldWithBrowseButton

    private val panel: DialogPanel = panel {
        row(".sby file:") {
            sbyFile = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withExtensionFilter("sby").withTitle("Select SymbiYosys File"), project
            ).align(AlignX.FILL).component
        }
        row("Tasks:") {
            tasks = textField().align(AlignX.FILL)
                .comment("Separated by spaces. Leave empty to run the file's default tasks, or all of them if none is marked default.")
                .component
        }
        row("Options:") {
            options = textField().align(AlignX.FILL)
                .comment(
                    "Passed to sby before the file. <code>-f</code> replaces the directories of an earlier run; " +
                        "<code>-j</code> limits how many processes run at once."
                ).component
        }
        row("Working directory:") {
            workingDirectory = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select Working Directory"), project
            ).align(AlignX.FILL)
                .comment(
                    "Leave empty to run in the directory of the .sby file. The files of [files] are read from here, " +
                        "and each task's directory with its traces is made here."
                ).component
        }
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(configuration: SbyRunConfiguration) {
        val settings = configuration.settings
        sbyFile.text = settings.sbyFile.orEmpty()
        tasks.text = settings.tasks.orEmpty()
        options.text = settings.options.orEmpty()
        workingDirectory.text = settings.workingDirectory.orEmpty()
    }

    override fun applyEditorTo(configuration: SbyRunConfiguration) {
        val settings = configuration.settings
        settings.sbyFile = sbyFile.text.trim()
        settings.tasks = tasks.text.trim()
        settings.options = options.text.trim()
        settings.workingDirectory = workingDirectory.text.trim()
    }
}
