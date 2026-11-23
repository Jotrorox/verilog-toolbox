package com.jotrorox.verilogtoolbox

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.layout.selected
import com.intellij.util.Alarm
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** What a new Quartus project is made of, as the New Quartus Project dialog collects it. */
data class QuartusNewProjectSettings(
    val name: String,
    val directory: Path,
    val family: String,
    val device: String,
    val topLevelEntity: String,
    /** The Verilog and SystemVerilog files to list. */
    val sources: List<Path>,
    /** The clock ports to constrain in `<name>.sdc`; null for no timing constraints. */
    val clocks: List<String>?,
    val clockMegahertz: Double = QuartusNewProject.DEFAULT_CLOCK_MHZ,
)

/**
 * Creating a Quartus project: the files it starts with, and the sources and modules found in the IDE
 * project to fill them with. Everything but [scan] works on text alone.
 */
object QuartusNewProject {
    const val DEFAULT_CLOCK_MHZ = 50.0

    /** Directories of tool output, which hold copies and netlists of the design rather than its sources. */
    private val SKIPPED_DIRECTORIES = setOf("build", "db", "incremental_db", "output_files", "simulation")
    private val SOURCE_EXTENSIONS = setOf("v", "sv")
    private val VALID_NAME = Regex("[A-Za-z0-9_-]+")
    private val INVALID_CHARACTERS = Regex("[^A-Za-z0-9_-]+")

    /** Limits that keep a scan of a directory that is far larger than a project, such as a home directory, short. */
    private const val MAX_DEPTH = 8
    private const val MAX_SOURCES = 5_000
    private const val MAX_SOURCE_BYTES = 4L * 1024 * 1024

    /** `clk`, `clock`, `sclk`, `clk50`, `clock2` and the like, as one word of a port name. */
    private val CLOCK_WORD = Regex("""[a-z]?(clk|clock)(\d+[a-z]*)?""")

    /** Words that make a name about a clock rather than a clock: `clk_en`, `clk_div`, `clk_locked`. */
    private val NOT_CLOCK_WORDS =
        setOf("en", "ena", "enable", "sel", "select", "div", "cnt", "count", "rst", "reset", "lock", "locked", "ok",
            "valid", "stable", "req", "ack", "gate", "out", "o")

    /** A Verilog file and the modules it declares. */
    data class SourceFile(val path: Path, val modules: List<VerilogModuleHeaders.Module>) {
        /** A file of testbenches only, modules without ports, has nothing to put on a device. */
        val isTestbench: Boolean get() = modules.isNotEmpty() && modules.all { it.ports.isEmpty() }
    }

    /** [text] as a name Quartus takes for a project and its files: letters, digits, `_` and `-`. */
    fun sanitizeName(text: String): String =
        text.trim().replace(INVALID_CHARACTERS, "_").trim('_', '-').ifEmpty { "project" }

    fun isValidName(name: String): Boolean = VALID_NAME.matches(name)

    /**
     * Whether a port called [name] looks like a clock input: `clk`, `clock`, `sys_clk`, `clk_i`,
     * `CLOCK_50`, `MAX10_CLK1_50`, `spi_sclk`; not `clk_en` or `clken`.
     */
    fun isClockName(name: String): Boolean {
        val words = name.lowercase().split('_').filter { it.isNotEmpty() }
        return words.none { it in NOT_CLOCK_WORDS } && words.any { CLOCK_WORD.matches(it) }
    }

    /** The single-bit inputs of [module] that look like clocks, in the order of its ports. */
    fun clockPorts(module: VerilogModuleHeaders.Module): List<String> =
        module.ports.filter {
            it.direction == VerilogModuleHeaders.Direction.INPUT && it.packed.isEmpty() && it.unpacked.isEmpty() &&
                isClockName(it.name)
        }.map { it.name }

    /** The period of a clock of [megahertz] in nanoseconds, as timing constraints give it: `20.000`. */
    fun period(megahertz: Double): String = String.format(Locale.ROOT, "%.3f", 1000.0 / megahertz)

