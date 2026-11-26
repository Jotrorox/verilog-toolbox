package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.execution.ExecutionException
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import com.jotrorox.verilogtoolbox.verilog.isVerilog
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** What the GOWIN EDA actions share: feedback, background work, opening files and the edits of the file list. */
internal object GowinEdaActions {
    private val HEADER_EXTENSIONS = setOf("vh", "svh")

    fun notify(project: Project, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox").createNotification(content, type).notify(project)
    }

    /** Runs [task] on a pooled thread and hands its result to [then] on the UI thread, unless the project has been closed. */
    fun <T> inBackground(project: Project, task: () -> T, modality: ModalityState = ModalityState.defaultModalityState(), then: (T) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = task()
            ApplicationManager.getApplication().invokeLater({ then(result) }, modality, project.disposed)
        }
    }

    /** The selected files on disk; folders and files of other file systems are left out. */
    fun selectedFiles(e: AnActionEvent): List<Path> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
            .filter { !it.isDirectory && it.isInLocalFileSystem }
            .mapNotNull { file -> runCatching { file.toNioPath().toAbsolutePath().normalize() }.getOrNull() }

    /** The kind [file] is listed as; null for files a project does not list, headers among them, which are included. */
    fun listableKind(file: Path): GowinEdaFileKind? =
        GowinEdaFileKind.ofFile(file)?.takeIf { file.extension.lowercase() !in HEADER_EXTENSIONS }

    /** [text] with [file] listed, unless it is listed already or is not a file a project lists. */
    fun add(text: String, file: Path, directory: Path): String {
        val kind = listableKind(file) ?: return text
        return GowinEdaProjectText.addFile(text, GowinEdaProjectText.path(file, directory), kind) {
            GowinEdaProjectText.sameFile(it, file, directory)
        }
    }

    fun remove(text: String, file: Path, directory: Path): String =
        GowinEdaProjectText.removeFiles(text) { GowinEdaProjectText.sameFile(it, file, directory) }

    /** `uart.sv` for one file, `3 files` for more. */
    fun describe(files: List<Path>): String = files.singleOrNull()?.name ?: "${files.size} files"

    /**
     * Opens [file] in the editor at [line] counted from 1; a report of the flow opens in the report viewer, other
     * HTML in the browser. Does nothing for a file that is gone.
     */
    fun open(project: Project, file: Path, line: Int? = null) {
        if (file.extension.lowercase() in setOf("html", "htm") && !GowinEdaReportDocument.isReport(file.toString())) {
            if (Files.isRegularFile(file)) BrowserUtil.browse(file)
            return
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)?.takeIf { !it.isDirectory } ?: return
        val descriptor = if (line == null) OpenFileDescriptor(project, virtualFile) else OpenFileDescriptor(project, virtualFile, maxOf(line - 1, 0), 0)
        descriptor.navigate(true)
    }

}

/** Runs a flow on the GOWIN EDA project of the IDE project. Off while another one runs: they share `impl`. */
abstract class GowinEdaFlowAction(private val flow: GowinEdaFlow) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            GowinEdaProjectService.getInstance(project).active != null && GowinEdaRuns.getInstance(project).running == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GowinEdaRunner.run(e.project ?: return, flow)
    }
}

class GowinEdaSynthesizeAction : GowinEdaFlowAction(GowinEdaFlow.SYNTHESIS)
class GowinEdaPlaceAndRouteAction : GowinEdaFlowAction(GowinEdaFlow.PLACE_AND_ROUTE)
class GowinEdaBuildAction : GowinEdaFlowAction(GowinEdaFlow.BUILD)
class GowinEdaBuildAndProgramAction : GowinEdaFlowAction(GowinEdaFlow.BUILD_AND_PROGRAM)
class GowinEdaProgramAction : GowinEdaFlowAction(GowinEdaFlow.PROGRAM)

/** The reports of the last run, which open in the report viewer. */
class GowinEdaReportsGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { reports(it).isNotEmpty() } == true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        return reports(project).map { (title, file) ->
            object : DumbAwareAction(title, "Open ${file.fileName}", null) {
                override fun actionPerformed(e: AnActionEvent) = GowinEdaActions.open(project, file)
            }
        }.toTypedArray()
    }

    private fun reports(project: Project): List<Pair<String, Path>> =
        GowinEdaProjectService.getInstance(project).gowinProject?.let { GowinEdaCompilation.reports(it) }.orEmpty()
}

/** Opens the `.gprj` of the active project, where its device and files can be seen and changed. */
class GowinEdaOpenProjectFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GowinEdaProjectService.getInstance(it).active } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GowinEdaActions.open(project, GowinEdaProjectService.getInstance(project).active ?: return)
    }
}

