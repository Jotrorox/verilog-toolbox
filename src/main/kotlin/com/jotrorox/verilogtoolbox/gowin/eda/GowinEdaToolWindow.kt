package com.jotrorox.verilogtoolbox.gowin.eda

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
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.io.path.name

/**
 * The GOWIN EDA tool window: what GOWIN EDA's own window shows of a project that is worth having next to the
 * editor. An overview with the device, the steps of the last run, its resources and timing; the pins; and the
 * messages, sorted by how serious they are.
 */
class GowinEdaToolWindowFactory : ToolWindowFactory, DumbAware {
    /** Shown only for projects with a GOWIN EDA project; [GowinEdaToolWindow.updateAvailability] follows what is found. */
    override fun shouldBeAvailable(project: Project): Boolean = GowinEdaProjectService.getInstance(project).known.isNotEmpty()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val overview = GowinEdaOverviewPanel(project)
        val messages = GowinEdaMessagesPanel(project)
        val contents = toolWindow.contentManager
        val factory = ContentFactory.getInstance()
        contents.addContent(factory.createContent(overview, GowinEdaToolWindow.OVERVIEW, false).apply { isCloseable = false })
        contents.addContent(factory.createContent(GowinEdaPinsPanel(project, toolWindow.disposable), GowinEdaToolWindow.PINS, false).apply { isCloseable = false })
        contents.addContent(factory.createContent(messages, GowinEdaToolWindow.MESSAGES, false).apply { isCloseable = false })

        val loader = GowinEdaSnapshotLoader(project) { snapshot ->
            overview.show(snapshot)
            messages.show(snapshot)
        }
        project.messageBus.connect(toolWindow.disposable).subscribe(GowinEdaProjectListener.TOPIC, GowinEdaProjectListener { loader.load() })
        loader.load()
    }
}

object GowinEdaToolWindow {
    const val ID = "GOWIN EDA"

    const val OVERVIEW = "Overview"
    const val PINS = "Pins"
    const val MESSAGES = "Messages"

    /** Shows the tool window's button while the project has a GOWIN EDA project, and only then. */
    fun updateAvailability(project: Project) {
        val available = GowinEdaProjectService.getInstance(project).known.isNotEmpty()
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.let { if (it.isAvailable != available) it.isAvailable = available }
        }, project.disposed)
    }

    /** Opens the tool window, on the tab called [tab] when one is given. */
    fun show(project: Project, tab: String? = null) {
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.let { toolWindow ->
                toolWindow.isAvailable = true
                val contents = toolWindow.contentManager
                contents.contents.firstOrNull { it.displayName == tab }?.let { contents.setSelectedContent(it, true) }
                toolWindow.activate(null)
            }
        }, project.disposed)
    }
}

/** Everything the tool window shows, read off the UI thread in one go. */
internal class GowinEdaSnapshot(
    val projects: List<Path>,
    val project: GowinEdaProject?,
    val installation: GowinEdaInstallation?,
    val part: GowinEdaPart?,
    val compilation: GowinEdaCompilation?,
    /** Whether a source, the constraints or the project changed after the last run wrote its logs. */
    val outOfDate: Boolean,
    val running: GowinEdaFlow?,
    val stage: String?,
    /** The flow whose run the messages are of, or null when they were read from the logs. */
    val messagesOf: GowinEdaFlow?,
    val messages: List<GowinEdaMessage>,
)

/** Loads snapshots on a background thread, one at a time, and hands each to the UI thread. */
internal class GowinEdaSnapshotLoader(private val project: Project, private val show: (GowinEdaSnapshot) -> Unit) {
    private val loading = AtomicBoolean()
    private val again = AtomicBoolean()

    /** What the reports said when they were last read, kept while they do not change. */
    private var reports: Pair<List<Long>, GowinEdaCompilation>? = null

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