    private fun megahertz(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /**
     * Timing constraints for [clocks] of [megahertz], or a template to complete when there are none:
     * without any, the Timing Analyzer takes every clock for 1 GHz and the fitter works for that.
     */
    fun timingConstraints(name: String, topLevelEntity: String, clocks: List<String>, megahertz: Double): String =
        buildString {
            append("# Timing constraints of $name, created by Verilog Toolbox.\n\n")
            if (clocks.isEmpty()) {
                append("# No input of $topLevelEntity is named like a clock. Constrain each clock input like this,\n")
                append("# with its period in nanoseconds (${period(megahertz)} ns is ${megahertz(megahertz)} MHz):\n")
                append("# create_clock -name clk -period ${period(megahertz)} [get_ports clk]\n")
            } else {
                append("# ${megahertz(megahertz)} MHz\n")
                for (clock in clocks) append("create_clock -name $clock -period ${period(megahertz)} [get_ports $clock]\n")
            }
            append("\n# The clocks of PLLs follow from their settings; uncertainty from the device.\n")
            append("derive_pll_clocks\n")
            append("derive_clock_uncertainty\n")
        }

    /** The files of the new project, by name in its directory: `<name>.qpf`, `<name>.qsf` and maybe `<name>.sdc`. */
    fun files(settings: QuartusNewProjectSettings): Map<String, String> {
        val name = settings.name
        val listed = settings.sources.sorted().mapNotNull { file ->
            QuartusFileKind.ofFile(file)?.let { it to QuartusSettingsText.path(file, settings.directory) }
        }
        val constraints = settings.clocks?.let { "$name.sdc" }
        var qsf = "# Settings of $name, created by Verilog Toolbox.\n\n" +
            QuartusSettingsText.settingsFile(settings.family, settings.device, settings.topLevelEntity, emptyList())
        // Quartus Lite uses a single core unless told otherwise.
        qsf = QuartusSettingsText.setGlobal(qsf, "NUM_PARALLEL_PROCESSORS", "ALL")
        for ((kind, path) in listed + listOfNotNull(constraints?.let { QuartusFileKind.SDC to it })) {
            qsf = QuartusSettingsText.addGlobal(qsf, kind.assignment, path)
        }
        val files = linkedMapOf("$name.qpf" to QuartusSettingsText.projectFile(name), "$name.qsf" to qsf)
        if (constraints != null) {
            files[constraints] =
                timingConstraints(name, settings.topLevelEntity, settings.clocks, settings.clockMegahertz)
        }
        return files
    }

    /**
     * The Verilog and SystemVerilog files below [root] with their modules, in the order of their paths.
     * Hidden directories and those the tools write are left out. Reads every file: not for the UI thread.
     */
    fun scan(root: Path): List<SourceFile> {
        val files = mutableListOf<SourceFile>()

        fun visit(directory: Path, depth: Int) {
            val entries = runCatching { Files.list(directory).use { it.sorted().toList() } }.getOrDefault(emptyList())
            for (entry in entries) {
                if (files.size >= MAX_SOURCES) return
                if (entry.isRegularFile() && entry.extension.lowercase() in SOURCE_EXTENSIONS) {
                    val modules = read(entry)?.let(VerilogModuleHeaders::modules).orEmpty()
                    files += SourceFile(entry.toAbsolutePath().normalize(), modules)
                } else if (depth < MAX_DEPTH && entry.isDirectory() && !isSkipped(entry.name)) {
                    visit(entry, depth + 1)
                }
            }
        }
        if (root.isDirectory()) visit(root, 0)
        return files
    }

    private fun read(file: Path): String? {
        if (runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE) > MAX_SOURCE_BYTES) return null
        return try {
            Files.readString(file)
        } catch (_: CharacterCodingException) {
            // Comments in another encoding do not matter for finding modules.
            runCatching { String(Files.readAllBytes(file), Charsets.ISO_8859_1) }.getOrNull()
        } catch (_: IOException) {
            null
        }
    }

    private fun isSkipped(directory: String): Boolean =
        directory.startsWith(".") || directory.lowercase() in SKIPPED_DIRECTORIES

    /** The sources of [files] below [directory] to list in a new project: every file that is not a testbench's. */
    fun sources(files: List<SourceFile>, directory: Path): List<Path> {
        val base = directory.toAbsolutePath().normalize()
        return files.filter { file ->
            !file.isTestbench && file.path.startsWith(base) &&
                base.relativize(file.path).parent?.none { isSkipped(it.toString()) } != false
        }.map { it.path }
    }

