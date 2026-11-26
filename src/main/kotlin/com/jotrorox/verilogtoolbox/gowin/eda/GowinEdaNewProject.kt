package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
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
import com.jotrorox.verilogtoolbox.gowin.GowinBoard
import com.jotrorox.verilogtoolbox.quartus.QuartusNewProject
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent
import kotlin.io.path.extension
import kotlin.io.path.name

/** What a new GOWIN EDA project is made of, as the New GOWIN EDA Project dialog collects it. */
data class GowinEdaNewProjectSettings(
    val name: String,
    val directory: Path,
    val device: GowinEdaDevice,
    val topModule: String,
    /** The Verilog and SystemVerilog files to list. */
    val sources: List<Path>,
    /** Whether to start a `.cst` for the pins. */
    val constraints: Boolean,
    /** The clock ports to constrain in `<name>.sdc`; null for no timing constraints. */
    val clocks: List<String>?,
    val clockMegahertz: Double = GowinEdaNewProject.DEFAULT_CLOCK_MHZ,
    /** The board, which the constraints say they are for. */
    val board: GowinBoard? = null,
)

/**
 * Creating a GOWIN EDA project: the files it starts with, worked out from text alone. Finding the sources and
 * their modules is what the New Quartus Project dialog does too, see [QuartusNewProject].
 */
object GowinEdaNewProject {
    const val DEFAULT_CLOCK_MHZ = 27.0

    /** GOWIN EDA takes no `/`, `*`, `-` or spaces in the names of the files it creates. */
    private val VALID_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun sanitizeName(text: String): String =
        text.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }.ifEmpty { "project" }

    fun isValidName(name: String): Boolean = VALID_NAME.matches(name)

    /** Where the constraints go: `src` beside the project when the sources are there, as GOWIN EDA keeps them, else the project's directory. */
    fun sourceDirectory(directory: Path, sources: List<Path>): Path {
        val source = directory.resolve("src")
        return if (sources.isNotEmpty() && sources.all { it.startsWith(source) } || sources.isEmpty() && Files.isDirectory(source)) source else directory
    }

    private fun megahertz(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** Timing constraints for [clocks] of [megahertz], or a template to complete when there are none. */
    fun timingConstraints(name: String, topModule: String, clocks: List<String>, megahertz: Double): String = buildString {
        val period = QuartusNewProject.period(megahertz)
        val half = String.format(Locale.ROOT, "%.3f", 500.0 / megahertz)
        append("// Timing constraints of $name, created by Verilog Toolbox.\n\n")
        if (clocks.isEmpty()) {
            append("// No input of $topModule is named like a clock. Constrain each clock input like this,\n")
            append("// with its period in nanoseconds ($period ns is ${megahertz(megahertz)} MHz):\n")
            append("// create_clock -name clk -period $period -waveform {0 $half} [get_ports {clk}]\n")
        } else {
            append("// ${megahertz(megahertz)} MHz\n")
            for (clock in clocks) append("create_clock -name $clock -period $period -waveform {0 $half} [get_ports {$clock}]\n")
        }
    }

    /** The first lines of a new `.cst`. */
    fun physicalConstraints(name: String, device: GowinEdaDevice, board: GowinBoard?): String =
        "// Physical constraints of $name for ${board?.displayName ?: device.partNumber}, created by Verilog Toolbox.\n" +
            "// Assign pins in the Pins tab of the GOWIN EDA tool window, or write them here:\n" +
            "// IO_LOC \"led[0]\" 10;\n" +
            "// IO_PORT \"led[0]\" IO_TYPE=LVCMOS33 PULL_MODE=UP DRIVE=8;\n"

    /** The files of the new project, by their path relative to its directory. */
    fun files(settings: GowinEdaNewProjectSettings): Map<String, String> {
        val name = settings.name
        val directory = settings.directory
        val constraintDirectory = sourceDirectory(directory, settings.sources)
        val listed = settings.sources.sorted().mapNotNull { file -> GowinEdaFileKind.ofFile(file)?.let { it to GowinEdaProjectText.path(file, directory) } }
        val cst = constraintDirectory.resolve("$name.cst").takeIf { settings.constraints }
        val sdc = constraintDirectory.resolve("$name.sdc").takeIf { settings.clocks != null }
        val extra = listOfNotNull(
            cst?.let { GowinEdaFileKind.CST to GowinEdaProjectText.path(it, directory) },
            sdc?.let { GowinEdaFileKind.SDC to GowinEdaProjectText.path(it, directory) },
        )
        var options = GowinEdaOptionsText.setString(null, "TopModule", settings.topModule)
        // GOWIN EDA reads Verilog 2001 unless told otherwise, which a SystemVerilog source would not get through.
        if (settings.sources.any { it.extension.lowercase() == "sv" }) options = GowinEdaOptionsText.setString(options, "Verilog_Standard", "Vlg_Std_Sysv2017")
        val files = linkedMapOf(
            "$name.${GowinEdaProject.EXTENSION}" to GowinEdaProjectText.projectFile(settings.device, listed + extra),
            "${GowinEdaProject.IMPL}/${GowinEdaProject.OPTIONS_FILE}" to options,
        )
        cst?.let { files[GowinEdaProjectText.path(it, directory)] = physicalConstraints(name, settings.device, settings.board) }
        sdc?.let { files[GowinEdaProjectText.path(it, directory)] = timingConstraints(name, settings.topModule, settings.clocks.orEmpty(), settings.clockMegahertz) }
        return files
    }
}