    private fun read(): GowinEdaSnapshot {
        val service = GowinEdaProjectService.getInstance(project)
        val runs = GowinEdaRuns.getInstance(project)
        val projects = service.available
        val gowinProject = service.gowinProject
        // Working out the top module reads the sources, which the UI thread must not wait for.
        gowinProject?.topModule
        var compilation: GowinEdaCompilation? = null
        var outOfDate = false
        if (gowinProject != null) {
            val stamps = GowinEdaCompilation.watched(gowinProject).map { stamp(it) }
            val known = reports?.takeIf { it.first == stamps && it.second.projectFile == gowinProject.file }
            compilation = known?.second ?: GowinEdaCompilation(gowinProject).also { reports = stamps to it }
            val ran = maxOf(stamp(gowinProject.synthesisOutput("log")), stamp(gowinProject.pnrOutput("log")))
            outOfDate = ran > 0 && (listOf(gowinProject.file) + gowinProject.enabledFiles.map { it.path }).any { stamp(it) > ran }
        }
        val installation = GowinEda.find()
        val part = GowinEdaDevices.part(GowinEdaDevices.catalog(installation), gowinProject?.device)
        val (flow, runMessages) = gowinProject?.let { runs.lastRun(it) } ?: (null to null)
        return GowinEdaSnapshot(
            projects, gowinProject, installation, part, compilation, outOfDate, runs.running, runs.stage,
            flow, runMessages ?: compilation?.messages.orEmpty(),
        )
    }

    private fun stamp(file: Path): Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0)
}