    /** The modules of [files] that can be a top-level entity, those with ports, once each. */
    fun designs(files: List<SourceFile>): List<VerilogModuleHeaders.Module> =
        files.flatMap { file -> file.modules.filter { it.ports.isNotEmpty() } }.distinctBy { it.name }

    /** The top-level entity to propose: the module named like the project, else the first. */
    fun defaultTopLevelEntity(designs: List<VerilogModuleHeaders.Module>, projectName: String): String? =
        (designs.firstOrNull { it.name == projectName }
            ?: designs.firstOrNull { it.name.equals(projectName, ignoreCase = true) }
            ?: designs.firstOrNull())?.name
}

/** New Quartus Project…: creates a `.qpf` and `.qsf`, and maybe an `.sdc`, from the sources of the IDE project. */
class QuartusNewProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        // Finding Quartus may look through directories, which the UI thread must not wait for.
        QuartusActions.inBackground(project, { Quartus.find() }) { installation ->
            val dialog = NewProjectDialog(project, base, installation)
            if (dialog.showAndGet()) create(project, dialog.settings())
        }
    }

    private fun create(project: Project, settings: QuartusNewProjectSettings) {
        val files = QuartusNewProject.files(settings)
        val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(settings.directory)
            ?: return QuartusActions.notify(project, "${settings.directory} does not exist.", NotificationType.ERROR)
        val created = try {
            WriteCommandAction.writeCommandAction(project).withName("New Quartus Project")
                .compute<Map<String, VirtualFile>, IOException> {
                    files.mapValues { (name, text) ->
                        val existing = directory.findChild(name)
                        // Timing constraints that exist already were written by hand; the dialog said they are kept.
                        if (existing != null && name.endsWith(".sdc")) return@mapValues existing
                        (existing ?: directory.createChildData(this, name)).also { VfsUtil.saveText(it, text) }
                    }
                }
        } catch (e: IOException) {
            val message = "The Quartus project could not be created: ${e.message}"
            return QuartusActions.notify(project, message, NotificationType.ERROR)
        }
        val service = QuartusProjectService.getInstance(project)
        service.rescan()
        val quartusProject = QuartusProject.of(settings.directory.resolve("${settings.name}.qpf"))
            ?: return QuartusActions.notify(project, "${settings.name}.qpf could not be read.", NotificationType.ERROR)
        service.select(quartusProject)
        service.useAsDesign()
        created["${settings.name}.qsf"]?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }
}