/** New GOWIN EDA Project…: creates a `.gprj` with the sources of the IDE project, and maybe a `.cst` and an `.sdc`. */
class GowinEdaNewProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        GowinEdaActions.inBackground(project, { GowinEda.find() }) { installation ->
            val dialog = NewProjectDialog(project, base, installation)
            if (dialog.showAndGet()) create(project, dialog.settings() ?: return@inBackground)
        }
    }

    private fun create(project: Project, settings: GowinEdaNewProjectSettings) {
        val files = GowinEdaNewProject.files(settings)
        val created = try {
            WriteCommandAction.writeCommandAction(project).withName("New GOWIN EDA Project").compute<Map<String, VirtualFile>, IOException> {
                files.mapValues { (path, text) ->
                    val target = settings.directory.resolve(path)
                    val directory = VfsUtil.createDirectories(target.parent.toString())
                    val existing = directory.findChild(target.name)
                    // Constraints that exist already were written by hand; the dialog said they are kept.
                    if (existing != null && target.extension.lowercase() in setOf("cst", "sdc")) return@mapValues existing
                    (existing ?: directory.createChildData(this, target.name)).also { VfsUtil.saveText(it, text) }
                }
            }
        } catch (e: IOException) {
            return GowinEdaActions.notify(project, "The GOWIN EDA project could not be created: ${e.message}", NotificationType.ERROR)
        }
        val service = GowinEdaProjectService.getInstance(project)
        val file = settings.directory.resolve("${settings.name}.${GowinEdaProject.EXTENSION}").toAbsolutePath().normalize()
        service.rescan()
        service.select(file)
        service.useAsDesign()
        GowinEdaToolWindow.show(project, GowinEdaToolWindow.OVERVIEW)
        created["${settings.name}.${GowinEdaProject.EXTENSION}"]?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
}

private class NewProjectDialog(private val project: Project, private val base: Path, installation: GowinEdaInstallation?) : DialogWrapper(project) {
    private val chooser = GowinEdaDeviceChooser(installation, null, disposable) { boardChanged() }
    private lateinit var nameField: JBTextField
    private lateinit var directoryField: TextFieldWithBrowseButton
    private lateinit var topField: ComboBox<String>
    private lateinit var sourcesBox: JBCheckBox
    private lateinit var pinsBox: JBCheckBox
    private lateinit var timingBox: JBCheckBox
    private lateinit var megahertzField: JBTextField
    private var topComment: JEditorPane? = null
    private var sourcesComment: JEditorPane? = null
    private var timingComment: JEditorPane? = null

