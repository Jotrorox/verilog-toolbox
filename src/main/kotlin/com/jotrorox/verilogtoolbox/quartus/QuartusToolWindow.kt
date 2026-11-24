package com.jotrorox.verilogtoolbox.quartus

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.io.path.name

/**
 * The Quartus tool window: what Quartus's own window shows of a project that is worth having next to
 * the editor. An overview with the device, the steps of the last compilation, its resources and timing; the
 * pin assignments; and the messages, sorted by how serious they are.
 */
class QuartusToolWindowFactory : ToolWindowFactory, DumbAware {
    /** Shown only for projects with a Quartus project; [QuartusToolWindow.updateAvailability] follows what is found. */
    override fun shouldBeAvailable(project: Project): Boolean = QuartusProjectService.getInstance(project).known.isNotEmpty()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val overview = QuartusOverviewPanel(project)
        val messages = QuartusMessagesPanel(project)
        val contents = toolWindow.contentManager
        val factory = ContentFactory.getInstance()
        contents.addContent(factory.createContent(overview, QuartusToolWindow.OVERVIEW, false).apply { isCloseable = false })
        contents.addContent(
            factory.createContent(QuartusPinsPanel(project, toolWindow.disposable), QuartusToolWindow.PINS, false).apply { isCloseable = false }
        )
        contents.addContent(factory.createContent(messages, QuartusToolWindow.MESSAGES, false).apply { isCloseable = false })

        val loader = QuartusSnapshotLoader(project) { snapshot ->
            overview.show(snapshot)
            messages.show(snapshot)
        }
        project.messageBus.connect(toolWindow.disposable).subscribe(QuartusProjectListener.TOPIC, QuartusProjectListener { loader.load() })
        loader.load()
    }
}

object QuartusToolWindow {
    const val ID = "Quartus"

    /** Shows the tool window's button while the project has a Quartus project, and only then. */
    fun updateAvailability(project: Project) {
        val available = QuartusProjectService.getInstance(project).known.isNotEmpty()
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.let { if (it.isAvailable != available) it.isAvailable = available }
        }, project.disposed)
    }

    const val OVERVIEW = "Overview"
    const val PINS = "Pins"
    const val MESSAGES = "Messages"

    /** Opens the tool window, on the tab called [tab] when one is given. */
    fun show(project: Project, tab: String? = null) {
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.let { toolWindow ->
                toolWindow.isAvailable = true
                // Selected before the window is activated: an active one does not call back.
                val contents = toolWindow.contentManager
                contents.contents.firstOrNull { it.displayName == tab }?.let { contents.setSelectedContent(it, true) }
                toolWindow.activate(null)
            }
        }, project.disposed)
    }
}

/** Everything the tool window shows, read off the UI thread in one go. */
internal class QuartusSnapshot(
    val projects: List<QuartusProject>,
    val revision: QuartusRevision?,
    val installation: QuartusInstallation?,
    val compilation: QuartusCompilation?,
    /** Whether a source or the settings changed after the last compilation was reported. */
    val outOfDate: Boolean,
    val running: QuartusFlow?,
    val stage: String?,
    /** The flow whose run the messages are of, or null when they were read from the reports. */
    val messagesOf: QuartusFlow?,
    val messages: List<QuartusMessage>,
)

/** Loads snapshots on a background thread, one at a time, and hands each to the UI thread. */
internal class QuartusSnapshotLoader(private val project: Project, private val show: (QuartusSnapshot) -> Unit) {
    private val loading = AtomicBoolean()
    private val again = AtomicBoolean()

    /** What the reports said when they were last read, kept while they do not change: they can be megabytes. */
    private var reports: Triple<List<Long>, QuartusCompilation, List<QuartusMessage>>? = null