private class NewProjectDialog(
    private val project: Project,
    private val base: Path,
    installation: QuartusInstallation?,
) : DialogWrapper(project) {
    private val chooser = QuartusDeviceChooser(installation, null, null, disposable)
    private lateinit var nameField: JBTextField
    private lateinit var directoryField: TextFieldWithBrowseButton
    private lateinit var topField: ComboBox<String>
    private lateinit var sourcesBox: JBCheckBox
    private lateinit var constraintsBox: JBCheckBox
    private lateinit var megahertzField: JBTextField
    private var directoryComment: JEditorPane? = null
    private var topComment: JEditorPane? = null
    private var sourcesComment: JEditorPane? = null
    private var constraintsComment: JEditorPane? = null

    @Volatile
    private var disposed = false

    /** What was found below the IDE project's directory; null until the scan is done. */
    private var projectFiles: List<QuartusNewProject.SourceFile>? = null

    /** What was found below a chosen directory outside the IDE project's, by that directory. */
    private var otherFiles: Pair<Path, List<QuartusNewProject.SourceFile>>? = null
    private var scanning = 0
    private var scanRequest = 0

    /** Whether the user chose the top-level entity, which then no longer follows the project name. */
    private var topChosen = false
    private var settingTop = false

    private val directoryTyping = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    init {
        Disposer.register(disposable) { disposed = true }
        title = "New Quartus Project"
        setOKButtonText("Create")
        init()
        initValidation()
        scan(base) { files ->
            projectFiles = files
            proposeTopLevelEntity()
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            nameField = textField().columns(COLUMNS_MEDIUM).align(AlignX.FILL).component
            nameField.text = QuartusNewProject.sanitizeName(base.name)
        }
        row("Directory:") {
            val cell = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select the Directory of the Quartus Project"), project,
            ).align(AlignX.FILL).comment("Creates the .qpf and .qsf in this directory.")
            directoryField = cell.component
            directoryField.text = base.toString()
            directoryComment = cell.comment
        }
        separator()
        chooser.addTo(this)
        row("Top-level entity:") {
            topField = ComboBox(CollectionComboBoxModel<String>()).apply { isEditable = true }
            topComment = cell(topField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).comment("Looking for modules…").comment
        }
        separator()
        row {
            val cell = checkBox("Add the project's Verilog sources").selected(true).comment("Looking for sources…")
            sourcesBox = cell.component
            sourcesComment = cell.comment
        }
        row {
            constraintsBox = checkBox("Create timing constraints (.sdc)").selected(true)
                .comment("Without them, the Timing Analyzer takes every clock for 1 GHz and reports it as failing.")
                .component
        }
        indent {
            row("Clock frequency (MHz):") {
                val cell = textField().columns(COLUMNS_TINY)
                    .comment("Each clock input of the top-level entity is constrained to this frequency.")
                megahertzField = cell.component
                megahertzField.text = QuartusNewProject.DEFAULT_CLOCK_MHZ.toInt().toString()
                constraintsComment = cell.comment
            }.enabledIf(constraintsBox.selected)
        }
    }.also {
        listen(nameField) {
            updateComments()
            if (!topChosen) proposeTopLevelEntity()
        }
        listen(directoryField.textField) {
            updateComments()
            directoryTyping.cancelAllRequests()
            directoryTyping.addRequest({ scanDirectory() }, TYPING_DELAY_MS, ModalityState.any())
        }
        (topField.editor.editorComponent as? JTextComponent)?.let { editor ->
            listen(editor) {
                if (!settingTop) topChosen = true
                updateComments()
            }
        }
        listen(megahertzField) { updateComments() }
        updateComments()
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    private fun listen(field: JTextComponent, changed: () -> Unit) {
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = changed()
        })
    }

    private val name: String get() = nameField.text.trim()

    private val directory: Path?
        get() = directoryField.text.trim().takeIf { it.isNotEmpty() }
            ?.let { text -> try { Path.of(text).toAbsolutePath().normalize() } catch (_: InvalidPathException) { null } }

    private val topLevelEntity: String get() = topField.editor.item?.toString().orEmpty().trim()

    private val megahertz: Double?
        get() = megahertzField.text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_MHZ }

    /** The files found for the chosen directory; null while they are being looked for. */
    private fun scannedFiles(): List<QuartusNewProject.SourceFile>? {
        val directory = directory ?: return emptyList()
        if (directory.startsWith(base)) return projectFiles
        return otherFiles?.takeIf { it.first == directory }?.second
    }

    private fun sources(): List<Path>? {
        val directory = directory ?: return emptyList()
        return scannedFiles()?.let { QuartusNewProject.sources(it, directory) }
    }

    /** Every module found, below the IDE project and below a directory outside it. */
    private fun designs(): List<VerilogModuleHeaders.Module> =
        QuartusNewProject.designs(projectFiles.orEmpty() + otherFiles?.second.orEmpty())

    private fun clocks(): List<String> =
        designs().firstOrNull { it.name == topLevelEntity }?.let(QuartusNewProject::clockPorts).orEmpty()

    /** A directory outside the IDE project has sources of its own to look for. */
    private fun scanDirectory() {
        val directory = directory ?: return
        if (directory.startsWith(base) || otherFiles?.first == directory || !Files.isDirectory(directory)) return
        val request = ++scanRequest
        scan(directory) { files ->
            if (request == scanRequest) {
                otherFiles = directory to files
                if (!topChosen) proposeTopLevelEntity()
            }
        }
    }

    private fun scan(root: Path, then: (List<QuartusNewProject.SourceFile>) -> Unit) {
        scanning++
        updateComments()
        val task = { runCatching { QuartusNewProject.scan(root) }.getOrDefault(emptyList()) }
        // Any modality: the first scan starts before the dialog shows, and only the dialog's fields are touched.
        QuartusActions.inBackground(project, task, ModalityState.any()) { files ->
            scanning--
            if (!disposed) {
                then(files)
                updateComments()
            }
        }
    }

    private fun proposeTopLevelEntity() {
        val designs = designs()
        val model = topField.model as CollectionComboBoxModel<String>
        val current = topLevelEntity
        val proposed = if (topChosen) current else QuartusNewProject.defaultTopLevelEntity(designs, name) ?: current
        settingTop = true
        try {
            model.replaceAll(designs.map { it.name })
            model.selectedItem = proposed
            topField.editor.item = proposed
        } finally {
            settingTop = false
        }
    }

    private fun updateComments() {
        val name = name.ifEmpty { "<name>" }
        directoryComment?.text = "Creates $name.qpf and $name.qsf in this directory."
        val designs = designs()
        topComment?.text = when {
            projectFiles == null -> "Looking for modules…"
            designs.isEmpty() -> "No module with ports was found in the project. Type the name of the top-level module."
            else -> "The module the design starts from, which names the device's pins. " +
                "${YosysSynthesis.plural(designs.size, "module")} with ports found in the project."
        }
        val sources = sources()
        sourcesComment?.text = when {
            sources == null -> "Looking for sources…"
            sources.isEmpty() ->
                "No Verilog sources were found below the directory. Add them later with Add to Quartus Project."
            else -> YosysSynthesis.plural(sources.size, "file") + " below the directory: " +
                sources.take(SHOWN_SOURCES).joinToString(", ") { it.name } +
                (if (sources.size > SHOWN_SOURCES) " and ${sources.size - SHOWN_SOURCES} more" else "") +
                ". Testbenches and the tools' output directories are left out."
        }
        val clocks = clocks()
        val megahertz = megahertz
        constraintsComment?.text = when {
            megahertz == null -> "The frequency of the board's clock, such as 50 or 12.5."
            clocks.isEmpty() && topLevelEntity.isNotEmpty() && projectFiles != null ->
                "No input of $topLevelEntity is named like a clock, so $name.sdc gets a template to complete."
            clocks.isEmpty() -> "Each clock input of the top-level entity is constrained to this frequency."
            else -> "A ${QuartusNewProject.period(megahertz)} ns clock on ${clocks.joinToString(", ")}."
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = name
        if (name.isEmpty()) return ValidationInfo("Enter the name of the project.", nameField)
        if (!QuartusNewProject.isValidName(name)) {
            return ValidationInfo("A Quartus project name may only hold letters, digits, _ and -.", nameField)
        }
        val directory = directory ?: return ValidationInfo("Enter the directory of the project.", directoryField.textField)
        if (!Files.isDirectory(directory)) return ValidationInfo("The directory does not exist.", directoryField.textField)
        for (extension in listOf("qpf", "qsf")) {
            if (Files.exists(directory.resolve("$name.$extension"))) {
                return ValidationInfo("$name.$extension exists in this directory already.", nameField)
            }
        }
        val device = chooser.validate()
        if (device != null && !device.warning) return device
        if (topLevelEntity.isEmpty()) return ValidationInfo("Enter the top-level entity.", topField)
        if (constraintsBox.isSelected && megahertz == null) {
            return ValidationInfo("Enter the clock frequency in MHz, such as 50.", megahertzField)
        }
        // The sources and clocks are only known once the scan is done.
        if ((sourcesBox.isSelected || constraintsBox.isSelected) && scannedFiles() == null) {
            return ValidationInfo("Still looking for the project's Verilog sources…")
        }
        if (constraintsBox.isSelected && Files.exists(directory.resolve("$name.sdc"))) {
            return ValidationInfo("$name.sdc exists already. It is kept as it is and listed in the settings.", nameField)
                .asWarning().withOKEnabled()
        }
        return device
    }

    fun settings(): QuartusNewProjectSettings {
        val directory = directory ?: base
        return QuartusNewProjectSettings(
            name = name,
            directory = directory,
            family = chooser.family,
            device = chooser.device,
            topLevelEntity = topLevelEntity,
            sources = if (sourcesBox.isSelected) sources().orEmpty() else emptyList(),
            clocks = if (constraintsBox.isSelected) clocks() else null,
            clockMegahertz = megahertz ?: QuartusNewProject.DEFAULT_CLOCK_MHZ,
        )
    }

    private companion object {
        const val TYPING_DELAY_MS = 500
        const val SHOWN_SOURCES = 3
        const val MAX_MHZ = 2000.0
    }
}
