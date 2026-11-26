package com.jotrorox.verilogtoolbox.quartus

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.jotrorox.verilogtoolbox.quartus.tcl.QuartusTclReportFileType
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.RowFilter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.event.DocumentListener as SwingDocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import javax.swing.text.DefaultHighlighter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Opens Quartus's text reports (`.rpt`) and pin-out files (`.pin`) in a viewer of their sections: tables that
 * sort and filter, messages that lead to the source, and the plain text one click away.
 */
class QuartusReportEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = isReport(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return QuartusReportEditor(project, text, QuartusReportView(project, file))
    }

    override fun getEditorTypeId(): String = "quartus-report"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        fun isReport(file: VirtualFile): Boolean =
            !file.isDirectory && file.fileType == QuartusTclReportFileType && (file.extension == "rpt" || file.extension == "pin")
    }
}

/** The report next to its text, the report shown to begin with. GOWIN EDA's reports are shown in it too. */
class QuartusReportEditor internal constructor(
    private val project: Project,
    text: TextEditor,
    private val view: QuartusReportView,
    name: String = "Quartus Report",
) : TextEditorWithPreview(text, view, name, Layout.SHOW_PREVIEW) {
    init {
        view.showLine = ::showLine
    }

    /** Shows the text at [line], counted from 0. */
    fun showLine(line: Int) {
        if (getLayout() != Layout.SHOW_EDITOR_AND_PREVIEW) setLayout(Layout.SHOW_EDITOR)
        val editor = textEditor.editor
        val document = editor.document
        if (document.lineCount == 0) return
        editor.caretModel.moveToOffset(document.getLineStartOffset(line.coerceIn(0, document.lineCount - 1)))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER_UP)
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    /** Shows the report, on the first section whose title contains [title]. */
    fun select(title: String) {
        if (getLayout() == Layout.SHOW_EDITOR) setLayout(Layout.SHOW_PREVIEW)
        view.select(title)
    }
}

/** The report of a file, read again whenever its text changes, with [parse], which reads a Quartus report unless another is given. */
class QuartusReportView internal constructor(
    project: Project,
    private val file: VirtualFile,
    private val parse: (CharSequence) -> QuartusReportDocument = QuartusReportDocument::parse,
) : UserDataHolderBase(), FileEditor {
    internal var showLine: (Int) -> Unit = {}
    private val panel = QuartusReportPanel(project, file) { showLine(it) }
    private val document = FileDocumentManager.getInstance().getDocument(file)
    private val reload = Timer(RELOAD_DELAY) { load() }.apply { isRepeats = false }
    private var generation = 0
    private var disposed = false

    init {
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = reload.restart()
        }, this)
        load()
    }

    /** Reads the report off the UI thread; it can be megabytes. Only the last of several reads is shown. */
    private fun load() {
        val text = document?.immutableCharSequence
        val current = ++generation
        AppExecutorUtil.getAppExecutorService().execute {
            val report = try {
                parse(text ?: VfsUtilCore.loadText(file))
            } catch (e: Exception) {
                LOG.warn("Cannot read the report ${file.path}", e)
                null
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && current == generation) panel.show(report)
            }
        }
    }

    fun select(title: String) = panel.select(title)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus
    override fun getName(): String = "Report"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        disposed = true
        reload.stop()
    }

    private companion object {
        const val RELOAD_DELAY = 300
        val LOG = logger<QuartusReportView>()
    }
}

/** Opens [file] in the report viewer, on the first section whose title contains [section]. */
internal fun openQuartusReport(project: Project, file: Path, section: String) {
    QuartusFiles.open(project, file)
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return
    FileEditorManager.getInstance(project).getEditorList(virtualFile).filterIsInstance<QuartusReportEditor>()
        .firstOrNull()?.select(section)
}

/**
 * The viewer: the report's title and status on top, its sections on the left, filtered by what is typed in
 * the search field, and the section chosen on the right.
 */
