package com.jotrorox.verilogtoolbox.gowin.eda

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
import com.intellij.openapi.application.ReadAction
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
import com.jotrorox.verilogtoolbox.quartus.QuartusPins
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
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
 * The Pins tab: every port of the top module, bit by bit, with its pin, I/O standard, pull mode and drive, edited
 * in place without GOWIN EDA's FloorPlanner. All changes go into the project's `.cst`; the table only ever shows
 * what that file says, read again whenever it changes.
 */
class GowinEdaPinsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private enum class Column(val title: String, val width: Int) {
        NODE("Port", 110),
        DIRECTION("Dir.", 55),
        LOCATION("Pin", 70),
        IO_TYPE("I/O Type", 95),
        PULL("Pull", 60),
        DRIVE("Drive", 45),
        PLACED("Placed", 70),
    }

    private enum class State { LOADING, NO_PROJECT, READY }

    /** What one load found; made off the UI thread and never changed after. */
    private class Snapshot(
        val state: State,
        val rows: List<GowinEdaPins.Row> = emptyList(),
        val constraintFile: Path? = null,
        val device: GowinEdaDevice? = null,
        val top: String? = null,
        val moduleFound: Boolean = false,
        val packagePins: List<GowinEdaPackagePin> = emptyList(),
        val ioStandards: Map<String, List<String>> = emptyMap(),
        val bankVoltages: Map<String, String> = emptyMap(),
        val placed: Boolean = false,
    ) {
        private val byIndex: Map<String, GowinEdaPackagePin> by lazy { packagePins.associateBy { it.index.uppercase() } }

        fun pin(location: String): GowinEdaPackagePin? = byIndex[location.trim().uppercase()]
    }

    private class Source(val stamp: Long, val modules: List<VerilogModuleHeaders.Module>)

    private inner class PinTableModel : AbstractTableModel() {
        var rows: List<GowinEdaPins.Row> = emptyList()
            private set

        fun update(rows: List<GowinEdaPins.Row>) {
            this.rows = rows
            fireTableDataChanged()
        }

        fun replace(index: Int, row: GowinEdaPins.Row) {
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
                Column.IO_TYPE -> row.ioType.orEmpty()
                Column.PULL -> row.pullMode.orEmpty()
                Column.DRIVE -> row.drive.orEmpty()
                Column.PLACED -> row.placed?.location.orEmpty()
            }
        }

        /** A vector of unknown width has no pin of its own, only its bits do. */
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            val row = rows.getOrNull(rowIndex) ?: return false
            return when (Column.entries[columnIndex]) {
                Column.LOCATION, Column.IO_TYPE, Column.PULL, Column.DRIVE -> !row.node.isWhole
                else -> false
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows.getOrNull(rowIndex) ?: return
            assign(rowIndex, row, Column.entries[columnIndex], value?.toString().orEmpty().trim())
        }
    }

    private val model = PinTableModel()
    private val sorter = TableRowSorter(model)
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
            if (!isEditing) sink[PlatformDataKeys.COPY_PROVIDER] = copyProvider
        }
    }

    private val scrollPane = ScrollPaneFactory.createScrollPane(table, true)
    private val search = SearchTextField(false)
    private val status = SimpleColoredComponent()
    private val placedColumn: TableColumn
    private var placedColumnShown = true
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
        for (column in listOf(Column.NODE, Column.LOCATION, Column.PLACED)) sorter.setComparator(column.ordinal, NaturalComparator.INSTANCE)
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
            choices = { row -> GowinEdaPins.freePins(snapshot.packagePins, snapshot.rows, row.name).map { it.index } },
            isValid = { GowinEdaPins.isLocation(it) },
            renderer = LocationRenderer(),
        )
        table.columnModel.getColumn(Column.IO_TYPE.ordinal).cellEditor = ChoiceEditor(
            choices = { row -> GowinEdaPins.ioTypes(snapshot.ioStandards, row.node.direction) },
            isValid = { it.isEmpty() || it.all { c -> c.isLetterOrDigit() || c == '_' } },
            renderer = PlainRenderer("Default"),
        )
        table.columnModel.getColumn(Column.PULL.ordinal).cellEditor = ChoiceEditor(
            choices = { GowinEdaPins.PULL_MODES }, isValid = { it.isEmpty() || it.uppercase() in GowinEdaPins.PULL_MODES }, renderer = PlainRenderer("Default"),
        )
        table.columnModel.getColumn(Column.DRIVE.ordinal).cellEditor = ChoiceEditor(
            choices = { GowinEdaPins.DRIVES }, isValid = { it.isEmpty() || it.toIntOrNull() != null || it.equals("OFF", true) }, renderer = PlainRenderer("Default"),
        )
        placedColumn = table.columnModel.getColumn(Column.PLACED.ordinal)

        search.textEditor.emptyText.text = "Filter by port or pin"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateFilter()
        })

        val clear = ClearLocationAction()
        clear.registerCustomShortcutSet(CommonShortcuts.getDelete(), table)
        val actions = DefaultActionGroup(RefreshAction(), Separator.getInstance(), OnlyUnassignedAction(), clear, Separator.getInstance(), OpenConstraintsAction())
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true)
        toolbar.targetComponent = table
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            add(toolbar.component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }
        status.border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(3, 8))
        add(top, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        updateStatus()
        updateEmptyText()

        val connection = project.messageBus.connect(parent)
        connection.subscribe(GowinEdaProjectListener.TOPIC, GowinEdaProjectListener { reload() })
        // The ports come from the Verilog sources, which the project service does not watch.
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.substringAfterLast('.', "").lowercase() in VERILOG_EXTENSIONS }) reload()
            }
        })
        reload()
    }

    fun reload() {
        if (disposed) return
        val current = generation.incrementAndGet()
        loader.cancelAllRequests()
        loader.addRequest({
            val next = try {
                load()
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Could not read the pins", e)
                Snapshot(State.NO_PROJECT)
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && current == generation.get()) show(next)
            }
        }, 0)
    }

    private fun load(): Snapshot {
        val service = GowinEdaProjectService.getInstance(project)
        service.available
        val gowinProject = service.gowinProject ?: return Snapshot(State.NO_PROJECT)
        val top = gowinProject.topModule
        val module = top?.let { name -> QuartusPins.topLevel(gowinProject.verilogSources.flatMap(::modules), name) }
        val constraints = gowinProject.constraintFile
        val text = constraints?.let { file ->
            // The editor's text, so that what is typed in the file shows at once.
            LocalFileSystem.getInstance().findFileByNioFile(file)?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
                ?.let { document -> ReadAction.nonBlocking(Callable { document.text }).executeSynchronously() }
                ?: read(file)
        }.orEmpty()
        val compilation = GowinEdaCompilation(gowinProject)
        // A placement for another part would put the ports on pins this one may not have.
        val placedHere = compilation.placedPart == null || compilation.placedPart == gowinProject.device?.partNumber
        val placed = if (placedHere) compilation.placedPorts else emptyList()
        val rows = GowinEdaPins.rows(module, GowinEdaCstText.statements(text), placed)
        val installation = GowinEda.find()
        return Snapshot(
            State.READY, rows, constraints, gowinProject.device, top, module != null,
            GowinEdaDevices.packagePins(installation, gowinProject.device),
            GowinEdaDevices.ioStandards(installation, gowinProject.device?.name),
            if (placedHere) compilation.bankVoltages else emptyMap(),
            placed.isNotEmpty(),
        )
    }

    private fun modules(file: Path): List<VerilogModuleHeaders.Module> {
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrNull() ?: return emptyList()
        sources[file]?.takeIf { it.stamp == stamp }?.let { return it.modules }
        val modules = VerilogModuleHeaders.modules(read(file) ?: return emptyList())
        sources[file] = Source(stamp, modules)
        return modules
    }

    /** The text of [file], read leniently: a comment in Latin-1 must not hide the module it is in. */
    private fun read(file: Path): String? = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull()

    private fun show(next: Snapshot) {
        if (table.isEditing) {
            pending = next
            return
        }
        val selected = selectedRows().mapTo(HashSet()) { it.name }
        val position = scrollPane.viewport.viewPosition
        snapshot = next
        model.update(next.rows)
        showPlacedColumn(next.placed)
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

    private fun showPlacedColumn(show: Boolean) {
        if (show == placedColumnShown) return
        if (show) table.addColumn(placedColumn) else table.removeColumn(placedColumn)
        placedColumnShown = show
    }

    private fun selectedRows(): List<GowinEdaPins.Row> =
        table.selectedRows.sorted().mapNotNull { view -> model.rows.getOrNull(table.convertRowIndexToModel(view)) }

    /** Writes what was entered in a cell to the constraints, creating them first when the project has none. */
    private fun assign(index: Int, row: GowinEdaPins.Row, column: Column, text: String) {
        val value = text.ifEmpty { null }?.let { if (column == Column.LOCATION) it.replace(" ", "").uppercase() else it.uppercase() }
        val (current, title, change) = when (column) {
            Column.LOCATION -> Triple(row.location, "Assign Pin") { t: String -> GowinEdaCstText.setLocation(t, row.name, value) }
            Column.IO_TYPE -> Triple(row.ioType, "Assign I/O Type") { t: String -> GowinEdaCstText.setAttribute(t, row.name, "IO_TYPE", value) }
            Column.PULL -> Triple(row.pullMode, "Assign Pull Mode") { t: String -> GowinEdaCstText.setAttribute(t, row.name, "PULL_MODE", value) }
            Column.DRIVE -> Triple(row.drive, "Assign Drive") { t: String -> GowinEdaCstText.setAttribute(t, row.name, "DRIVE", value) }
            else -> return
        }
        if (value == current?.uppercase()) return
        val service = GowinEdaProjectService.getInstance(project)
        val file = snapshot.constraintFile ?: run {
            val gowinProject = service.gowinProject ?: return
            GowinEdaConstraints.create(project, gowinProject) ?: return
        }
        if (!service.editFile(file, title, create = false) { it?.let(change) }) return
        val updated = when (column) {
            Column.LOCATION -> row.copy(location = value, conflicts = emptyList())
            Column.IO_TYPE -> row.copy(ioType = value)
            Column.PULL -> row.copy(pullMode = value)
            else -> row.copy(drive = value)
        }
        pending = null
        model.replace(index, updated)
        reload()
    }

    private fun clearLocations(rows: List<GowinEdaPins.Row>) {
        val nodes = rows.filter { it.location != null }.map { it.name }
        val file = snapshot.constraintFile ?: return
        if (nodes.isEmpty()) return
        val edited = GowinEdaProjectService.getInstance(project).editFile(file, "Clear Pin", create = false) { text ->
            text?.let { nodes.fold(it) { result, node -> GowinEdaCstText.setLocation(result, node, null) } }
        }
        if (edited) reload()
    }

    /** Opens the constraints, at the statement of the selected port when it has one. */
    private fun openConstraints() {
        val path = snapshot.constraintFile ?: run {
            val service = GowinEdaProjectService.getInstance(project)
            val gowinProject = service.gowinProject ?: return
            GowinEdaConstraints.create(project, gowinProject)?.let { GowinEdaActions.open(project, it) }
            return
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        val node = selectedRows().singleOrNull()?.name
        val offset = node?.let { name ->
            FileDocumentManager.getInstance().getDocument(file)?.text?.let { text ->
                GowinEdaCstText.statements(text).firstOrNull { it.node.equals(name.replace(" ", ""), true) }?.range?.first
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
                    row.location?.contains(query, ignoreCase = true) == true || row.placed?.location?.contains(query, ignoreCase = true) == true
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
            State.NO_PROJECT -> status.append("No GOWIN EDA project", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            State.READY -> {
                val rows = current.rows
                status.append(count(rows.size, "pin"))
                val unassigned = rows.count { it.isUnassigned }
                if (unassigned > 0) status.append(" · $unassigned not assigned")
                val conflicts = rows.filter { it.conflicts.isNotEmpty() }.mapNotNull { it.location }.distinct().size
                if (conflicts > 0) status.append(" · ${count(conflicts, "conflict")}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                current.device?.let { status.append(" · ${it.partNumber}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                when {
                    current.constraintFile == null -> {
                        status.icon = AllIcons.General.Information
                        status.append(" · no constraint file yet; assigning a pin creates one", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    !current.moduleFound -> {
                        status.icon = AllIcons.General.Warning
                        status.append(" · top module ${current.top?.let { "'$it' " }.orEmpty()}not found in the sources", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
    }

    private fun updateEmptyText() {
        val text = table.emptyText
        text.clear()
        val current = snapshot
        when {
            current.state == State.LOADING -> text.appendText("Loading pins…")
            current.state == State.NO_PROJECT -> {
                text.appendText("No GOWIN EDA project")
                text.appendLine("Pins are assigned in the .cst of a GOWIN EDA project in this directory", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            current.rows.isEmpty() && !current.moduleFound -> {
                text.appendText(current.top?.let { "Top module '$it' not found in the Verilog sources" } ?: "The project has no Verilog sources")
                text.appendLine("Open Project File", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, ActionListener {
                    GowinEdaProjectService.getInstance(project).active?.let { GowinEdaActions.open(project, it) }
                })
            }
            current.rows.isEmpty() -> text.appendText("${current.top} has no ports")
            else -> text.appendText("No pins match the filter")
        }
    }

    private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    /** `52 · bank 1 (3.3 V) · GCLKT_3`, what a tooltip and the list of pins say about a pin. */
    private fun describe(pin: GowinEdaPackagePin): String = listOfNotNull(
        pin.bank?.let { bank -> "bank $bank" + (snapshot.bankVoltages[bank.toString()]?.let { " ($it V)" } ?: "") },
        pin.name.takeIf { it.isNotBlank() },
        pin.function,
    ).joinToString(" · ")

    private inner class CellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            toolTipText = null
            val pin = runCatching { model.rows.getOrNull(table.convertRowIndexToModel(row)) }.getOrNull() ?: return
            val placed = pin.placed
            when (Column.entries[table.convertColumnIndexToModel(column)]) {
                Column.NODE -> {
                    append(pin.name)
                    toolTipText = when {
                        pin.node.isOrphan -> "Constrained in the .cst, but not a port of ${snapshot.top}"
                        pin.node.isWhole -> "The width of ${pin.name} depends on parameters that could not be worked out; constrain its bits in the .cst"
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
                    snapshot.packagePins.isNotEmpty() && pin.location.split(',').any { snapshot.pin(it) == null } -> {
                        append(pin.location, SimpleTextAttributes.ERROR_ATTRIBUTES)
                        toolTipText = "${snapshot.device?.partNumber ?: "The device"} has no pin ${pin.location}"
                    }
                    else -> {
                        append(pin.location)
                        snapshot.pin(pin.location.substringBefore(','))?.let { toolTipText = describe(it) }
                    }
                }
                Column.IO_TYPE -> inherited(pin.ioType, placed?.ioType, "The I/O type place and route chose, as the .cst sets none")
                Column.PULL -> inherited(pin.pullMode, placed?.pullMode, "The pull mode place and route chose, as the .cst sets none")
                Column.DRIVE -> inherited(pin.drive, placed?.drive, "The drive place and route chose, as the .cst sets none")
                Column.PLACED -> {
                    val location = placed?.location ?: return
                    val text = location + (placed.bank?.let { "/$it" } ?: "")
                    when {
                        pin.location == null -> {
                            append(text)
                            toolTipText = "Placed by place and route: the .cst assigns no pin"
                        }
                        pin.location.split(',').first().equals(location, true) -> append(text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        else -> {
                            append(text, SimpleTextAttributes.ERROR_ATTRIBUTES)
                            toolTipText = "The last place and route put it on $location; the .cst now says ${pin.location}"
                        }
                    }
                }
            }
        }

        /** The value of the `.cst`, or grayed what place and route used without one. */
        private fun inherited(own: String?, placed: String?, tooltip: String) {
            when {
                own != null -> append(own)
                placed != null -> {
                    append(placed, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    toolTipText = tooltip
                }
            }
        }
    }

    private inner class LocationRenderer : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value.isNullOrEmpty()) {
                append("Not assigned", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                return
            }
            append(value)
            snapshot.pin(value)?.let { append("  " + describe(it), SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
    }

    private class PlainRenderer(private val empty: String) : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value.isNullOrEmpty()) append(empty, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) else append(value)
        }
    }

    /** An editable combo box: one of [choices] or anything typed; text that is not [isValid] keeps the editor open. */
    private inner class ChoiceEditor(
        private val combo: ComboBox<String> = ComboBox<String>(),
        private val choices: (GowinEdaPins.Row) -> List<String>,
        private val isValid: (String) -> Boolean,
        renderer: ColoredListCellRenderer<String>,
    ) : DefaultCellEditor(combo) {
        init {
            clickCountToStart = 2
            combo.isEditable = true
            combo.maximumRowCount = 16
            combo.renderer = renderer
        }

        override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
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

    private inner class RefreshAction : DumbAwareAction("Refresh", "Read the pins again", AllIcons.Actions.Refresh) {
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

    private inner class ClearLocationAction : DumbAwareAction("Clear Pin", "Remove the pins of the selected ports", AllIcons.General.Remove) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !table.isEditing && selectedRows().any { it.location != null }
        }

        override fun actionPerformed(e: AnActionEvent) = clearLocations(selectedRows())
    }

    private inner class OpenConstraintsAction :
        DumbAwareAction("Open Constraints", "Open the .cst, at the statement of the selected port", AllIcons.Actions.EditSource) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = snapshot.state == State.READY
        }

        override fun actionPerformed(e: AnActionEvent) = openConstraints()
    }

    private companion object {
        const val TOOLBAR_PLACE = "GowinEdaPins"
        val VERILOG_EXTENSIONS = setOf("v", "sv", "vh", "svh")
        val LOG = Logger.getInstance(GowinEdaPinsPanel::class.java)
    }
}