/** Opens the physical constraints of the active project, or offers to create them when it has none. */
class GowinEdaOpenConstraintsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GowinEdaProjectService.getInstance(it).active } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = GowinEdaProjectService.getInstance(project)
        GowinEdaActions.inBackground(project, { service.gowinProject }) { gowinProject ->
            if (gowinProject == null) return@inBackground
            gowinProject.constraintFile?.let { return@inBackground GowinEdaActions.open(project, it) }
            GowinEdaConstraints.create(project, gowinProject)?.let { GowinEdaActions.open(project, it) }
        }
    }
}

/** Opens a tab of the GOWIN EDA tool window, for those who look for it in the menu. */
abstract class GowinEdaShowTabAction(private val tab: String) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GowinEdaProjectService.getInstance(it).active } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GowinEdaToolWindow.show(e.project ?: return, tab)
    }
}

class GowinEdaShowOverviewAction : GowinEdaShowTabAction(GowinEdaToolWindow.OVERVIEW)
class GowinEdaShowPinsAction : GowinEdaShowTabAction(GowinEdaToolWindow.PINS)
class GowinEdaShowMessagesAction : GowinEdaShowTabAction(GowinEdaToolWindow.MESSAGES)

/** Looks for project files and reports again, for what changed outside the IDE. */
class GowinEdaRefreshAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val service = GowinEdaProjectService.getInstance(e.project ?: return)
        service.rescan()
        service.outputChanged()
    }
}

/**
 * Open in GOWIN EDA: starts GOWIN EDA's IDE on the active project, for what this IDE does not do, such as the
 * FloorPlanner, the IP Core Generator or the Analyzer Oscilloscope. It runs on its own and outlives the IDE.
 */
class GowinEdaOpenInGowinAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && GowinEdaProjectService.getInstance(project).active != null && GowinEda.find() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = GowinEdaProjectService.getInstance(project).active ?: return
        GowinEdaActions.inBackground(project, { start(file) }) { error ->
            if (error != null) GowinEdaActions.notify(project, error, NotificationType.ERROR)
            else GowinEdaActions.notify(project, "Starting GOWIN EDA on ${file.name}…")
        }
    }

    /** Starts the IDE as its file association does, `gw_ide -prj <file>`; returns what went wrong, or null. */
    private fun start(file: Path): String? {
        val installation = GowinEda.find() ?: return "GOWIN EDA was not found. Set its installation in Settings | Tools | Verilog Toolbox."
        val ide = installation.tool(GowinEda.IDE) ?: return "${installation.displayName} has no ${GowinEda.binary(GowinEda.IDE)}."
        return try {
            val builder = installation.commandLine(ide, file.parent).withParameters("-prj", file.toString()).toProcessBuilder()
                // Nobody reads what the IDE prints, so it must not fill a pipe and block.
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.start().outputStream.close()
            null
        } catch (e: ExecutionException) {
            "GOWIN EDA could not be started: ${e.message}"
        } catch (e: IOException) {
            "GOWIN EDA could not be started: ${e.message}"
        }
    }
}

/** Add to GOWIN EDA Project: lists the selected files in the active `.gprj`, each as the kind its extension calls for. */
class GowinEdaAddFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val gowinProject = e.project?.let { GowinEdaProjectService.getInstance(it) }?.takeIf { it.active != null }?.gowinProject
        e.presentation.isEnabledAndVisible = gowinProject != null && candidates(gowinProject, GowinEdaActions.selectedFiles(e)).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = GowinEdaActions.selectedFiles(e)
        val service = GowinEdaProjectService.getInstance(project)
        GowinEdaActions.inBackground(project, { service.gowinProject }) { gowinProject ->
            if (gowinProject == null) return@inBackground
            val files = candidates(gowinProject, selected)
            val added = mutableListOf<Path>()
            val edited = service.edit("Add to GOWIN EDA Project") { text ->
                files.fold(text) { result, file -> GowinEdaActions.add(result, file, gowinProject.directory).also { if (it != result) added.add(file) } }
            }
            val name = gowinProject.file.name
            when {
                !edited -> GowinEdaActions.notify(project, "$name could not be changed.", NotificationType.ERROR)
                added.isEmpty() -> GowinEdaActions.notify(project, "${GowinEdaActions.describe(files)} ${if (files.size == 1) "is" else "are"} in $name already.")
                else -> GowinEdaActions.notify(project, "Added ${GowinEdaActions.describe(added)} to $name.")
            }
        }
    }

    private fun candidates(gowinProject: GowinEdaProject, files: List<Path>): List<Path> {
        val listed = gowinProject.files.mapTo(HashSet()) { it.path }
        return files.filter { GowinEdaActions.listableKind(it) != null && it !in listed }
    }
}