internal class QuartusReportPanel(
    private val project: Project,
    private val file: VirtualFile,
    private val showLine: (Int) -> Unit,
) : JPanel(BorderLayout()) {
    private val title = JBLabel().apply { font = JBFont.h3().asBold() }
    private val status = JBLabel()
    private val details = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val search = SearchTextField(false)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val content = JPanel(BorderLayout())
    private val views = mutableMapOf<QuartusReportDocument.Section, SectionView>()
    private var report: QuartusReportDocument? = null
    private var shown: SectionView? = null
    private var wanted: String? = null

    val preferredFocus: JComponent get() = tree

    init {
        val header = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(title)
                add(Box.createHorizontalStrut(JBUI.scale(12)))
                add(status)
            }, BorderLayout.NORTH)
            add(details, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)

        search.textEditor.emptyText.text = "Search sections and their contents"
        search.border = JBUI.Borders.empty(6)
        search.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = rebuild()
            override fun removeUpdate(e: SwingDocumentEvent) = rebuild()
            override fun changedUpdate(e: SwingDocumentEvent) = rebuild()
        })
        search.textEditor.registerKeyboardAction({ tree.requestFocusInWindow() }, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.emptyText.text = "Reading the report…"
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeSelectionListener { selected()?.let { showSection(it) } }

        val sections = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, "VerilogToolbox.QuartusReport.Sections", 0.25f)
        splitter.firstComponent = sections
        splitter.secondComponent = content
        add(splitter, BorderLayout.CENTER)

        // Find filters the section shown; pressed again, it searches all sections.
        DumbAwareAction.create { if (shown?.focusFilter() != true) search.requestFocusInWindow() }
            .registerCustomShortcutSet(CommonShortcuts.getFind(), this)
    }

    fun show(report: QuartusReportDocument?) {
        val previous = (shown?.section)?.title
        this.report = report
        views.clear()
        shown = null
        title.text = report?.title ?: file.name
        val reportStatus = report?.verdict ?: report?.status ?: report?.timing
        status.text = reportStatus.orEmpty()
        status.icon = when (reportStatus?.let { QuartusReportValues.verdict(it, "") }) {
            null -> if (reportStatus == null) null else AllIcons.General.Information
            QuartusReportValues.Verdict.GOOD -> AllIcons.General.InspectionsOK
            QuartusReportValues.Verdict.BAD -> AllIcons.General.Error
        }
        status.foreground = when (status.icon) {
            AllIcons.General.InspectionsOK -> SUCCESS
            AllIcons.General.Error -> UIUtil.getErrorForeground()
            else -> UIUtil.getLabelForeground()
        }
        details.text = report?.details?.joinToString("   ·   ").orEmpty()
        details.isVisible = details.text.isNotEmpty()
        tree.emptyText.text = if (report == null) "Cannot read the report" else "No sections match"
        rebuild(previous)
        // A section asked for before the report was read.
        wanted?.let { wanted = null; select(it) }
    }

    /** Selects the first section whose title contains [title], when the report is read. */
    fun select(title: String) {
        if (report == null) {
            wanted = title
            return
        }
        search.text = ""
        val node = sectionNodes().firstOrNull { (it.userObject as Entry).section.title.contains(title, ignoreCase = true) } ?: return
        selectNode(node)
    }

    private val query: String get() = search.text.trim()

    /** The titles of the sections listed, in their order; for tests. */
    internal val listedTitles: List<String> get() = sectionNodes().map { (it.userObject as Entry).section.title }

    /** The title of the section shown; for tests. */
    internal val selectedTitle: String? get() = selected()?.title

    /** Searches all sections for [text], as if it was typed; for tests. */
    internal fun search(text: String) {
        search.text = text
    }

    /** Lists the sections that match the search, grouped, and keeps or makes a selection. */
    private fun rebuild(keep: String? = (shown?.section)?.title) {
        val report = report
        root.removeAllChildren()
        if (report != null) {
            val query = query
            val sections = report.sections.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) || it.contains(query) }
            for ((group, members) in QuartusReportGroups.group(sections)) {
                if (group == null) {
                    members.forEach { root.add(DefaultMutableTreeNode(Entry(it, it.title))) }
                } else {
                    val node = DefaultMutableTreeNode(Group(group, members.size))
                    members.forEach { node.add(DefaultMutableTreeNode(Entry(it, QuartusReportGroups.label(it.title, group)))) }
                    root.add(node)
                }
            }
        }
        model.reload()
        if (query.isNotEmpty()) for (row in 0 until root.childCount) tree.expandPath(TreePath((root.getChildAt(row) as DefaultMutableTreeNode).path))
        val nodes = sectionNodes()
        val initial = if (query.isEmpty()) initial(report) else null
        val node = nodes.firstOrNull { (it.userObject as Entry).section.title == keep }
            ?: nodes.firstOrNull { (it.userObject as Entry).section == initial }
            ?: nodes.firstOrNull()
        if (node != null) {
            selectNode(node)
        } else {
            shown = null
            content.removeAll()
            content.revalidate()
            content.repaint()
        }
        // The same section, shown again with what is searched for now.
        shown?.filter(if (shown?.section?.title?.contains(query, ignoreCase = true) == true) "" else query)
    }

    /** The section a report opens on: the messages when something failed, else its summary. */
    private fun initial(report: QuartusReportDocument?): QuartusReportDocument.Section? {
        report ?: return null
        val messages = report.sections.filterIsInstance<QuartusReportDocument.Messages>().firstOrNull()
        if (messages != null && messages.count(QuartusSeverity.ERROR) > 0) return messages
        return report.summary ?: report.sections.firstOrNull()
    }

    /** The nodes of sections, in the order of the tree. */
    private fun sectionNodes(): List<DefaultMutableTreeNode> =
        root.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>().filter { it.userObject is Entry }.toList()

    private fun selectNode(node: DefaultMutableTreeNode) {
        val path = TreePath(node.path)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun selected(): QuartusReportDocument.Section? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Entry)?.section

    private fun showSection(section: QuartusReportDocument.Section) {
        if (shown?.section == section) return
        val view = views.getOrPut(section) {
            when (section) {
                is QuartusReportDocument.Table -> TableView(section, showLine)
                is QuartusReportDocument.Text -> TextView(project, section, showLine)
                is QuartusReportDocument.Messages -> MessagesView(project, file, section, showLine)
            }
        }
        shown = view
        content.removeAll()
        content.add(view.component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        val query = query
        view.filter(if (query.isEmpty() || section.title.contains(query, ignoreCase = true)) "" else query)
    }

    private class Entry(val section: QuartusReportDocument.Section, val label: String)
    private class Group(val title: String, val size: Int)

    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Group -> {
                    icon = AllIcons.Nodes.Folder
                    QuartusMessageCell.appendHighlighted(this, item.title, query, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${item.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Entry -> {
                    val section = item.section
                    icon = when (section) {
                        is QuartusReportDocument.Table -> AllIcons.Nodes.DataTables
                        is QuartusReportDocument.Text -> AllIcons.FileTypes.Text
                        is QuartusReportDocument.Messages -> section.worst?.let { QuartusMessageCell.icon(it) } ?: AllIcons.General.Information
                    }
                    appendLabel(item.label)
                    when (section) {
                        is QuartusReportDocument.Table ->
                            if (section.columns.isNotEmpty()) append("  ${section.rows.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        is QuartusReportDocument.Messages -> {
                            val counts = listOf(
                                QuartusSeverity.ERROR to "error", QuartusSeverity.CRITICAL_WARNING to "critical",
                                QuartusSeverity.WARNING to "warning",
                            ).mapNotNull { (severity, noun) ->
                                section.count(severity).takeIf { it > 0 }?.let { YosysSynthesis.plural(it, noun) }
                            }
                            if (counts.isNotEmpty()) append("  " + counts.joinToString(", "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        is QuartusReportDocument.Text -> {}
                    }
                    toolTipText = section.title
                }
            }
        }

        /** A name in the hierarchy, such as `top|sub:u_sub|state`, with its own part first and its path after it in grey. */
        private fun appendLabel(label: String) {
            val trimmed = label.trimEnd('|')
            val split = trimmed.lastIndexOf('|')
            if (split <= 0 || split == trimmed.length - 1) {
                QuartusMessageCell.appendHighlighted(this, label, query, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }
            QuartusMessageCell.appendHighlighted(this, trimmed.substring(split + 1), query, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            QuartusMessageCell.appendHighlighted(this, "  " + trimmed.substring(0, split), query, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        val SUCCESS: Color = JBColor(0x2E7D32, 0x6AAB73)
    }
}

/** Puts sections with a common start, such as the operating corners of timing or the parameters of each instance, under one node. */
internal object QuartusReportGroups {
    private val MODEL = Regex("""^(.+? Model) \S""")
    private val DELIMITERS = listOf(": ", " - ")
    private const val SOURCE_ASSIGNMENTS = "Source assignments for "

    /** What the sections titled like [title] start with, or null. */
    fun key(title: String): String? {
        for (delimiter in DELIMITERS) {
            val at = title.indexOf(delimiter)
            if (at > 0) return title.substring(0, at)
        }
        if (title.startsWith(SOURCE_ASSIGNMENTS)) return SOURCE_ASSIGNMENTS.removeSuffix(" for ")
        return MODEL.find(title)?.groupValues?.get(1)
    }

    /** [title] without the [group] it is listed under, and without the quotes around an instance name. */
    fun label(title: String, group: String): String =
        title.removePrefix(group).trimStart().removePrefix(":").removePrefix("-").trimStart().removePrefix("for ")
            .trim().removeSurrounding("\"").ifEmpty { title }

    /** Runs of two or more sections in a row with the same key, under that key; the others alone, under null. */
    fun <T : QuartusReportDocument.Section> group(sections: List<T>): List<Pair<String?, List<T>>> {
        val result = mutableListOf<Pair<String?, MutableList<T>>>()
        for (section in sections) {
            val key = key(section.title)
            val last = result.lastOrNull()
            if (key != null && last != null && last.first == key) last.second += section else result += key to mutableListOf(section)
        }
        return result.flatMap { (key, members) -> if (key != null && members.size > 1) listOf(key to members) else members.map { null to listOf(it) } }
    }
}

/** What the right side shows of a section. */
private abstract class SectionView(val section: QuartusReportDocument.Section, private val showLine: (Int) -> Unit) {
    protected val filterField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter"
        textEditor.columns = FILTER_COLUMNS
        addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = applyFilter(text.trim())
            override fun removeUpdate(e: SwingDocumentEvent) = applyFilter(text.trim())
            override fun changedUpdate(e: SwingDocumentEvent) = applyFilter(text.trim())
        })
    }
    protected val caption = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    abstract val body: JComponent

    val component: JComponent by lazy {
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 4, 12)
            val left = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(JBLabel(section.title).apply { font = JBFont.h4().asBold(); toolTipText = section.title })
                add(caption)
            }
            add(left, BorderLayout.CENTER)
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(filterField)
                add(ActionLink("Show in text") { showLine(section.line) }.apply {
                    toolTipText = "Show this section in the text of the report"
                })
            }
            add(right, BorderLayout.EAST)
        }
        JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            val middle = JPanel(BorderLayout())
            if (section.preface.isNotEmpty()) middle.add(notes(section.preface, null), BorderLayout.NORTH)
            middle.add(body, BorderLayout.CENTER)
            if (section.notes.isNotEmpty()) middle.add(notes(section.notes, AllIcons.General.Information), BorderLayout.SOUTH)
            add(middle, BorderLayout.CENTER)
        }
    }

    /** Shows only what contains [text]; called with the search of all sections when that found this one. */
    fun filter(text: String) {
        component
        if (filterField.text.trim() != text) filterField.text = text else applyFilter(text)
    }

    protected abstract fun applyFilter(text: String)

    /** Moves the focus to the filter; false when it is there already. */
    fun focusFilter(): Boolean {
        if (filterField.textEditor.hasFocus()) return false
        filterField.requestFocusInWindow()
        filterField.selectText()
        return true
    }

    protected fun showLine(line: Int) = showLine.invoke(line)

    private fun notes(lines: List<String>, icon: Icon?): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4, 12, 6, 12)
        if (icon != null) add(JBLabel(icon).apply { verticalAlignment = SwingConstants.TOP; border = JBUI.Borders.emptyRight(6) }, BorderLayout.WEST)
        add(JBTextArea(lines.joinToString("\n")).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont()
            border = null
        }, BorderLayout.CENTER)
    }

    private companion object {
        const val FILTER_COLUMNS = 18
    }
}