/** The Overview tab: the toolbar that runs the flow, and what there is to know about the project at a glance. */
internal class GowinEdaOverviewPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val content = JPanel(BorderLayout())

    init {
        val group = ActionManager.getInstance().getAction("VerilogToolbox.GowinEda.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("GowinEdaToolWindow", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(content, true))
    }

    fun show(snapshot: GowinEdaSnapshot) {
        content.removeAll()
        content.add(render(snapshot), BorderLayout.NORTH)
        content.revalidate()
        content.repaint()
    }

    private fun render(snapshot: GowinEdaSnapshot): JComponent {
        val gowinProject = snapshot.project ?: return panel {
            row { label("This project has no GOWIN EDA project.") }
            row { link("New GOWIN EDA Project…") { perform("VerilogToolbox.GowinEda.NewProject") } }
        }.withBorder()
        val service = GowinEdaProjectService.getInstance(project)
        return panel {
            row("Project:") {
                if (snapshot.projects.size > 1) {
                    comboBox(snapshot.projects, textListCellRenderer<Path?> { it?.name }).applyToComponent {
                        selectedItem = gowinProject.file
                        addActionListener { (selectedItem as? Path)?.takeIf { it != gowinProject.file }?.let { service.select(it) } }
                    }
                } else {
                    link(gowinProject.file.name) { GowinEdaActions.open(project, gowinProject.file) }
                        .applyToComponent { toolTipText = "Open ${gowinProject.file.name}" }
                }
            }
            row("Device:") {
                val device = gowinProject.device?.toString() ?: "Not set"
                link(device) { perform("VerilogToolbox.GowinEda.ChangeDevice") }.applyToComponent { toolTipText = "Change the device" }
            }
            snapshot.part?.let { part -> row("") { comment(part.summary) } }
            row("Top module:") {
                val top = gowinProject.topModule
                if (top == null) {
                    label("None found").applyToComponent { foreground = UIUtil.getErrorForeground() }
                } else {
                    link(top) { openTopModule(gowinProject, top) }.gap(RightGap.SMALL).applyToComponent { toolTipText = "Go to the top module" }
                    if (!gowinProject.isTopModuleSet) comment("worked out from the sources")
                }
            }
            row("Sources:") {
                val design = gowinProject.enabledFiles.count { it.kind == GowinEdaFileKind.VERILOG || it.kind == GowinEdaFileKind.VHDL || it.kind == GowinEdaFileKind.NETLIST }
                val physical = gowinProject.enabledFiles.count { it.kind == GowinEdaFileKind.CST }
                val timing = gowinProject.enabledFiles.count { it.kind == GowinEdaFileKind.SDC }
                label(YosysSynthesis.plural(design, "design file") + ", " + YosysSynthesis.plural(physical, "pin constraint file") +
                    ", " + YosysSynthesis.plural(timing, "timing constraint file"))
            }
            if (snapshot.installation == null) {
                row("GOWIN EDA:") {
                    icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                    link("Not found. Configure…") { ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java) }
                }
            }
            if (gowinProject.constraintFile == null) {
                row {
                    icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                    label("No pin constraints (.cst): place and route picks the pins.").gap(RightGap.SMALL)
                    link("Assign Pins") { GowinEdaToolWindow.show(project, GowinEdaToolWindow.PINS) }
                }
            }
            if (gowinProject.timingConstraintFiles.isEmpty()) {
                row {
                    icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                    label("No timing constraints (.sdc): timing is checked against the default clock.")
                        .applyToComponent { toolTipText = "Add an .sdc file with a create_clock for each clock to the project." }
                }
            }
            separator()
            status(snapshot)
            snapshot.compilation?.let { compilation ->
                steps(compilation)
                timing(compilation)
                resources(compilation)
                output(compilation)
            }
        }.withBorder()
    }

    private fun Panel.status(snapshot: GowinEdaSnapshot) {
        val status = snapshot.compilation?.status
        row {
            when {
                snapshot.running != null -> {
                    icon(AnimatedIcon.Default.INSTANCE).gap(RightGap.SMALL)
                    label("Running ${snapshot.running.title}" + (snapshot.stage?.let { ": $it" } ?: "") + "…").bold()
                }
                status == null -> {
                    icon(AllIcons.General.Information).gap(RightGap.SMALL)
                    label("Not built yet.")
                }
                status == GowinEdaCompilation.Status.SUCCESSFUL -> {
                    icon(AllIcons.General.InspectionsOK).gap(RightGap.SMALL)
                    label("Built").bold()
                }
                status == GowinEdaCompilation.Status.SYNTHESIZED -> {
                    icon(AllIcons.General.InspectionsOK).gap(RightGap.SMALL)
                    label("Synthesized").bold().gap(RightGap.SMALL)
                    comment("not placed and routed")
                }
                else -> {
                    icon(AllIcons.General.Error).gap(RightGap.SMALL)
                    label("Failed").bold().gap(RightGap.SMALL)
                    val errors = snapshot.compilation.messages.count { it.severity == GowinEdaSeverity.ERROR }
                    if (errors > 0) link(YosysSynthesis.plural(errors, "error")) { GowinEdaToolWindow.show(project, GowinEdaToolWindow.MESSAGES) }
                }
            }
        }
        if (snapshot.running == null && status != null && snapshot.outOfDate) {
            row {
                icon(AllIcons.General.Warning).gap(RightGap.SMALL)
                label("The sources or constraints changed since.").gap(RightGap.SMALL)
                link("Build") { perform("VerilogToolbox.GowinEda.Build") }
            }
        }
    }

    private fun Panel.steps(compilation: GowinEdaCompilation) {
        if (compilation.steps.isEmpty()) return
        group("Flow") {
            for (step in compilation.steps) {
                row {
                    icon(if (step.failed) AllIcons.General.Error else AllIcons.Actions.Checked).gap(RightGap.SMALL)
                    label(step.name).gap(RightGap.SMALL)
                    step.elapsed?.let { comment(it) }
                }
            }
        }
    }

    private fun Panel.timing(compilation: GowinEdaCompilation) {
        val timing = compilation.timing ?: return
        group("Timing") {
            row {
                icon(if (timing.isMet) AllIcons.General.InspectionsOK else AllIcons.General.Error).gap(RightGap.SMALL)
                label(
                    if (timing.isMet) "Timing requirements are met."
                    else "Timing requirements are not met: " +
                        YosysSynthesis.plural((timing.setupViolations ?: 0) + (timing.holdViolations ?: 0), "failing endpoint") + "."
                )
            }
            for (fmax in timing.fmax) {
                row(JBLabel("Fmax ${fmax.clock}:")) {
                    label("%.2f MHz".format(Locale.ROOT, fmax.fmax)).gap(RightGap.SMALL).applyToComponent {
                        if (fmax.constraint != null && fmax.fmax < fmax.constraint) foreground = UIUtil.getErrorForeground()
                    }
                    fmax.constraint?.let { comment("%.2f MHz asked for".format(Locale.ROOT, it)) }
                }
            }
            for (slack in timing.worst) {
                row(JBLabel("${slack.check} slack:")) {
                    label("${slack.slack} ns").gap(RightGap.SMALL).applyToComponent { if (slack.slack < 0) foreground = UIUtil.getErrorForeground() }
                    slack.to?.let { comment("to $it") }
                }
            }
            compilation.reports.firstOrNull { it.first == "Timing Report" }?.let { (_, file) ->
                row { link("Timing report") { openGowinReport(project, file, "Max Frequency Summary") } }
            }
        }
    }

    private fun Panel.resources(compilation: GowinEdaCompilation) {
        if (compilation.resources.isEmpty()) return
        group(if (compilation.estimated) "Resources (synthesis estimate)" else "Resources") {
            for (resource in compilation.resources) {
                val value = resource.usage + (resource.utilization?.let { " ($it)" } ?: "")
                // A part of the line above it, such as the registers used as flip-flops, is set in.
                if (resource.isPart) indent { row(JBLabel(resource.name.trim() + ":")) { label(value) }.layout(RowLayout.LABEL_ALIGNED) }
                else row(JBLabel("${resource.name}:")) { label(value) }
            }
        }
    }

    private fun Panel.output(compilation: GowinEdaCompilation) {
        if (compilation.bitstream == null && compilation.reports.isEmpty()) return
        group("Output") {
            compilation.bitstream?.let { bitstream ->
                row("Bitstream:") {
                    label(bitstream.name).gap(RightGap.SMALL).applyToComponent { toolTipText = bitstream.toString() }
                    link("Program") { perform("VerilogToolbox.GowinEda.Program") }
                }
            }
            if (compilation.reports.isNotEmpty()) {
                // A list wraps, which a row of links in a narrow tool window does not.
                for ((title, file) in compilation.reports) {
                    row { link(title) { GowinEdaActions.open(project, file) }.applyToComponent { toolTipText = file.toString() } }
                }
            }
        }
    }

    private fun openTopModule(gowinProject: GowinEdaProject, top: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            val found = gowinProject.verilogSources.firstNotNullOfOrNull { file ->
                val text = runCatching { Files.readString(file) }.getOrNull() ?: return@firstNotNullOfOrNull null
                VerilogModuleHeaders.modules(text).firstOrNull { it.name == top }
                    ?.let { file to text.substring(0, it.nameOffset).count { c -> c == '\n' } + 1 }
            }
            ApplicationManager.getApplication().invokeLater({
                if (found != null) GowinEdaActions.open(project, found.first, found.second) else GowinEdaActions.open(project, gowinProject.file)
            }, project.disposed)
        }
    }

    private fun perform(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        ActionManager.getInstance().tryToExecute(action, null, this, "GowinEdaToolWindow", true)
    }

    private fun JComponent.withBorder(): JComponent = apply { border = JBUI.Borders.empty(8, 12) }
}

