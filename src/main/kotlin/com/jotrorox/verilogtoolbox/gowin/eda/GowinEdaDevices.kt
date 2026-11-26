package com.jotrorox.verilogtoolbox.gowin.eda

import com.google.gson.JsonParser
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.jotrorox.verilogtoolbox.gowin.GowinBoard
import com.jotrorox.verilogtoolbox.gowin.GowinBoards
import com.jotrorox.verilogtoolbox.quartus.QuartusFilteringComboBox
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * A part of GOWIN EDA's device catalog, `IDE/data/device/device_info.csv`. The same part number may be in it
 * more than once, once for each version of the die, such as `GW1NR-9` and `GW1NR-9C`.
 */
data class GowinEdaPart(
    val id: String,
    val partNumber: String,
    val series: String,
    /** The device with its version, such as `GW1NR-9C`. */
    val device: String,
    /** Such as `C`; empty for the first version. */
    val version: String,
    val packageName: String,
    val voltage: String,
    val speed: String,
    val ios: Int?,
    val luts: Int?,
    val registers: Int?,
) {
    fun toDevice(): GowinEdaDevice = GowinEdaDevice(device, partNumber, id)

    /** `8,640 LUTs, 6,480 registers, 71 I/Os in QFN88P, speed C6/I5`. */
    val summary: String
        get() = listOfNotNull(
            luts?.let { String.format(Locale.ROOT, "%,d LUTs", it) }, registers?.let { String.format(Locale.ROOT, "%,d registers", it) },
            ios?.let { "$it I/Os in $packageName" },
            speed.takeIf { it.isNotBlank() }?.let { "speed $it" },
        ).joinToString(", ")
}

/**
 * A pin of a package, from `IDE/data/device/<device>/<package>.json`: [index] is what a `.cst` places a port on
 * (`52`, or `B3` on a ball grid), [name] the pad (`IOR17A`), [function] what the pin also does in
 * configuration, such as `TMS` or `GCLKT_3`.
 */
data class GowinEdaPackagePin(
    val index: String,
    val name: String,
    val type: String,
    val bank: Int?,
    val function: String?,
    val differential: String?,
    val pair: String?,
) {
    val isIo: Boolean get() = type == "I/O"
}

/** What GOWIN EDA's data directory says about its devices: the catalog, the pins of each package and the I/O standards. */
object GowinEdaDevices {
    private val catalogs = ConcurrentHashMap<Path, List<GowinEdaPart>>()
    private val packages = ConcurrentHashMap<Path, List<GowinEdaPackagePin>>()
    private val standards = ConcurrentHashMap<Pair<Path, String>, Map<String, List<String>>>()

    /**
     * Boards whose GOWIN EDA device differs from what apicula's presets suggest: the Tang Primer 20K carries a
     * GW2A-18C, which apicula's `GW2A-18` preset also programs.
     */
    private val BOARD_DEVICES = mapOf("tangprimer20k" to "GW2A-18C")

    /** The parts [installation] knows, read once; empty without one. */
    fun catalog(installation: GowinEdaInstallation?): List<GowinEdaPart> {
        val file = installation?.dataDirectory?.resolve("device")?.resolve("device_info.csv") ?: return emptyList()
        catalogs[file]?.let { return it }
        val parts = runCatching { parseCatalog(Files.readString(file)) }.getOrDefault(emptyList())
        if (parts.isNotEmpty()) catalogs[file] = parts
        return parts
    }

    /** The catalog already read for [installation], without reading; null when it has not been. */
    fun cachedCatalog(installation: GowinEdaInstallation?): List<GowinEdaPart>? =
        installation?.dataDirectory?.resolve("device")?.resolve("device_info.csv")?.let { catalogs[it] }

    /** The lines of `device_info.csv`: id, part number, series, device, die, version, package, voltage, speed, …, I/Os, LUTs, registers. */
    internal fun parseCatalog(text: String): List<GowinEdaPart> = text.lineSequence().mapNotNull { line ->
        val cells = line.split(',').map { it.trim() }
        if (cells.size < 13 || cells[0].isEmpty() || cells[1].isEmpty() || cells[3].isEmpty()) return@mapNotNull null
        GowinEdaPart(
            id = cells[0], partNumber = cells[1], series = cells[2], device = cells[3], version = cells[5],
            packageName = cells[6], voltage = cells[7], speed = cells[8],
            ios = cells[10].toIntOrNull(), luts = cells[11].toIntOrNull(), registers = cells[12].toIntOrNull(),
        )
    }.toList()