/** A table that sorts by any column, numbers as numbers, and shows only the rows that contain the filter. */
private class TableView(private val table: QuartusReportDocument.Table, showLine: (Int) -> Unit) : SectionView(table, showLine) {
    private var highlight = ""

    private val model = object : AbstractTableModel() {
        override fun getRowCount(): Int = table.rows.size
        override fun getColumnCount(): Int = table.width
        override fun getColumnName(column: Int): String = table.columns.getOrElse(column) { "" }
        override fun getValueAt(row: Int, column: Int): Any = table.rows[row].cells.getOrElse(column) { QuartusReportDocument.Cell("") }
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    /**
     * The columns that hold numbers, set to the right. The first column names the row; the values of a summary
     * are of all kinds; resources used keep to the left, with their gauge after them.
     */
    private val numeric: Set<Int> = if (table.columns.isEmpty()) emptySet() else (1 until table.width).filter { column ->
        val values = table.rows.mapNotNull { it.cells.getOrNull(column)?.text?.ifEmpty { null } }
        values.isNotEmpty() && values.none { QuartusReportValues.usage(it) != null } &&
            values.count { QuartusReportValues.number(it) != null } >= values.size * NUMERIC_SHARE
    }.toSet()

    private val sorter = object : TableRowSorter<AbstractTableModel>(model) {
        /** Ascending, descending, then back to the order of the report, which is often a hierarchy. */
        override fun toggleSortOrder(column: Int) {
            val current = sortKeys.firstOrNull()
            if (current != null && current.column == column && current.sortOrder == SortOrder.DESCENDING) sortKeys = emptyList()
            else super.toggleSortOrder(column)
        }
    }.apply {
        for (column in 0 until table.width) setComparator(column, QuartusReportValues.COMPARATOR)
    }

    private val grid = object : JBTable(model) {
        /** As wide as the window when the columns fit, else as wide as they are, with a scroll bar. */
        override fun getScrollableTracksViewportWidth(): Boolean = preferredSize.width < (parent?.width ?: 0)
    }.apply {
        // A table without column names has no header to sort by; it still filters.
        rowSorter = sorter
        if (table.columns.isEmpty()) tableHeader = null
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        setShowHorizontalLines(true)
        intercellSpacing = JBUI.size(0, 1)
        setDefaultRenderer(Any::class.java, Renderer())
        columnSelectionAllowed = false
        emptyText.text = "No rows contain the filter"
        PopupHandler.installPopupMenu(this, DefaultActionGroup(CopyRows(), ShowRow()), "QuartusReportTable")
    }

    override val body: JComponent = ScrollPaneFactory.createScrollPane(grid, true).also { fitColumns() }

    init {
        updateCaption()
    }

    override fun applyFilter(text: String) {
        highlight = text
        sorter.rowFilter = if (text.isEmpty()) null else object : RowFilter<AbstractTableModel, Int>() {
            override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean =
                table.rows[entry.identifier].cells.any { it.text.contains(text, ignoreCase = true) }
        }
        grid.repaint()
        updateCaption()
    }

    private fun updateCaption() {
        val shown = grid.rowCount
        val all = table.rows.size
        val rows = YosysSynthesis.plural(all, "row")
        caption.text = when {
            shown != all -> "$shown of $rows"
            table.columns.isEmpty() -> ""
            else -> rows
        }
        caption.isVisible = caption.text.isNotEmpty()
    }

    /** Each column as wide as its widest value, up to a limit; the cells of a long table are only sampled. */
    private fun fitColumns() {
        val metrics = grid.getFontMetrics(grid.font)
        val header = grid.tableHeader
        val headerMetrics = header?.let { it.getFontMetrics(it.font) }
        for (column in 0 until table.width) {
            var width = headerMetrics?.stringWidth(table.columns.getOrElse(column) { "" })?.plus(JBUI.scale(SORT_ICON)) ?: 0
            for (row in table.rows.take(SAMPLED_ROWS)) {
                val cell = row.cells.getOrNull(column) ?: continue
                width = maxOf(width, metrics.stringWidth(cell.text) + indent(cell, column))
            }
            // The table is as wide as its columns are now, not as they would like to be: both are set.
            val fitted = minOf(width + JBUI.scale(CELL_PADDING * 2 + 4) + gauge(column), JBUI.scale(MAX_COLUMN))
            grid.columnModel.getColumn(column).apply {
                preferredWidth = fitted
                this.width = fitted
            }
        }
    }

    private fun indent(cell: QuartusReportDocument.Cell, column: Int): Int = if (column == 0) JBUI.scale(INDENT * cell.indent) else 0

    /** Room for the gauge after the values of a column of resources. */
    private fun gauge(column: Int): Int =
        if (table.rows.any { QuartusReportValues.usage(it.cells.getOrNull(column)?.text.orEmpty()) != null }) JBUI.scale(GAUGE_GAP + GAUGE_WIDTH) else 0

    private fun selectedRows(): List<QuartusReportDocument.Row> =
        grid.selectedRows.map { table.rows[grid.convertRowIndexToModel(it)] }

    private inner class CopyRows : DumbAwareAction("Copy", "Copy the selected rows, with a tab between the cells", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = grid.selectedRowCount > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val header = if (table.columns.isEmpty()) emptyList() else listOf(table.columns.joinToString("\t"))
            val rows = selectedRows().map { row -> row.cells.joinToString("\t") { it.text } }
            CopyPasteManager.getInstance().setContents(StringSelection((header + rows).joinToString("\n")))
        }
    }