    fun load() {
        if (!loading.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (project.isDisposed) return@execute
                val snapshot = read()
                ApplicationManager.getApplication().invokeLater({ show(snapshot) }, project.disposed)
            } finally {
                loading.set(false)
                if (again.compareAndSet(true, false)) load()
            }
        }
    }

    private fun read(): QuartusSnapshot {
        val service = QuartusProjectService.getInstance(project)
        val runs = QuartusRuns.getInstance(project)
        val revision = service.revision
        var compilation: QuartusCompilation? = null
        var fromReports = emptyList<QuartusMessage>()
        var outOfDate = false
        if (revision != null) {
            val files = (QuartusCompilation.REPORTS.map { it.second } + listOf("fit.summary", "map.summary", "sta.summary", "sof", "pof"))
                .map { revision.output(it) }
            val stamps = files.map { stamp(it) }
            val known = reports?.takeIf { it.first == stamps && it.second.settingsFile == revision.settingsFile }
            if (known != null) {
                compilation = known.second
                fromReports = known.third
            } else {
                compilation = QuartusCompilation(revision)
                fromReports = QuartusCompilation.messages(revision)
                reports = Triple(stamps, compilation, fromReports)
            }
            val reported = stamp(revision.output("flow.rpt"))
            outOfDate = reported > 0 && (listOf(revision.settingsFile) + revision.files.map { it.path }).any { stamp(it) > reported }
        }
        val (flow, runMessages) = revision?.let { runs.lastRun(it) } ?: (null to null)
        return QuartusSnapshot(
            service.available, revision, Quartus.find(), compilation, outOfDate, runs.running, runs.stage,
            flow, runMessages ?: fromReports,
        )
    }

    private fun stamp(file: Path): Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0)
}

