package com.jotrorox.verilogtoolbox

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableColumn
import javax.swing.table.TableRowSorter

/**
 * The Pins tab: every top-level port, bit by bit, with its pin and I/O standard, edited in place, so that pins
 * are assigned without Quartus's Pin Planner. All changes go through [QuartusProjectService.edit] into the
 * settings file; the table only ever shows what that file says, read again whenever it changes.
 */
class QuartusPinsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private enum class Column(val title: String, val width: Int) {
        // Widths for a tool window at the side, where "not assigned" and a pin name still have to fit.
        NODE("Node", 125),
        DIRECTION("Dir.", 62),
        LOCATION("Location", 105),
        IO_STANDARD("I/O Standard", 125),
        FITTER("Fitter", 85),
    }

    private enum class State { LOADING, NO_PROJECT, READY }

    /** What one load found; made off the UI thread and never changed after. */
    private class Snapshot(
        val state: State,
        val rows: List<QuartusPins.Row> = emptyList(),
        val settingsFile: Path? = null,
        val device: String? = null,
        val entity: String? = null,
        val moduleFound: Boolean = false,
        /** Null without a `.pin` file, or with one of another device, whose pins would mislead. */
        val report: QuartusPins.PinReport? = null,
        val standards: List<String> = QuartusPins.COMMON_IO_STANDARDS,
    )

    private class Source(val stamp: Long, val modules: List<VerilogModuleHeaders.Module>)

    private inner class PinTableModel : AbstractTableModel() {
        var rows: List<QuartusPins.Row> = emptyList()
            private set

        fun update(rows: List<QuartusPins.Row>) {
            this.rows = rows
            fireTableDataChanged()
        }

        /** Changes one row only, which keeps the selection that replacing all of them would clear. */
        fun replace(index: Int, row: QuartusPins.Row) {
            if (index !in rows.indices) return
            rows = rows.toMutableList().also { it[index] = row }
            fireTableRowsUpdated(index, index)
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = Column.entries.size
        override fun getColumnName(column: Int): String = Column.entries[column].title
        override fun getColumnClass(column: Int): Class<*> = String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (Column.entries[columnIndex]) {
                Column.NODE -> row.name
                Column.DIRECTION -> QuartusPins.directionLabel(row.node.direction)
                Column.LOCATION -> row.location.orEmpty()
                Column.IO_STANDARD -> row.standard?.value.orEmpty()
                Column.FITTER -> row.fitterLocation.orEmpty()
            }
        }

        /** A vector of unknown width takes an I/O standard for all its bits, but a pin only per bit. */
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = when (Column.entries[columnIndex]) {
            Column.LOCATION -> rows.getOrNull(rowIndex)?.node?.isWhole == false
            Column.IO_STANDARD -> true
            else -> false
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows.getOrNull(rowIndex) ?: return
            assign(rowIndex, row, Column.entries[columnIndex], value?.toString().orEmpty())
        }
    }

    private val model = PinTableModel()
    private val sorter = TableRowSorter(model)

    /** A load that came in while a cell was edited, shown once the editing ends. */
    private var pending: Snapshot? = null

    private val table: JBTable = object : JBTable(model), UiDataProvider {
        override fun removeEditor() {
            super.removeEditor()
            pending?.let {
                pending = null
                show(it)
            }
        }

        override fun uiDataSnapshot(sink: DataSink) {
            // The text of a cell editor is copied as text, not as rows.
            if (!isEditing) sink[PlatformDataKeys.COPY_PROVIDER] = copyProvider
        }
    }

    private val scrollPane = ScrollPaneFactory.createScrollPane(table, true)
    private val search = SearchTextField(false)
    private val status = SimpleColoredComponent()
    private val fitterColumn: TableColumn
    private var fitterColumnShown = true
    private var onlyUnassigned = false

    private var snapshot = Snapshot(State.LOADING)

    private val loader = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
    private val generation = AtomicInteger()
    private val sources = ConcurrentHashMap<Path, Source>()

    @Volatile
    private var disposed = false

    private val copyProvider = object : CopyProvider {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun performCopy(dataContext: DataContext) = copySelection()
        override fun isCopyEnabled(dataContext: DataContext): Boolean = table.selectedRowCount > 0
        override fun isCopyVisible(dataContext: DataContext): Boolean = true
    }

    init {
        Disposer.register(parent, Disposable { disposed = true })

        table.rowSorter = sorter
        sorter.setComparator(Column.NODE.ordinal, NaturalComparator.INSTANCE)
        sorter.setComparator(Column.LOCATION.ordinal, NaturalComparator.INSTANCE)
        sorter.setComparator(Column.FITTER.ordinal, NaturalComparator.INSTANCE)
        table.tableHeader.reorderingAllowed = false
        table.putClientProperty("terminateEditOnFocusLost", true)
        val renderer = CellRenderer()
        for (column in Column.entries) {
            table.columnModel.getColumn(column.ordinal).apply {
                preferredWidth = JBUI.scale(column.width)
                cellRenderer = renderer
            }
        }
        table.columnModel.getColumn(Column.LOCATION.ordinal).cellEditor = ChoiceEditor(
            choices = { row ->
                val report = snapshot.report
                if (report == null) emptyList() else QuartusPins.freePins(report, snapshot.rows, row.name).map { it.location }
            },
            isValid = { text ->
                text.isEmpty() || QuartusSettingsText.pinLocation(text)?.let(QuartusPins::isPinLocation) == true
            },
            renderer = LocationRenderer(),
        )
        table.columnModel.getColumn(Column.IO_STANDARD.ordinal).cellEditor = ChoiceEditor(
            choices = { snapshot.standards },
            isValid = { true },
            renderer = StandardRenderer(),
        )
        fitterColumn = table.columnModel.getColumn(Column.FITTER.ordinal)

        search.textEditor.emptyText.text = "Filter by node or location"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateFilter()
        })

        val clear = ClearLocationAction()
        clear.registerCustomShortcutSet(CommonShortcuts.getDelete(), table)
        val actions = DefaultActionGroup(
            RefreshAction(), Separator.getInstance(),
            OnlyUnassignedAction(), clear, Separator.getInstance(),
            OpenSettingsAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true)
        toolbar.targetComponent = table
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            add(toolbar.component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }
        status.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(3, 8),
        )
        add(top, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        updateStatus()
        updateEmptyText()

        val connection = project.messageBus.connect(parent)
        connection.subscribe(QuartusProjectListener.TOPIC, QuartusProjectListener { reload() })
        // The ports come from the Verilog sources, which the Quartus project service does not watch.
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.substringAfterLast('.', "").lowercase() in VERILOG_EXTENSIONS }) reload()
            }
        })
        reload()
    }

    /** Reads the pins again on a pooled thread and shows them, unless a newer read has started in the meantime. */
    fun reload() {
        if (disposed) return
        val current = generation.incrementAndGet()
        loader.cancelAllRequests()
        loader.addRequest({
            val next = try {
                load()
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Could not read the pin assignments", e)
                Snapshot(State.NO_PROJECT)
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && current == generation.get()) show(next)
            }
        }, 0)
    }

    private fun load(): Snapshot {
        val service = QuartusProjectService.getInstance(project)
        // The first look for project files may not have happened yet when the tool window opens.
        service.available
        val revision = service.revision ?: return Snapshot(State.NO_PROJECT)
        val entity = revision.topLevelEntity
        val module = QuartusPins.topLevel(revision.verilogSources.flatMap(::modules), entity)
        val pins = revision.pins
        val overrides = revision.assignments
            .filter { it.command == QuartusCommand.PARAMETER && it.to == null && it.option("-entity") == null }
            .mapNotNull { assignment -> assignment.option("-name")?.let { name -> assignment.value?.let { name to it } } }
            .toMap()
        val device = revision.device
        val report = read(revision.output("pin"))?.let(QuartusPins::pinReport)?.takeIf { report ->
            val fitted = report.device
            fitted == null || device == null || device.equals("AUTO", ignoreCase = true) ||
                fitted.equals(device, ignoreCase = true)
        }
        val defaultStandard = revision.global(QuartusPins.DEFAULT_IO_STANDARD)
        val rows = QuartusPins.rows(QuartusPins.nodes(module, pins, overrides), pins, defaultStandard, report)
        val standards = (QuartusPins.COMMON_IO_STANDARDS + pins.mapNotNull { it.ioStandard } + listOfNotNull(defaultStandard))
            .filter { it.isNotBlank() }.distinct()
        return Snapshot(State.READY, rows, revision.settingsFile, device, entity, module != null, report, standards)
    }

    /** The modules of a source file, read again only when it has changed. */
    private fun modules(file: Path): List<VerilogModuleHeaders.Module> {
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrNull()
            ?: return emptyList()
        sources[file]?.takeIf { it.stamp == stamp }?.let { return it.modules }
        val text = read(file) ?: return emptyList()
        val modules = VerilogModuleHeaders.modules(text)
        sources[file] = Source(stamp, modules)
        return modules
    }

    /** The text of [file], read leniently: a comment in Latin-1 must not hide the module it is in. */
    private fun read(file: Path): String? = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull()

    private fun show(next: Snapshot) {
        // Replacing the rows would end the edit; the newer state waits for it instead.
        if (table.isEditing) {
            pending = next
            return
        }
        val selected = selectedRows().mapTo(HashSet()) { it.name }
        val position = scrollPane.viewport.viewPosition
        snapshot = next
        model.update(next.rows)
        showFitterColumn(next.report != null)
        if (selected.isNotEmpty()) {
            val selection = table.selectionModel
            selection.valueIsAdjusting = true
            selection.clearSelection()
            for (view in 0 until table.rowCount) {
                if (model.rows[table.convertRowIndexToModel(view)].name in selected) selection.addSelectionInterval(view, view)
            }
            selection.valueIsAdjusting = false
        }
        scrollPane.viewport.viewPosition = position
        updateStatus()
        updateEmptyText()
    }

    private fun showFitterColumn(show: Boolean) {
        if (show == fitterColumnShown) return
        if (show) table.addColumn(fitterColumn) else table.removeColumn(fitterColumn)
        fitterColumnShown = show
    }

    private fun selectedRows(): List<QuartusPins.Row> =
        table.selectedRows.sorted().mapNotNull { view -> model.rows.getOrNull(table.convertRowIndexToModel(view)) }

    /** Writes what was entered in a cell to the settings file, and shows it until the file has been read again. */
    private fun assign(index: Int, row: QuartusPins.Row, column: Column, text: String) {
        val service = QuartusProjectService.getInstance(project)
        val updated = when (column) {
            Column.LOCATION -> {
                val location = QuartusSettingsText.pinLocation(text)
                if (location == row.location?.let(QuartusSettingsText::pinLocation)) return
                if (!service.edit("Assign Pin") { QuartusSettingsText.setLocation(it, row.name, location) }) return
                row.copy(location = location, conflicts = emptyList())
            }
            Column.IO_STANDARD -> {
                val value = text.trim().ifEmpty { null }
                val standard = row.standard
                val own = standard?.takeIf { !it.isInherited }?.value
                // Leaving an inherited value as it was shown does not make it the node's own.
                if (value == own || own == null && value != null && value == standard?.value) return
                val edited = service.edit("Assign I/O Standard") {
                    QuartusSettingsText.setInstance(it, "IO_STANDARD", row.name, value)
                }
                if (!edited) return
                row.copy(standard = value?.let { QuartusPins.Standard(it, QuartusPins.StandardSource.OWN) })
            }
            else -> return
        }
        // What was read before the edit is out of date now.
        pending = null
        model.replace(index, updated)
        reload()
    }

    private fun clearLocations(rows: List<QuartusPins.Row>) {
        val nodes = rows.filter { it.location != null }.map { it.name }
        if (nodes.isEmpty()) return
        val edited = QuartusProjectService.getInstance(project).edit("Clear Pin Location") { text ->
            nodes.fold(text) { result, node -> QuartusSettingsText.setLocation(result, node, null) }
        }
        if (edited) reload()
    }

    /** Opens the settings file, at the assignment of the selected node when it has one. */
    private fun openSettings() {
        val path = snapshot.settingsFile ?: return
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        val node = selectedRows().singleOrNull()?.name
        val offset = node?.let { name ->
            FileDocumentManager.getInstance().getDocument(file)?.text?.let { text ->
                QuartusScript.commands(text).let { commands ->
                    commands.firstOrNull { it.command == QuartusCommand.LOCATION && it.to == name }
                        ?: commands.firstOrNull { it.command == QuartusCommand.INSTANCE && it.to == name }
                }?.start
            }
        }
        (if (offset != null) OpenFileDescriptor(project, file, offset) else OpenFileDescriptor(project, file)).navigate(true)
    }

    private fun copySelection() {
        val columns = (0 until table.columnCount).map { table.convertColumnIndexToModel(it) }
        val text = table.selectedRows.sorted().joinToString("\n") { view ->
            val index = table.convertRowIndexToModel(view)
            columns.joinToString("\t") { model.getValueAt(index, it).toString() }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun updateFilter() {
        val query = search.text.trim()
        sorter.rowFilter = if (query.isEmpty() && !onlyUnassigned) null else object : RowFilter<PinTableModel, Int>() {
            override fun include(entry: Entry<out PinTableModel, out Int>): Boolean {
                val row = model.rows.getOrNull(entry.identifier) ?: return false
                if (onlyUnassigned && !row.isUnassigned) return false
                return query.isEmpty() || row.name.contains(query, ignoreCase = true) ||
                    row.location?.contains(query, ignoreCase = true) == true ||
                    row.fitterLocation?.contains(query, ignoreCase = true) == true
            }
        }
        updateEmptyText()
    }

    private fun updateStatus() {
        status.clear()
        status.icon = null
        val current = snapshot
        when (current.state) {
            State.LOADING -> status.append("Loading…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            State.NO_PROJECT -> status.append("No Quartus project", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            State.READY -> {
                val rows = current.rows
                status.append(count(rows.size, "pin"))
                val unassigned = rows.count { it.isUnassigned }
                if (unassigned > 0) status.append(" · $unassigned not assigned")
                val conflicts = rows.filter { it.conflicts.isNotEmpty() }
                    .mapNotNull { it.location?.let(QuartusSettingsText::pinLocation) }.distinct().size
                if (conflicts > 0) status.append(" · ${count(conflicts, "conflict")}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                current.device?.let { status.append(" · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                if (!current.moduleFound) {
                    status.icon = AllIcons.General.Warning
                    status.append(
                        " · top-level entity '${current.entity}' not found in the Verilog sources",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
            }
        }
    }

    private fun updateEmptyText() {
        val text = table.emptyText
        text.clear()
        val current = snapshot
        when {
            current.state == State.LOADING -> text.appendText("Loading pin assignments…")
            current.state == State.NO_PROJECT -> {
                text.appendText("No Quartus project")
                text.appendLine(
                    "Pins are assigned in the .qsf of a Quartus project in this directory",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
                )
            }
            current.rows.isEmpty() && !current.moduleFound -> {
                text.appendText("Top-level entity '${current.entity}' not found in the Verilog sources")
                text.appendLine("Open Settings File", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, ActionListener { openSettings() })
            }
            current.rows.isEmpty() -> text.appendText("${current.entity} has no ports")
            else -> text.appendText("No pins match the filter")
        }
    }

    private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    private inner class CellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ) {
            toolTipText = null
            val pin = runCatching { model.rows.getOrNull(table.convertRowIndexToModel(row)) }.getOrNull() ?: return
            when (Column.entries[table.convertColumnIndexToModel(column)]) {
                Column.NODE -> {
                    append(pin.name)
                    toolTipText = when {
                        pin.node.isOrphan -> "Assigned in the settings file, but not a port of ${snapshot.entity}"
                        pin.node.isWhole -> "The width of ${pin.name} depends on parameters that could not be worked out; " +
                            "its bits are assigned in the settings file"
                        else -> null
                    }
                }
                Column.DIRECTION -> append(
                    QuartusPins.directionLabel(pin.node.direction),
                    if (pin.node.isOrphan) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                )
                Column.LOCATION -> when {
                    pin.location == null -> if (pin.isUnassigned) append("not assigned", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    pin.conflicts.isNotEmpty() -> {
                        append(pin.location, SimpleTextAttributes.ERROR_ATTRIBUTES)
                        toolTipText = "${pin.location} is also assigned to ${pin.conflicts.joinToString(", ")}"
                    }
                    else -> {
                        append(pin.location)
                        snapshot.report?.pin(pin.location)?.let { toolTipText = "Bank ${bank(it)}" }
                    }
                }
                Column.IO_STANDARD -> {
                    val standard = pin.standard ?: return
                    when (standard.source) {
                        QuartusPins.StandardSource.OWN -> append(standard.value)
                        QuartusPins.StandardSource.BUS -> {
                            append(standard.value, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                            toolTipText = "Inherited from ${standard.from}"
                        }
                        QuartusPins.StandardSource.DEFAULT -> {
                            append(standard.value, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                            toolTipText = "The device default (${QuartusPins.DEFAULT_IO_STANDARD})"
                        }
                    }
                }
                Column.FITTER -> {
                    val placed = pin.fitterLocation ?: return
                    when {
                        pin.location == null -> {
                            append(placed)
                            toolTipText = "Placed by the Fitter: the settings do not assign a pin"
                        }
                        QuartusPins.sameLocation(placed, pin.location) -> append(placed, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        else -> {
                            append(placed, SimpleTextAttributes.ERROR_ATTRIBUTES)
                            toolTipText = "The last compilation placed it at $placed; the settings now say ${pin.location}"
                        }
                    }
                }
            }
        }
    }

    private fun bank(pin: QuartusPins.PackagePin): String =
        pin.bank + (snapshot.report?.bankVoltages?.get(pin.bank)?.let { " ($it)" } ?: "")

    /** The pins offered for a location, with their bank and what the Fitter put there, if anything. */
    private inner class LocationRenderer : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(
            list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean,
        ) {
            if (value.isNullOrEmpty()) {
                append("Not assigned", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                return
            }
            append(value)
            val pin = snapshot.report?.pin(value) ?: return
            append("  bank ${bank(pin)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            pin.node?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        }
    }

    private class StandardRenderer : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(
            list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean,
        ) {
            if (value.isNullOrEmpty()) append("None of its own", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) else append(value)
        }
    }

    /**
     * An editable combo box: one of [choices] or anything typed. Its value is the text as typed, which an
     * editable combo box does not reliably commit on its own; text that is not [isValid] keeps the editor open.
     */
    private inner class ChoiceEditor(
        private val combo: ComboBox<String> = ComboBox<String>(),
        private val choices: (QuartusPins.Row) -> List<String>,
        private val isValid: (String) -> Boolean,
        renderer: ColoredListCellRenderer<String>,
    ) : DefaultCellEditor(combo) {
        init {
            clickCountToStart = 2
            combo.isEditable = true
            combo.maximumRowCount = 16
            combo.renderer = renderer
        }

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int,
        ): Component {
            val current = value?.toString().orEmpty()
            val pin = model.rows.getOrNull(table.convertRowIndexToModel(row))
            val items = (listOf("", current) + (pin?.let(choices) ?: emptyList())).distinct()
            combo.model = DefaultComboBoxModel(items.toTypedArray())
            combo.putClientProperty("JComponent.outline", null)
            return super.getTableCellEditorComponent(table, current, isSelected, row, column)
        }

        override fun getCellEditorValue(): Any = text()

        override fun stopCellEditing(): Boolean {
            if (!isValid(text().trim())) {
                combo.putClientProperty("JComponent.outline", "error")
                combo.repaint()
                return false
            }
            return super.stopCellEditing()
        }

        private fun text(): String = (if (combo.isEditable) combo.editor.item else combo.selectedItem)?.toString().orEmpty()
    }

    private inner class RefreshAction : DumbAwareAction("Refresh", "Read the pin assignments again", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = reload()
    }

    private inner class OnlyUnassignedAction :
        DumbAwareToggleAction("Show Only Unassigned", "Show only the ports that have no pin yet", AllIcons.General.Filter) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = onlyUnassigned

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            onlyUnassigned = state
            updateFilter()
        }
    }

    private inner class ClearLocationAction :
        DumbAwareAction("Clear Location", "Remove the pin assignments of the selected nodes", AllIcons.General.Remove) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !table.isEditing && selectedRows().any { it.location != null }
        }

        override fun actionPerformed(e: AnActionEvent) = clearLocations(selectedRows())
    }

    private inner class OpenSettingsAction :
        DumbAwareAction(
            "Open Settings File", "Open the .qsf, at the assignment of the selected node", AllIcons.Actions.EditSource,
        ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = snapshot.settingsFile != null
        }

        override fun actionPerformed(e: AnActionEvent) = openSettings()
    }

    private companion object {
        const val TOOLBAR_PLACE = "QuartusPins"
        val VERILOG_EXTENSIONS = setOf("v", "sv", "vh", "svh")
        val LOG = Logger.getInstance(QuartusPinsPanel::class.java)
    }
}