    private inner class ShowRow : DumbAwareAction("Show in Text", "Show this row in the text of the report", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = grid.selectedRowCount > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedRows().firstOrNull()?.let { showLine(it.line) }
        }
    }

    /**
     * A cell: set in by its depth in the first column, numbers to the right, failures in red, successes in
     * green, and how much of a resource is used as a bar under it.
     */
    private inner class Renderer : ColoredTableCellRenderer() {
        private var usage: Double? = null

        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val cell = value as? QuartusReportDocument.Cell ?: return
            val model = grid.convertColumnIndexToModel(column)
            ipad = JBUI.insets(1, CELL_PADDING + INDENT * (if (model == 0) cell.indent else 0), 1, CELL_PADDING)
            setTextAlign(if (model in numeric) SwingConstants.RIGHT else SwingConstants.LEFT)
            val columnName = this@TableView.table.columns.getOrNull(model).orEmpty()
            val attributes = when (QuartusReportValues.verdict(cell.text, columnName, this@TableView.table.title)) {
                QuartusReportValues.Verdict.BAD -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getErrorForeground())
                QuartusReportValues.Verdict.GOOD -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, QuartusReportPanel.SUCCESS)
                null -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            QuartusMessageCell.appendHighlighted(this, cell.text, highlight, attributes)
            usage = QuartusReportValues.usage(cell.text)
            toolTipText = cell.text.takeIf { it.length > TOOLTIP_LENGTH }
        }

        /** A small gauge after the text: how much of the resource is used. */
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val usage = usage ?: return
            val gaugeWidth = JBUI.scale(GAUGE_WIDTH)
            val left = minOf(preferredSize.width + JBUI.scale(GAUGE_GAP) - JBUI.scale(CELL_PADDING), width - gaugeWidth - JBUI.scale(CELL_PADDING))
            if (left < JBUI.scale(CELL_PADDING)) return
            val height = JBUI.scale(GAUGE_HEIGHT)
            val top = (this.height - height) / 2
            val arc = height
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = USAGE_TRACK
                g2.fillRoundRect(left, top, gaugeWidth, height, arc, arc)
                g2.color = when {
                    usage >= FULL -> UIUtil.getErrorForeground()
                    usage >= HIGH -> USAGE_HIGH
                    else -> USAGE
                }
                val filled = if (usage > 0) maxOf((gaugeWidth * usage).toInt(), height) else 0
                if (filled > 0) g2.fillRoundRect(left, top, filled, height, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        const val NUMERIC_SHARE = 0.8
        const val SAMPLED_ROWS = 500
        const val CELL_PADDING = 6
        const val INDENT = 4
        const val SORT_ICON = 16
        const val MAX_COLUMN = 480
        const val TOOLTIP_LENGTH = 60
        const val GAUGE_WIDTH = 48
        const val GAUGE_HEIGHT = 6
        const val GAUGE_GAP = 10
        const val HIGH = 0.75
        const val FULL = 0.9
        val USAGE: Color = JBColor(0x4083C9, 0x548AF7)
        val USAGE_HIGH: Color = JBColor(0xE08855, 0xE08855)
        val USAGE_TRACK: Color = JBColor(Color(0, 0, 0, 28), Color(255, 255, 255, 36))
    }
}