/** The Overview tab: the toolbar that runs the flows, and what there is to know about the project at a glance. */
internal class QuartusOverviewPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val content = JPanel(BorderLayout())

    init {
        val group = ActionManager.getInstance().getAction("VerilogToolbox.Quartus.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("QuartusToolWindow", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(content, true))
    }

    fun show(snapshot: QuartusSnapshot) {
        content.removeAll()
        content.add(render(snapshot), BorderLayout.NORTH)
        content.revalidate()
        content.repaint()
    }

    private fun render(snapshot: QuartusSnapshot): JComponent {
        val revision = snapshot.revision ?: return panel {
            row { label("This project has no Quartus project.") }
            row { link("New Quartus Project…") { perform("VerilogToolbox.Quartus.NewProject") } }
        }.withBorder()
        val service = QuartusProjectService.getInstance(project)
        return panel {
            row("Project:") {
                if (snapshot.projects.size > 1) {
                    comboBox(snapshot.projects, textListCellRenderer<QuartusProject?> { it?.file?.name }).applyToComponent {
                        selectedItem = revision.project
                        addActionListener { (selectedItem as? QuartusProject)?.takeIf { it != revision.project }?.let { service.select(it) } }
                    }
                } else {
                    link(revision.project.file.name) { QuartusFiles.open(project, revision.settingsFile) }
                        .applyToComponent { toolTipText = "Open the settings file ${revision.settingsFile.name}" }
                }
            }
            if (revision.project.revisions.size > 1) {
                row("Revision:") {
                    comboBox(revision.project.revisions).applyToComponent {
                        selectedItem = revision.name
                        addActionListener { (selectedItem as? String)?.takeIf { it != revision.name }?.let { service.select(revision.project, it) } }
                    }
                }
            }
            row("Device:") {
                val device = listOfNotNull(revision.family, revision.device).joinToString(" · ").ifEmpty { "Not set" }
                link(device) { perform("VerilogToolbox.Quartus.ChangeDevice") }.applyToComponent { toolTipText = "Change the device" }
            }
            row("Top level:") {
                link(revision.topLevelEntity) { openTopLevel(revision) }.applyToComponent { toolTipText = "Go to the top-level entity" }
            }
            row("Sources:") {
                val sources = revision.files.count { it.kind in DESIGN_KINDS }
                val constraints = revision.files.count { it.kind == QuartusFileKind.SDC }
                label(YosysSynthesis.plural(sources, "design file") + ", " + YosysSynthesis.plural(constraints, "constraint file"))
            }
            if (snapshot.installation == null) {
                row("Quartus:") {
                    icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                    link("Not found. Configure…") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
                    }
                }
            }
            if (constraintsMissing(revision)) {
                row {
                    icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                    label("No timing constraints (.sdc): timing is not checked.")
                        .applyToComponent { toolTipText = "Add an .sdc file with a create_clock for each clock to the project." }
                }
            }
            separator()
            status(snapshot)
            snapshot.compilation?.let { compilation ->
                steps(snapshot, compilation)
                timing(compilation)
                resources(compilation)
                output(compilation)
            }
        }.withBorder()
    }

    private fun Panel.status(snapshot: QuartusSnapshot) {
        val status = snapshot.compilation?.status
        row {
            when {
                snapshot.running != null -> {
                    icon(AnimatedIcon.Default.INSTANCE).gap(RightGap.SMALL)
                    label("Running ${snapshot.running.title}" + (snapshot.stage?.let { ": $it" } ?: "") + "…").bold()
                }
                status == null -> {
                    icon(AllIcons.General.Information).gap(RightGap.SMALL)
                    label("Not compiled yet.")
                }
                else -> {
                    val successful = status.startsWith("Successful")
                    icon(if (successful) AllIcons.General.InspectionsOK else AllIcons.General.Error).gap(RightGap.SMALL)
                    label(if (successful) "Compiled" else status.substringBefore(" - ")).bold().gap(RightGap.SMALL)
                    comment(status.substringAfter(" - ", ""))
                }
            }
        }
        if (snapshot.running == null && status != null && snapshot.outOfDate) {
            row {
                icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                label("The sources or settings changed since.").gap(RightGap.SMALL)
                link("Compile") { perform("VerilogToolbox.Quartus.Compile") }
            }
        }
    }

    private fun Panel.steps(snapshot: QuartusSnapshot, compilation: QuartusCompilation) {
        if (compilation.steps.isEmpty()) return
        val failed = compilation.status?.startsWith("Successful") == false
        group("Flow") {
            compilation.steps.forEachIndexed { index, step ->
                row {
                    // A flow that failed ends with the step that failed.
                    val isFailed = failed && index == compilation.steps.lastIndex
                    icon(if (isFailed) AllIcons.General.Error else AllIcons.Actions.Checked).gap(RightGap.SMALL)
                    label(step.name).gap(RightGap.SMALL)
                    comment(step.elapsed)
                }
            }
        }
    }

    private fun Panel.timing(compilation: QuartusCompilation) {
        if (compilation.slacks.isEmpty()) return
        val failing = compilation.failingSlacks
        group("Timing") {
            row {
                icon(if (failing.isEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Error).gap(RightGap.SMALL)
                label(
                    if (failing.isEmpty()) "Timing requirements are met."
                    else "Timing requirements are not met: ${YosysSynthesis.plural(failing.size, "check")} with negative slack."
                )
            }
            for (fmax in compilation.worstFmax) {
                // A clock of a PLL is named by its whole hierarchy, which would push the values out of the window.
                row(plain("Fmax ${shortClock(fmax.clock)}:")
                    .apply { toolTipText = fmax.clock }) {
                    label("${fmax.restricted} MHz").applyToComponent { toolTipText = "The lowest over the operating corners, here ${fmax.corner}" }
                }
            }
            for (slack in compilation.worstSlacks) {
                row(plain("${slack.check} slack:")) {
                    label("${slack.slack} ns").gap(RightGap.SMALL).applyToComponent {
                        if (slack.slack < 0) foreground = UIUtil.getErrorForeground()
                    }
                    comment("${slack.clock}, ${slack.corner}")
                }
            }
            row { link("Timing report") { QuartusFiles.open(project, compilation.report("sta.rpt") ?: return@link) } }
        }
    }

    /**
     * A row label for text out of a report. Given as a string, an `&` in it would mark a mnemonic and be left out,
     * as in "Analysis & Elaboration Status".
     */
    private fun plain(text: String): JBLabel = JBLabel(text)

    private fun Panel.resources(compilation: QuartusCompilation) {
        if (compilation.resources.isEmpty()) return
        group("Resources") {
            for ((name, value) in compilation.resources) {
                // A part of the line above it, such as the registers among the logic elements, is set in.
                if (name.startsWith(" ")) indent { row(plain(name.trim() + ":")) { label(value) }.layout(RowLayout.LABEL_ALIGNED) }
                else row(plain("$name:")) { label(value) }
            }
        }
    }

    private fun Panel.output(compilation: QuartusCompilation) {
        if (compilation.programmingFiles.isEmpty() && compilation.reports.isEmpty()) return
        group("Output") {
            if (compilation.programmingFiles.isNotEmpty()) {
                row("Programming files:") {
                    label(compilation.programmingFiles.joinToString(", ") { it.name })
                        .applyToComponent { toolTipText = compilation.programmingFiles.first().parent?.toString() }
                }
            }
            row("Reports:") {
                for ((title, file) in compilation.reports) {
                    link(title) { QuartusFiles.open(project, file) }.gap(RightGap.SMALL)
                }
            }.apply { if (compilation.reports.isEmpty()) visible(false) }
        }
    }

    /** A design that Quartus places and routes without constraints is not checked against anything. */
    private fun constraintsMissing(revision: QuartusRevision): Boolean =
        revision.files.none { it.kind == QuartusFileKind.SDC } &&
            !Files.isRegularFile(revision.project.directory.resolve(revision.name + ".sdc"))

    private fun openTopLevel(revision: QuartusRevision) {
        AppExecutorUtil.getAppExecutorService().execute {
            val found = revision.verilogSources.firstNotNullOfOrNull { file ->
                val text = runCatching { Files.readString(file) }.getOrNull() ?: return@firstNotNullOfOrNull null
                VerilogModuleHeaders.modules(text).firstOrNull { it.name == revision.topLevelEntity }
                    ?.let { file to text.substring(0, it.nameOffset).count { c -> c == '\n' } + 1 }
            }
            ApplicationManager.getApplication().invokeLater({
                if (found != null) QuartusFiles.open(project, found.first, found.second)
                else QuartusFiles.open(project, revision.settingsFile)
            }, project.disposed)
        }
    }

    private fun perform(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        ActionManager.getInstance().tryToExecute(action, null, this, "QuartusToolWindow", true)
    }

    private fun JComponent.withBorder(): JComponent = apply { border = JBUI.Borders.empty(8, 12) }

    private companion object {
        val DESIGN_KINDS = setOf(
            QuartusFileKind.SYSTEMVERILOG, QuartusFileKind.VERILOG, QuartusFileKind.VHDL, QuartusFileKind.BLOCK_DESIGN,
            QuartusFileKind.QIP, QuartusFileKind.QSYS, QuartusFileKind.IP,
        )
        const val CLOCK_NAME_LENGTH = 24

        /** The end of a long clock name, which is the part that tells the clocks of one PLL apart. */
        fun shortClock(name: String): String =
            if (name.length <= CLOCK_NAME_LENGTH) name else "…" + name.takeLast(CLOCK_NAME_LENGTH - 1)
    }
}