    /** The part of [device] in [catalog], by its id, else by its device and part number. */
    fun part(catalog: List<GowinEdaPart>, device: GowinEdaDevice?): GowinEdaPart? {
        if (device == null) return null
        return catalog.firstOrNull { it.id == device.id }
            ?: catalog.firstOrNull { it.device.equals(device.name, true) && it.partNumber.equals(device.partNumber, true) }
    }

    /**
     * The part [board] carries, if the catalog has it: of the versions of its part number the one whose
     * letter ends the board's device (`GW1N-9C` for the Tang Nano 9K), else the newest.
     */
    fun forBoard(catalog: List<GowinEdaPart>, board: GowinBoard): GowinEdaPart? {
        val candidates = catalog.filter { it.partNumber.equals(board.device, ignoreCase = true) }
        if (candidates.size <= 1) return candidates.firstOrNull()
        BOARD_DEVICES[board.id]?.let { device -> candidates.firstOrNull { it.device == device }?.let { return it } }
        val letter = Regex("""\d([A-Z])$""").find(board.packDevice)?.groupValues?.get(1).orEmpty()
        return candidates.firstOrNull { it.version == letter } ?: candidates.maxBy { it.version }
    }

    /** The boards of [GowinBoards] whose part the catalog has, with that part. */
    fun boards(catalog: List<GowinEdaPart>): List<Pair<GowinBoard, GowinEdaPart>> =
        GowinBoards.ALL.mapNotNull { board -> forBoard(catalog, board)?.let { board to it } }

    /** The pins of the package of [device]; empty when the installation has no table for it. */
    fun packagePins(installation: GowinEdaInstallation?, device: GowinEdaDevice?): List<GowinEdaPackagePin> {
        val directory = installation?.dataDirectory?.resolve("device") ?: return emptyList()
        if (device == null) return emptyList()
        val relative = runCatching { Files.readAllLines(directory.resolve("device_package.csv")) }.getOrDefault(emptyList())
            .map { it.split(',').map(String::trim) }
            .firstOrNull { it.size >= 6 && it[0] == device.id }?.get(5) ?: return emptyList()
        val file = directory.resolve(relative)
        packages[file]?.let { return it }
        val pins = runCatching { parsePackage(Files.readString(file)) }.getOrDefault(emptyList())
        if (pins.isNotEmpty()) packages[file] = pins
        return pins
    }

    internal fun parsePackage(text: String): List<GowinEdaPackagePin> {
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return emptyList()
        val data = json.get("PIN_DATA")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val pin = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun string(key: String) = pin.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            GowinEdaPackagePin(
                index = string("INDEX") ?: return@mapNotNull null,
                name = string("NAME").orEmpty(),
                type = string("TYPE").orEmpty(),
                bank = pin.get("BANK")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull(),
                function = string("CFG"),
                differential = string("DIFF"),
                pair = string("PAIR"),
            )
        }
    }

    /**
     * The I/O standards [device] supports, by the direction of a port (`input`, `output`, `bidir`), from
     * `IDE/data/iotable/<device>/<direction>.csv`; the standards the software marks as unsupported are left out.
     */
    fun ioStandards(installation: GowinEdaInstallation?, device: String?): Map<String, List<String>> {
        val directory = installation?.dataDirectory?.resolve("iotable") ?: return emptyMap()
        if (device.isNullOrBlank()) return emptyMap()
        val key = directory to device
        standards[key]?.let { return it }
        val found = DIRECTIONS.associateWith { direction ->
            runCatching { parseIoTable(Files.readString(directory.resolve(device).resolve("$direction.csv"))) }.getOrDefault(emptyList())
        }.filterValues { it.isNotEmpty() }
        if (found.isNotEmpty()) standards[key] = found
        return found
    }

    val DIRECTIONS = listOf("input", "output", "bidir")

    /** The names of the `IO_Type` column of an I/O table, without the standards whose software column says they are not supported. */
    internal fun parseIoTable(text: String): List<String> {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split(',')?.map { it.trim() } ?: return emptyList()
        val type = header.indexOf("IO_Type").takeIf { it >= 0 } ?: return emptyList()
        return lines.drop(1).flatMap { line ->
            val cells = line.split(',')
            if ("Not Support" in line) return@flatMap emptyList()
            // `LVCMOS33/LVTTL33` is one row for two names, either of which a .cst takes.
            cells.getOrNull(type)?.replace(" ", "")?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        }.distinct()
    }
}