/** Text in the editor's font, with what the filter finds marked and scrolled to. */
private class TextView(project: Project, private val text: QuartusReportDocument.Text, showLine: (Int) -> Unit) : SectionView(text, showLine) {
    private val area = JBTextArea(text.text).apply {
        isEditable = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(4, 12)
        caretPosition = 0
    }

    override val body: JComponent = JPanel(BorderLayout()).apply {
        add(ScrollPaneFactory.createScrollPane(area, true), BorderLayout.CENTER)
        val links = files(text.text).filter { Files.isRegularFile(it) }
        if (links.isNotEmpty()) {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(4))).apply {
                links.forEach { path -> add(ActionLink("Open ${path.fileName}") { QuartusFiles.open(project, path) }) }
            }, BorderLayout.SOUTH)
        }
    }

    override fun applyFilter(text: String) {
        val highlighter = area.highlighter
        highlighter.removeAllHighlights()
        if (text.isEmpty()) {
            caption.text = ""
            return
        }
        val scheme = EditorColorsManager.getInstance().globalScheme
        val painter = DefaultHighlighter.DefaultHighlightPainter(scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor ?: JBColor.YELLOW)
        var at = this.text.text.indexOf(text, ignoreCase = true)
        var first = -1
        var count = 0
        while (at >= 0) {
            highlighter.addHighlight(at, at + text.length, painter)
            if (first < 0) first = at
            count++
            at = this.text.text.indexOf(text, at + text.length, ignoreCase = true)
        }
        caption.text = YosysSynthesis.plural(count, "match")
        if (first >= 0) {
            area.caretPosition = first
            runCatching { area.modelToView2D(first)?.bounds?.let { area.scrollRectToVisible(it) } }
        }
    }

    private companion object {
        /** `The pin-out file can be found in C:/project/output_files/blinky.pin.` */
        val FOUND_IN = Regex("""can be found in (\S.*?)\.?\s*$""", RegexOption.MULTILINE)

        fun files(text: String): List<Path> =
            FOUND_IN.findAll(text).mapNotNull { runCatching { Path.of(it.groupValues[1].trim()) }.getOrNull() }.toList()
    }
}