/** The Messages tab: the messages of the last run, filtered by severity. Notes are hidden to begin with. */
internal class GowinEdaMessagesPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    private val listModel = DefaultListModel<GowinEdaMessage>()
    private val list = JBList(listModel)
    private val summary = JBLabel()
    private val shown = mutableSetOf(GowinEdaSeverity.ERROR, GowinEdaSeverity.WARNING)
    private var snapshot: GowinEdaSnapshot? = null

    init {
        list.emptyText.text = "No messages"
        list.cellRenderer = Renderer()
        ListSpeedSearch.installOn(list) { "${it.text} ${it.code.orEmpty()}" }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigate()
        }.installOn(list)
        list.registerKeyboardAction({ navigate() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)

        val group = DefaultActionGroup(
            Toggle(GowinEdaSeverity.ERROR, "Errors", AllIcons.General.Error),
            Toggle(GowinEdaSeverity.WARNING, "Warnings", AllIcons.General.Warning),
            Toggle(GowinEdaSeverity.NOTE, "Notes", AllIcons.General.Information),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("GowinEdaMessages", group, false)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        summary.border = JBUI.Borders.empty(4, 8)
        summary.foreground = UIUtil.getContextHelpForeground()
        val panel = JPanel(BorderLayout())
        panel.add(summary, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        setContent(panel)
    }

    fun show(snapshot: GowinEdaSnapshot) {
        this.snapshot = snapshot
        rebuild()
    }

    private fun rebuild() {
        val snapshot = snapshot ?: return
        val messages = snapshot.messages
        listModel.clear()
        // Errors first: they are few and what one looks for; otherwise in the order the tools printed them.
        messages.filter { visible(it.severity) }.sortedBy { it.severity.ordinal }.forEach { listModel.addElement(it) }
        val counts = listOf(GowinEdaSeverity.ERROR to "error", GowinEdaSeverity.WARNING to "warning", GowinEdaSeverity.NOTE to "note")
            .map { (severity, noun) -> YosysSynthesis.plural(messages.count { it.severity == severity }, noun) }
        val source = when {
            snapshot.project == null -> ""
            snapshot.running != null -> "Running ${snapshot.running.title}…"
            messages.isEmpty() && snapshot.compilation?.status == null -> "Not built yet."
            snapshot.messagesOf != null -> "${snapshot.messagesOf.title}: "
            else -> "Last run: "
        }
        summary.text = if (snapshot.running != null || snapshot.project == null || messages.isEmpty() && snapshot.compilation?.status == null) source
        else source + counts.joinToString(", ")
    }

    /** Information follows the notes' switch. */
    private fun visible(severity: GowinEdaSeverity): Boolean = (if (severity == GowinEdaSeverity.INFO) GowinEdaSeverity.NOTE else severity) in shown

    private fun navigate(): Boolean {
        val message = list.selectedValue ?: return false
        val directory = snapshot?.project?.directory ?: return false
        val file = message.file?.let { runCatching { directory.resolve(it).normalize() }.getOrNull() } ?: return false
        GowinEdaActions.open(project, file, message.line)
        return true
    }

    private inner class Toggle(private val severity: GowinEdaSeverity, text: String, icon: Icon) : ToggleAction("Show $text", null, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = severity in shown
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) shown += severity else shown -= severity
            rebuild()
        }
    }

    private class Renderer : ColoredListCellRenderer<GowinEdaMessage>() {
        override fun customizeCellRenderer(list: JList<out GowinEdaMessage>, message: GowinEdaMessage?, index: Int, selected: Boolean, hasFocus: Boolean) {
            message ?: return
            icon = when (message.severity) {
                GowinEdaSeverity.ERROR -> AllIcons.General.Error
                GowinEdaSeverity.WARNING -> AllIcons.General.Warning
                GowinEdaSeverity.NOTE, GowinEdaSeverity.INFO -> AllIcons.General.Information
            }
            append(message.text)
            message.file?.let { file ->
                append("  ")
                append(file.replace('\\', '/').substringAfterLast('/') + (message.line?.let { ":$it" } ?: ""), SimpleTextAttributes.LINK_ATTRIBUTES)
            }
            message.code?.let { append("  ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            toolTipText = message.text
        }
    }
}