/**
 * The Messages tab: the messages of the last run as a tree, each with what the tool added to it below,
 * filtered by severity. Information is hidden to begin with: a compilation prints hundreds of those.
 */
internal class QuartusMessagesPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val summary = JBLabel()
    private val shown = mutableSetOf(QuartusSeverity.ERROR, QuartusSeverity.CRITICAL_WARNING, QuartusSeverity.WARNING)
    private var snapshot: QuartusSnapshot? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "No messages"
        tree.cellRenderer = Renderer()
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigate()
        }.installOn(tree)
        tree.registerKeyboardAction({ navigate() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)

        val group = DefaultActionGroup(
            Toggle(QuartusSeverity.ERROR, "Errors", AllIcons.General.Error),
            Toggle(QuartusSeverity.CRITICAL_WARNING, "Critical Warnings", AllIcons.General.BalloonWarning),
            Toggle(QuartusSeverity.WARNING, "Warnings", AllIcons.General.Warning),
            Toggle(QuartusSeverity.INFO, "Information", AllIcons.General.Information),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("QuartusMessages", group, false)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        summary.border = JBUI.Borders.empty(4, 8)
        summary.foreground = UIUtil.getContextHelpForeground()
        val panel = JPanel(BorderLayout())
        panel.add(summary, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        setContent(panel)
    }

    fun show(snapshot: QuartusSnapshot) {
        this.snapshot = snapshot
        rebuild()
    }

    private fun rebuild() {
        val snapshot = snapshot ?: return
        val messages = snapshot.messages
        root.removeAllChildren()
        var parent: DefaultMutableTreeNode? = null
        for (message in messages) {
            if (message.depth == 0) {
                parent = if (visible(message.severity)) DefaultMutableTreeNode(message).also { root.add(it) } else null
            } else {
                // What a tool adds to a message is shown with it, whatever its own severity.
                parent?.add(DefaultMutableTreeNode(message))
            }
        }
        model.reload()
        val top = messages.filter { it.depth == 0 }
        val counts = listOf(
            QuartusSeverity.ERROR to "error", QuartusSeverity.CRITICAL_WARNING to "critical warning", QuartusSeverity.WARNING to "warning",
        ).map { (severity, noun) -> YosysSynthesis.plural(top.count { it.severity == severity }, noun) }
        val source = when {
            snapshot.revision == null -> ""
            snapshot.running != null -> "Running ${snapshot.running.title}…"
            messages.isEmpty() -> "Not compiled yet."
            snapshot.messagesOf != null -> "${snapshot.messagesOf.title}: "
            else -> "Last compilation: "
        }
        summary.text = if (messages.isEmpty() || snapshot.running != null) source else source + counts.joinToString(", ")
        // Errors are few and what one looks for; they are shown with their details.
        for (node in root.children().asSequence().filterIsInstance<DefaultMutableTreeNode>()) {
            if ((node.userObject as? QuartusMessage)?.severity == QuartusSeverity.ERROR) tree.expandPath(TreePath(node.path))
        }
    }

    /** Extra information is detail of the same kind as information, and follows its switch. */
    private fun visible(severity: QuartusSeverity): Boolean =
        (if (severity == QuartusSeverity.EXTRA_INFO) QuartusSeverity.INFO else severity) in shown

    private fun navigate(): Boolean {
        val message = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? QuartusMessage ?: return false
        val directory = snapshot?.revision?.project?.directory ?: return false
        val file = message.file?.let { runCatching { directory.resolve(it).normalize() }.getOrNull() } ?: return false
        QuartusFiles.open(project, file, message.line)
        return true
    }

    private inner class Toggle(private val severity: QuartusSeverity, text: String, icon: Icon) :
        ToggleAction("Show $text", null, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = severity in shown
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) shown += severity else shown -= severity
            rebuild()
        }
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val message = (value as? DefaultMutableTreeNode)?.userObject as? QuartusMessage ?: return
            icon = when (message.severity) {
                QuartusSeverity.ERROR -> AllIcons.General.Error
                QuartusSeverity.CRITICAL_WARNING -> AllIcons.General.BalloonWarning
                QuartusSeverity.WARNING -> AllIcons.General.Warning
                QuartusSeverity.INFO, QuartusSeverity.EXTRA_INFO -> AllIcons.General.Information
            }
            append(message.text)
            message.file?.let { file ->
                val name = file.replace('\\', '/').substringAfterLast('/')
                append("  $name" + (message.line?.let { ":$it" } ?: ""), SimpleTextAttributes.LINK_ATTRIBUTES)
            }
            message.code?.let { append("  ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            toolTipText = message.text
        }
    }
}