/**
 * The device of a GOWIN EDA project as rows of a Kotlin UI DSL panel (see [addTo]): a board, which chooses the
 * part it carries, the part number, which narrows as it is typed, and the version of the device. The catalog is
 * read from [installation] in the background.
 */
class GowinEdaDeviceChooser(
    private val installation: GowinEdaInstallation?,
    current: GowinEdaDevice?,
    parentDisposable: Disposable,
    private val onChange: () -> Unit = {},
) {
    private class BoardChoice(val board: GowinBoard?, val part: GowinEdaPart?)

    private val boardField = ComboBox(CollectionComboBoxModel(mutableListOf(BoardChoice(null, null))))
    val partField: QuartusFilteringComboBox = QuartusFilteringComboBox()
    private val deviceField = ComboBox(CollectionComboBoxModel(mutableListOf<String>()))
    private var status: JEditorPane? = null
    private var catalog: List<GowinEdaPart>? = null
    private var updating = false

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable) { disposed = true }
        partField.text = current?.partNumber.orEmpty()
        deviceField.isEditable = true
        deviceField.editor.item = current?.name.orEmpty()
        boardField.renderer = textListCellRenderer<BoardChoice?> { it?.board?.displayName ?: "None" }
        boardField.addActionListener {
            val choice = boardField.selectedItem as? BoardChoice ?: return@addActionListener
            if (updating) return@addActionListener
            choice.part?.let { select(it) }
        }
        (partField.editor.editorComponent as? JTextComponent)?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updating) partChanged()
            }
        })
        partField.addActionListener { if (!updating) partChanged() }
        deviceField.addActionListener { if (!updating) changed() }
    }

    /** The board chosen, when one is; it says what the constraints of a new project are for. */
    val board: GowinBoard? get() = (boardField.selectedItem as? BoardChoice)?.board

    /** The device as the catalog has it; null while nothing in the catalog fits what is typed. */
    val device: GowinEdaDevice?
        get() {
            val part = partField.text.trim().uppercase()
            val name = deviceField.editor.item?.toString()?.trim().orEmpty()
            val known = catalog.orEmpty().firstOrNull { it.partNumber.equals(part, true) && it.device.equals(name, true) }
            return known?.toDevice() ?: if (catalog.isNullOrEmpty() && part.isNotEmpty() && name.isNotEmpty()) {
                GowinEdaDevice(name, part, "")
            } else null
        }

    val preferredFocusedComponent: JComponent get() = partField

    fun addTo(panel: Panel) {
        with(panel) {
            row("Board:") {
                cell(boardField).columns(COLUMNS_MEDIUM).align(AlignX.FILL)
                    .comment("A development board, which chooses the part it carries. None for any other.")
            }
            row("Part number:") {
                cell(partField).columns(COLUMNS_MEDIUM).align(AlignX.FILL)
            }
            row("Device:") {
                status = cell(deviceField).columns(COLUMNS_MEDIUM).align(AlignX.FILL)
                    .comment(if (installation == null) NO_GOWIN else "Loading devices…").comment
            }
        }
        if (installation != null) load()
    }

    fun validate(): ValidationInfo? {
        if (partField.text.isBlank()) return ValidationInfo("Choose the part number.", partField)
        if (device == null) {
            return if (deviceVersions().isEmpty()) ValidationInfo("${installation?.displayName ?: "GOWIN EDA"} does not know this part.", partField)
            else ValidationInfo("Choose the version of the device.", deviceField)
        }
        return null
    }

    private fun load() {
        GowinEdaDevices.cachedCatalog(installation)?.let { return show(it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = GowinEdaDevices.catalog(installation)
            // Any modality: the dialog that asked is modal itself, and only its own fields are touched.
            ApplicationManager.getApplication().invokeLater({ if (!disposed) show(loaded) }, ModalityState.any())
        }
    }

    private fun show(loaded: List<GowinEdaPart>) {
        catalog = loaded
        updating = true
        try {
            val boards = GowinEdaDevices.boards(loaded).map { (board, part) -> BoardChoice(board, part) }
            (boardField.model as CollectionComboBoxModel<BoardChoice>).replaceAll(listOf(BoardChoice(null, null)) + boards)
            partField.items = loaded.map { it.partNumber }.distinct()
        } finally {
            updating = false
        }
        partChanged()
    }

    private fun deviceVersions(): List<GowinEdaPart> {
        val part = partField.text.trim()
        return catalog.orEmpty().filter { it.partNumber.equals(part, ignoreCase = true) }
    }

    /** Offers the versions of the typed part, keeping the chosen one when the part has it. */
    private fun partChanged() {
        val versions = deviceVersions()
        val current = deviceField.editor.item?.toString().orEmpty()
        updating = true
        try {
            val names = versions.map { it.device }.distinct()
            (deviceField.model as CollectionComboBoxModel<String>).replaceAll(names)
            // The newest version, which boards of today carry, unless one is chosen.
            val chosen = names.firstOrNull { it.equals(current, true) } ?: versions.maxByOrNull { it.version }?.device ?: current
            deviceField.selectedItem = chosen
            deviceField.editor.item = chosen
            val board = boardField.selectedItem as? BoardChoice
            if (board?.part != null && !board.part.partNumber.equals(partField.text.trim(), true)) {
                boardField.selectedIndex = 0
            }
        } finally {
            updating = false
        }
        changed()
    }

    private fun select(part: GowinEdaPart) {
        updating = true
        try {
            partField.text = part.partNumber
        } finally {
            updating = false
        }
        partChanged()
        updating = true
        try {
            deviceField.selectedItem = part.device
            deviceField.editor.item = part.device
        } finally {
            updating = false
        }
        changed()
    }

    private fun changed() {
        val loaded = catalog
        val part = loaded.orEmpty().firstOrNull { device?.id == it.id }
        status?.text = when {
            installation == null -> NO_GOWIN
            loaded == null -> "Loading devices…"
            part != null -> "${part.device}: ${part.summary}."
            partField.text.isBlank() -> "${loaded.map { it.partNumber }.distinct().size} parts. Type any part of a name to narrow the list, such as 9QN88 or GW5A."
            deviceVersions().isEmpty() -> "${installation.displayName} does not know this part."
            else -> "Choose the version of the device."
        }
        onChange()
    }

    private companion object {
        const val NO_GOWIN = "GOWIN EDA was not found, so its devices cannot be listed. Type the part number and device, such as GW1NR-LV9QN88PC6/I5 and GW1NR-9C."
    }
}