/**
 * The messages of the tool, with what it added to each below it, filtered by severity. Information is
 * hidden to begin with when there is anything more serious.
 */
private class MessagesView(
    private val project: Project,
    private val file: VirtualFile,
    private val messages: QuartusReportDocument.Messages,
    showLine: (Int) -> Unit,
) : SectionView(messages, showLine) {
    private var highlight = ""
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val shown = mutableSetOf(QuartusSeverity.ERROR, QuartusSeverity.CRITICAL_WARNING, QuartusSeverity.WARNING)

    init {
        val serious = listOf(QuartusSeverity.ERROR, QuartusSeverity.CRITICAL_WARNING, QuartusSeverity.WARNING).sumOf { messages.count(it) }
        if (serious == 0) shown += QuartusSeverity.INFO
    }

    private val boxes = mutableMapOf<QuartusSeverity, JBCheckBox>()

    /** A check box for each severity, with its icon and how many messages it has. */
    private val toggles = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
        border = JBUI.Borders.empty(0, 8, 6, 8)
        for ((severity, noun) in listOf(
            QuartusSeverity.ERROR to "Errors", QuartusSeverity.CRITICAL_WARNING to "Critical warnings",
            QuartusSeverity.WARNING to "Warnings", QuartusSeverity.INFO to "Info",
        )) {
            val box = JBCheckBox("", severity in shown).apply {
                addActionListener {
                    if (isSelected) shown += severity else shown -= severity
                    rebuild()
                }
            }
            boxes[severity] = box
            val label = JBLabel("$noun (${messages.count(severity)})", QuartusMessageCell.icon(severity), SwingConstants.LEFT)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = box.doClick()
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
                add(box)
                add(label)
            })
        }
    }

    override val body: JComponent = JPanel(BorderLayout()).apply {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                val message = ((value as? DefaultMutableTreeNode)?.userObject as? Pair<*, *>)?.first as? QuartusMessage ?: return
                QuartusMessageCell.render(this, message, highlight)
            }
        }
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigate()
        }.installOn(tree)
        tree.registerKeyboardAction({ navigate() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(GoToSource(), CopyMessage(), ShowMessage()), "QuartusReportMessages")
        add(toggles, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
    }

    init {
        rebuild()
    }

    override fun applyFilter(text: String) {
        highlight = text
        rebuild()
    }

    private fun visible(severity: QuartusSeverity): Boolean =
        (if (severity == QuartusSeverity.EXTRA_INFO) QuartusSeverity.INFO else severity) in shown

    private fun rebuild() {
        root.removeAllChildren()
        var parent: DefaultMutableTreeNode? = null
        var hidden = 0
        for (entry in messages.messages) {
            val message = entry.first
            if (message.depth == 0) {
                val matches = highlight.isEmpty() || message.text.contains(highlight, ignoreCase = true) ||
                    message.file?.contains(highlight, ignoreCase = true) == true || message.code == highlight
                parent = if (visible(message.severity) && matches) DefaultMutableTreeNode(entry).also { root.add(it) } else null
                if (parent == null) hidden++
            } else {
                parent?.add(DefaultMutableTreeNode(entry))
            }
        }
        model.reload()
        for (node in root.children().asSequence().filterIsInstance<DefaultMutableTreeNode>()) {
            @Suppress("UNCHECKED_CAST")
            val message = (node.userObject as Pair<QuartusMessage, Int>).first
            if (message.severity == QuartusSeverity.ERROR || highlight.isNotEmpty()) tree.expandPath(TreePath(node.path))
        }
        tree.emptyText.clear()
        tree.emptyText.text = if (messages.messages.isEmpty()) "No messages" else "No messages are shown"
        if (hidden > 0 && QuartusSeverity.INFO !in shown && highlight.isEmpty()) {
            tree.emptyText.appendSecondaryText("Show information", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                boxes[QuartusSeverity.INFO]?.doClick()
            }
        }
        val count = root.childCount
        caption.text = YosysSynthesis.plural(count, "message") + " shown" +
            (if (hidden > 0) ", $hidden hidden" else "")
    }

    private fun selected(): Pair<QuartusMessage, Int>? {
        @Suppress("UNCHECKED_CAST")
        return (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Pair<QuartusMessage, Int>
    }

    /** The source file of [message]: as it is given when absolute, else next to the project the report is of. */
    private fun source(message: QuartusMessage): Path? {
        val name = message.file ?: return null
        val path = runCatching { Path.of(name) }.getOrNull() ?: return null
        if (path.isAbsolute) return path
        val reports = runCatching { Path.of(file.path).parent }.getOrNull() ?: return null
        return listOfNotNull(reports.parent, reports).map { it.resolve(path).normalize() }.firstOrNull { Files.isRegularFile(it) }
    }

    private fun navigate(): Boolean {
        val message = selected()?.first ?: return false
        val path = source(message) ?: return false
        QuartusFiles.open(project, path, message.line)
        return true
    }

    private inner class GoToSource : DumbAwareAction("Go to Source", "Open the file and line the message is about", AllIcons.Actions.EditSource) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.first?.let { source(it) } != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            navigate()
        }
    }

    private inner class CopyMessage : DumbAwareAction("Copy", "Copy the message", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val message = selected()?.first ?: return
            val text = buildString {
                append(message.severity.label)
                message.code?.let { append(" ($it)") }
                append(": ").append(message.text)
                message.file?.let { append(" File: ").append(it) }
                message.line?.let { append(" Line: ").append(it) }
            }
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
    }

    private inner class ShowMessage : DumbAwareAction("Show in Text", "Show this message in the text of the report", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selected()?.let { showLine(it.second) }
        }
    }
}