/** Remove from GOWIN EDA Project: takes the selected files out of the active `.gprj`. */
class GowinEdaRemoveFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val gowinProject = e.project?.let { GowinEdaProjectService.getInstance(it) }?.takeIf { it.active != null }?.gowinProject
        e.presentation.isEnabledAndVisible = gowinProject != null && listed(gowinProject, GowinEdaActions.selectedFiles(e)).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = GowinEdaActions.selectedFiles(e)
        val service = GowinEdaProjectService.getInstance(project)
        GowinEdaActions.inBackground(project, { service.gowinProject }) { gowinProject ->
            if (gowinProject == null) return@inBackground
            val files = listed(gowinProject, selected)
            val edited = service.edit("Remove from GOWIN EDA Project") { text ->
                files.fold(text) { result, file -> GowinEdaActions.remove(result, file, gowinProject.directory) }
            }
            val name = gowinProject.file.name
            if (edited) GowinEdaActions.notify(project, "Removed ${GowinEdaActions.describe(files)} from $name.")
            else GowinEdaActions.notify(project, "$name could not be changed.", NotificationType.ERROR)
        }
    }

    private fun listed(gowinProject: GowinEdaProject, files: List<Path>): List<Path> {
        val listed = gowinProject.files.mapTo(HashSet()) { it.path }
        return files.filter { it in listed }
    }
}

/**
 * Set as GOWIN EDA Top Module: makes a module of a Verilog file the top module of the active project, in its
 * options as GOWIN EDA keeps it, and lists the file if it is not listed yet. The module is the one at the caret,
 * the file's only module with ports, or one the user picks.
 */
class GowinEdaSetTopModuleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        presentation.setText(templatePresentation.text, true)
        val service = e.project?.let { GowinEdaProjectService.getInstance(it) }
        val file = verilogFile(e)
        if (service?.active == null || file == null) {
            presentation.isEnabledAndVisible = false
            return
        }
        presentation.isEnabledAndVisible = true
        // A file in the project view is only read once the action is chosen, unless it is open anyway.
        val editor = e.getData(CommonDataKeys.EDITOR)
        val document = editor?.document ?: FileDocumentManager.getInstance().getCachedDocument(file) ?: return
        val designs = designs(document.immutableCharSequence)
        if (designs.isEmpty()) {
            presentation.isEnabledAndVisible = false
            return
        }
        val module = choose(designs, editor?.caretModel?.offset) ?: return
        val gowinProject = service.gowinProject ?: return
        val path = file.toNioPath().toAbsolutePath().normalize()
        if (gowinProject.options.topModule == module.name && gowinProject.files.any { it.path == path }) {
            presentation.isEnabledAndVisible = false
            return
        }
        // Module names hold underscores, which are not meant as mnemonics here.
        presentation.setText("Set '${module.name}' as GOWIN EDA Top Module", false)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = verilogFile(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val document = editor?.document ?: FileDocumentManager.getInstance().getDocument(file) ?: return
        val designs = designs(document.immutableCharSequence)
        val module = choose(designs, editor?.caretModel?.offset)
        val path = file.toNioPath().toAbsolutePath().normalize()
        when {
            module != null -> setTopModule(project, path, module.name)
            designs.isEmpty() -> GowinEdaActions.notify(project, "${file.name} has no module with ports to make the top module.", NotificationType.WARNING)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(designs.map { it.name })
                .setTitle("Set as GOWIN EDA Top Module")
                .setItemChosenCallback { name -> setTopModule(project, path, name) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }

    private fun verilogFile(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf {
            !it.isDirectory && it.isInLocalFileSystem && it.isVerilog() && it.extension?.lowercase() in SOURCE_EXTENSIONS
        }

    private fun designs(source: CharSequence): List<VerilogModuleHeaders.Module> =
        VerilogModuleHeaders.modules(source).filter { it.ports.isNotEmpty() }

    private fun choose(designs: List<VerilogModuleHeaders.Module>, offset: Int?): VerilogModuleHeaders.Module? =
        designs.firstOrNull { offset != null && offset in it.range } ?: designs.singleOrNull()

    private fun setTopModule(project: Project, file: Path, module: String) {
        val service = GowinEdaProjectService.getInstance(project)
        val projectFile = service.active ?: return
        var added = false
        val listed = service.edit("Set Top Module") { text ->
            GowinEdaActions.add(text, file, projectFile.parent).also { added = it != text }
        }
        val set = listed && service.editOptions("Set Top Module") { text -> GowinEdaOptionsText.setString(text, "TopModule", module) }
        when {
            !set -> GowinEdaActions.notify(project, "The options of ${projectFile.name} could not be changed.", NotificationType.ERROR)
            added -> GowinEdaActions.notify(project, "$module is the top module of ${projectFile.name} now, and ${file.name} was added to it.")
            else -> GowinEdaActions.notify(project, "$module is the top module of ${projectFile.name} now.")
        }
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("v", "sv")
    }
}