/** Device…: changes the device of the active GOWIN EDA project. */
class GowinEdaChangeDeviceAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && GowinEdaProjectService.getInstance(project).active != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = GowinEdaProjectService.getInstance(project)
        GowinEdaActions.inBackground(project, { GowinEda.find() to service.gowinProject }) { (installation, gowinProject) ->
            if (gowinProject == null) return@inBackground
            val dialog = ChangeDeviceDialog(project, installation, gowinProject)
            if (dialog.showAndGet()) change(project, gowinProject, dialog.chooser.device ?: return@inBackground)
        }
    }

    private fun change(project: Project, gowinProject: GowinEdaProject, device: GowinEdaDevice) {
        val changed = GowinEdaProjectService.getInstance(project).edit("Change Device") { GowinEdaProjectText.setDevice(it, device) }
        if (changed) GowinEdaActions.notify(project, "${gowinProject.file.fileName} now targets ${device.partNumber} (${device.name}).")
        else GowinEdaActions.notify(project, "${gowinProject.file.fileName} could not be changed.", NotificationType.ERROR)
    }

    private class ChangeDeviceDialog(project: Project, installation: GowinEdaInstallation?, gowinProject: GowinEdaProject) :
        DialogWrapper(project) {
        val chooser = GowinEdaDeviceChooser(installation, gowinProject.device, disposable)

        init {
            title = "Device of ${gowinProject.name}"
            setOKButtonText("Change")
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            chooser.addTo(this)
            row {
                comment("The constraints stay as they are. A device in another package may need other pins.")
            }
        }

        override fun getPreferredFocusedComponent(): JComponent = chooser.preferredFocusedComponent

        override fun doValidate(): ValidationInfo? = chooser.validate()
    }
}