/** How the viewer reads the values of a report: as numbers to sort by, resources used, failures and successes. */
internal object QuartusReportValues {
    enum class Verdict { GOOD, BAD }

    /** A number at the start, followed by a unit, a percent sign or a part in parentheses; not by letters, as in `25.1std`. */
    private val NUMBER = Regex("""^[<>~]?\s*(-?[\d,]*\.?\d+)(?=$|\s|%|\()""")
    private val TIME = Regex("""^(\d+):(\d{2}):(\d{2})$""")
    /** `9,993 / 49,760 ( 20 % )` as Quartus writes it, or `45/8640` as GOWIN EDA does, with the percentage in a column of its own. */
    private val USAGE = Regex("""^([\d,]+)\s*/\s*([\d,]+)\s*(?:\(|$)""")

    /** The number a value starts with: `9993 (893)`, `54.45 MHz`, `25.8%`, `-1.250`; times as seconds. */
    fun number(value: String): Double? {
        val text = value.trim()
        TIME.matchEntire(text)?.let { time ->
            val (hours, minutes, seconds) = time.destructured
            return hours.toDouble() * 3600 + minutes.toDouble() * 60 + seconds.toDouble()
        }
        val match = NUMBER.find(text) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    /** How much of a resource `9,993 / 49,760 ( 20 % )` uses, from 0 to 1. */
    fun usage(value: String): Double? {
        val match = USAGE.find(value.trim()) ?: return null
        val used = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val total = match.groupValues[2].replace(",", "").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return (used / total).coerceIn(0.0, 1.0)
    }

    /**
     * Whether [value], in the column called [column] of the table called [table], is a failure or a success worth
     * marking; null for most values. Negative slack fails, in the columns of slack and in the summary of timing.
     */
    fun verdict(value: String, column: String, table: String = ""): Verdict? {
        val text = value.trim()
        val slack = column.contains("Slack") || column.contains("TNS") || table.contains("Timing Analysis Summary")
        return when {
            text.startsWith("Successful") || text.startsWith("Timing requirements met") -> Verdict.GOOD
            text == "Fail" || text.startsWith("Failed") || text.startsWith("Unsuccessful") || text.startsWith("Error") ||
                text.startsWith("Timing requirements not met") -> Verdict.BAD
            slack && (number(text) ?: 0.0) < 0 -> Verdict.BAD
            else -> null
        }
    }

    /** Numbers by their value, before text; empty cells last. */
    val COMPARATOR: Comparator<Any?> = Comparator { a, b ->
        val left = (a as? QuartusReportDocument.Cell)?.text.orEmpty()
        val right = (b as? QuartusReportDocument.Cell)?.text.orEmpty()
        when {
            left.isEmpty() || right.isEmpty() -> right.length.coerceAtMost(1) - left.length.coerceAtMost(1)
            else -> {
                val x = number(left)
                val y = number(right)
                when {
                    x != null && y != null -> x.compareTo(y).takeIf { it != 0 } ?: left.compareTo(right, ignoreCase = true)
                    x != null -> -1
                    y != null -> 1
                    else -> left.compareTo(right, ignoreCase = true)
                }
            }
        }
    }
}