    @Volatile
    private var disposed = false

    /** What was found below the IDE project's directory; null until the scan is done. */
    private var projectFiles: List<QuartusNewProject.SourceFile>? = null
    private var topChosen = false
    private var settingTop = false
    private var megahertzTyped = false
    private var lastBoard: GowinBoard? = null

    init {
        Disposer.register(disposable) { disposed = true }
        title = "New GOWIN EDA Project"
        setOKButtonText("Create")
        init()
        initValidation()
        GowinEdaActions.inBackground(project, { runCatching { QuartusNewProject.scan(base) }.getOrDefault(emptyList()) }, ModalityState.any()) { files ->
            if (disposed) return@inBackground
            projectFiles = files
            proposeTopModule()
            updateComments()
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            nameField = textField().columns(COLUMNS_MEDIUM).align(AlignX.FILL).component
            nameField.text = GowinEdaNewProject.sanitizeName(base.name)
        }
        row("Directory:") {
            directoryField = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleDir().withTitle("Select the Directory of the GOWIN EDA Project"), project,
            ).align(AlignX.FILL).comment("Creates the .gprj in this directory, and GOWIN EDA its impl directory beside it.").component
            directoryField.text = base.toString()
        }
        separator()
        chooser.addTo(this)
        row("Top module:") {
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
            pinsBox = checkBox("Create pin constraints (.cst)").selected(true)
                .comment("Assign the pins in the Pins tab of the GOWIN EDA tool window afterwards.").component
        }
        row {
            timingBox = checkBox("Create timing constraints (.sdc)").selected(true)
                .comment("Without them, timing is checked against a default clock rather than the board's.").component
        }
        indent {
            row("Clock frequency (MHz):") {
                val cell = textField().columns(COLUMNS_TINY).comment("Each clock input of the top module is constrained to this frequency.")
                megahertzField = cell.component
                megahertzField.text = GowinEdaNewProject.DEFAULT_CLOCK_MHZ.toInt().toString()
                timingComment = cell.comment
            }.enabledIf(timingBox.selected)
        }
    }.also {
        listen(nameField) {
            updateComments()
            if (!topChosen) proposeTopModule()
        }
        listen(directoryField.textField) { updateComments() }
        (topField.editor.editorComponent as? JTextComponent)?.let { editor ->
            listen(editor) {
                if (!settingTop) topChosen = true
                updateComments()
            }
        }
        listen(megahertzField) {
            if (!settingTop) megahertzTyped = true
            updateComments()
        }
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

    private val topModule: String get() = topField.editor.item?.toString().orEmpty().trim()

    private val megahertz: Double?
        get() = megahertzField.text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_MHZ }

    private fun sources(): List<Path>? {
        val directory = directory ?: return emptyList()
        return projectFiles?.let { QuartusNewProject.sources(it, directory) }
    }

    private fun designs(): List<VerilogModuleHeaders.Module> = QuartusNewProject.designs(projectFiles.orEmpty())

    private fun clocks(): List<String> = designs().firstOrNull { it.name == topModule }?.let(QuartusNewProject::clockPorts).orEmpty()

    /** A board's oscillator is the clock to constrain, unless a frequency was typed. */
    private fun boardChanged() {
        val board = chooser.board
        if (board == lastBoard) return
        lastBoard = board
        if (board != null && !megahertzTyped && ::megahertzField.isInitialized) {
            settingTop = true
            try {
                megahertzField.text = board.clockMhz.toString()
            } finally {
                settingTop = false
            }
        }
    }

    private fun proposeTopModule() {
        val designs = designs()
        val model = topField.model as CollectionComboBoxModel<String>
        val current = topModule
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
        val designs = designs()
        topComment?.text = when {
            projectFiles == null -> "Looking for modules…"
            designs.isEmpty() -> "No module with ports was found in the project. Type the name of the top module."
            else -> "The module the design starts from, whose ports go to the pins. ${YosysSynthesis.plural(designs.size, "module")} with ports found."
        }
        val sources = sources()
        sourcesComment?.text = when {
            sources == null -> "Looking for sources…"
            sources.isEmpty() -> "No Verilog sources were found below the directory. Add them later with Add to GOWIN EDA Project."
            else -> YosysSynthesis.plural(sources.size, "file") + " below the directory: " +
                sources.take(SHOWN_SOURCES).joinToString(", ") { it.name } +
                (if (sources.size > SHOWN_SOURCES) " and ${sources.size - SHOWN_SOURCES} more" else "") +
                ". Testbenches and the tools' output directories are left out."
        }
        val clocks = clocks()
        val megahertz = megahertz
        timingComment?.text = when {
            megahertz == null -> "The frequency of the board's clock, such as 27 or 50."
            clocks.isEmpty() && topModule.isNotEmpty() && projectFiles != null ->
                "No input of $topModule is named like a clock, so the .sdc gets a template to complete."
            clocks.isEmpty() -> "Each clock input of the top module is constrained to this frequency."
            else -> "A ${QuartusNewProject.period(megahertz)} ns clock on ${clocks.joinToString(", ")}."
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = name
        if (name.isEmpty()) return ValidationInfo("Enter the name of the project.", nameField)
        if (!GowinEdaNewProject.isValidName(name)) {
            return ValidationInfo("A GOWIN EDA project name may only hold letters, digits and _, and does not start with a digit.", nameField)
        }
        val directory = directory ?: return ValidationInfo("Enter the directory of the project.", directoryField.textField)
        if (!Files.isDirectory(directory)) return ValidationInfo("The directory does not exist.", directoryField.textField)
        if (Files.exists(directory.resolve("$name.${GowinEdaProject.EXTENSION}"))) {
            return ValidationInfo("$name.${GowinEdaProject.EXTENSION} exists in this directory already.", nameField)
        }
        chooser.validate()?.let { return it }
        if (topModule.isEmpty()) return ValidationInfo("Enter the top module.", topField)
        if (timingBox.isSelected && megahertz == null) return ValidationInfo("Enter the clock frequency in MHz, such as 27.", megahertzField)
        if ((sourcesBox.isSelected || timingBox.isSelected) && projectFiles == null) return ValidationInfo("Still looking for the project's Verilog sources…")
        val constraints = GowinEdaNewProject.sourceDirectory(directory, if (sourcesBox.isSelected) sources().orEmpty() else emptyList())
        val kept = listOfNotNull("$name.cst".takeIf { pinsBox.isSelected }, "$name.sdc".takeIf { timingBox.isSelected })
            .filter { Files.exists(constraints.resolve(it)) }
        if (kept.isNotEmpty()) {
            return ValidationInfo("${kept.joinToString(" and ")} exist${if (kept.size == 1) "s" else ""} already: kept as it is and listed in the project.", nameField)
                .asWarning().withOKEnabled()
        }
        return null
    }

    fun settings(): GowinEdaNewProjectSettings? {
        val device = chooser.device ?: return null
        return GowinEdaNewProjectSettings(
            name = name,
            directory = directory ?: base,
            device = device,
            topModule = topModule,
            sources = if (sourcesBox.isSelected) sources().orEmpty() else emptyList(),
            constraints = pinsBox.isSelected,
            clocks = if (timingBox.isSelected) clocks() else null,
            clockMegahertz = megahertz ?: GowinEdaNewProject.DEFAULT_CLOCK_MHZ,
            board = chooser.board,
        )
    }

    private companion object {
        const val SHOWN_SOURCES = 3
        const val MAX_MHZ = 2000.0
    }
}
