package com.jotrorox.verilogtoolbox.quartus

import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import com.jotrorox.verilogtoolbox.verilog.isVerilog
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** What the Quartus project actions share: feedback, background work and the text edits of file lists. */
internal object QuartusActions {
    private val HEADER_EXTENSIONS = setOf("vh", "svh")

    fun notify(project: Project, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(content, type).notify(project)
    }

    /**
     * Runs [task] on a pooled thread, for what touches the disk or starts a process, and hands its result
     * to [then] on the UI thread in the modality of the caller, unless the project has been closed.
     */
    fun <T> inBackground(
        project: Project,
        task: () -> T,
        modality: ModalityState = ModalityState.defaultModalityState(),
        then: (T) -> Unit,
    ) {
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

    /**
     * The assignment [file] is listed with; null for files a project does not list, headers among them,
     * which are included rather than listed.
     */
    fun listableKind(file: Path): QuartusFileKind? =
        QuartusFileKind.ofFile(file)?.takeIf { file.extension.lowercase() !in HEADER_EXTENSIONS }

    /** The names of the file assignments in [text] that list [file], whatever kind they list it as. */
    private fun listings(text: String, file: Path, directory: Path, activeOnly: Boolean): List<String> =
        QuartusScript.commands(text).filter { command ->
            command.command == QuartusCommand.GLOBAL && QuartusFileKind.ofAssignment(command.name) != null &&
                !(activeOnly && command.isInactive) &&
                command.value?.let { QuartusSettingsText.sameFile(it, file, directory) } == true
        }.mapNotNull { it.name }.distinct()

    /** [text] with [file] listed, unless it is listed already or is not a file a project lists. */
    fun add(text: String, file: Path, directory: Path): String {
        val kind = listableKind(file) ?: return text
        if (listings(text, file, directory, activeOnly = true).isNotEmpty()) return text
        return QuartusSettingsText.addGlobal(text, kind.assignment, QuartusSettingsText.path(file, directory)) {
            QuartusSettingsText.sameFile(it, file, directory)
        }
    }

    /** [text] without the assignments that list [file]. */
    fun remove(text: String, file: Path, directory: Path): String =
        listings(text, file, directory, activeOnly = false).fold(text) { result, name ->
            QuartusSettingsText.removeGlobal(result, name) { QuartusSettingsText.sameFile(it, file, directory) }
        }

    /** `uart.sv` for one file, `3 files` for more. */
    fun describe(files: List<Path>): String = files.singleOrNull()?.name ?: "${files.size} files"
}

/**
 * Add to Quartus Project: lists the selected files in the settings of the active revision, each with the
 * assignment its extension calls for, as one undoable change.
 */
class QuartusAddFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val revision = project?.let { QuartusProjectService.getInstance(it) }?.takeIf { it.active != null }?.revision
        e.presentation.isEnabledAndVisible =
            revision != null && candidates(revision, QuartusActions.selectedFiles(e)).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = QuartusActions.selectedFiles(e)
        val service = QuartusProjectService.getInstance(project)
        QuartusActions.inBackground(project, { service.revision }) { revision ->
            if (revision == null) return@inBackground
            val files = candidates(revision, selected)
            val directory = revision.project.directory
            val added = mutableListOf<Path>()
            val edited = service.edit("Add to Quartus Project") { text ->
                files.fold(text) { result, file ->
                    QuartusActions.add(result, file, directory).also { if (it != result) added.add(file) }
                }
            }
            val settings = revision.settingsFile.name
            when {
                !edited -> QuartusActions.notify(project, "$settings could not be changed.", NotificationType.ERROR)
                added.isEmpty() -> QuartusActions.notify(
                    project,
                    "${QuartusActions.describe(files)} ${if (files.size == 1) "is" else "are"} in $settings already.",
                )
                else -> QuartusActions.notify(project, "Added ${QuartusActions.describe(added)} to $settings.")
            }
        }
    }

    /** The files a project lists that this revision does not list yet. */
    private fun candidates(revision: QuartusRevision, files: List<Path>): List<Path> {
        val listed = revision.listedFiles.mapTo(HashSet()) { it.path }
        return files.filter { QuartusActions.listableKind(it) != null && it !in listed }
    }
}

/** Remove from Quartus Project: takes the selected files out of the settings of the active revision. */
class QuartusRemoveFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val revision = project?.let { QuartusProjectService.getInstance(it) }?.takeIf { it.active != null }?.revision
        e.presentation.isEnabledAndVisible =
            revision != null && listed(revision, QuartusActions.selectedFiles(e)).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = QuartusActions.selectedFiles(e)
        val service = QuartusProjectService.getInstance(project)
        QuartusActions.inBackground(project, { service.revision }) { revision ->
            if (revision == null) return@inBackground
            val files = listed(revision, selected)
            val directory = revision.project.directory
            val removed = mutableListOf<Path>()
            val edited = service.edit("Remove from Quartus Project") { text ->
                files.fold(text) { result, file ->
                    QuartusActions.remove(result, file, directory).also { if (it != result) removed.add(file) }
                }
            }
            val settings = revision.settingsFile.name
            val left = files - removed.toSet()
            when {
                !edited -> QuartusActions.notify(project, "$settings could not be changed.", NotificationType.ERROR)
                // A file listed by a script that the settings source, or by a .qip, is not in their text.
                left.isNotEmpty() -> QuartusActions.notify(
                    project,
                    (if (removed.isEmpty()) "" else "Removed ${QuartusActions.describe(removed)} from $settings. ") +
                        "${QuartusActions.describe(left)} ${if (left.size == 1) "is" else "are"} listed by a script " +
                        "that $settings runs; remove ${if (left.size == 1) "it" else "them"} there.",
                    NotificationType.WARNING,
                )
                else -> QuartusActions.notify(project, "Removed ${QuartusActions.describe(removed)} from $settings.")
            }
        }
    }

    private fun listed(revision: QuartusRevision, files: List<Path>): List<Path> {
        val listed = revision.listedFiles.mapTo(HashSet()) { it.path }
        return files.filter { it in listed }
    }
}

/**
 * Set as Quartus Top-Level Entity: makes a module of a Verilog file the top level of the active revision,
 * and lists the file if it is not listed yet. The module is the one at the caret, the file's only module
 * with ports, or one the user picks; modules without ports are testbenches, which do not go on a device.
 */
class QuartusSetTopLevelAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        presentation.setText(templatePresentation.text, true)
        val project = e.project
        val service = project?.let { QuartusProjectService.getInstance(it) }
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
        val revision = service.revision ?: return
        val path = file.toNioPath().toAbsolutePath().normalize()
        if (revision.topLevelEntity == module.name && revision.files.any { it.path == path }) {
            presentation.isEnabledAndVisible = false
            return
        }
        // Module names hold underscores, which are not meant as mnemonics here.
        presentation.setText("Set '${module.name}' as Quartus Top-Level Entity", false)
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
            module != null -> setTopLevel(project, path, module.name)
            designs.isEmpty() -> QuartusActions.notify(
                project, "${file.name} has no module with ports to make the top-level entity.", NotificationType.WARNING,
            )
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(designs.map { it.name })
                .setTitle("Set as Quartus Top-Level Entity")
                .setItemChosenCallback { name -> setTopLevel(project, path, name) }
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

    /** The module at the caret, else the only one; null when the user has to pick. */
    private fun choose(designs: List<VerilogModuleHeaders.Module>, offset: Int?): VerilogModuleHeaders.Module? =
        designs.firstOrNull { offset != null && offset in it.range } ?: designs.singleOrNull()

    private fun setTopLevel(project: Project, file: Path, module: String) {
        val service = QuartusProjectService.getInstance(project)
        val quartusProject = service.active ?: return
        val settings = quartusProject.settingsFile(service.activeRevisionName ?: return).name
        var added = false
        val edited = service.edit("Set Top-Level Entity") { text ->
            val withTop = QuartusSettingsText.setGlobal(text, "TOP_LEVEL_ENTITY", module)
            QuartusActions.add(withTop, file, quartusProject.directory).also { added = it != withTop }
        }
        when {
            !edited -> QuartusActions.notify(project, "$settings could not be changed.", NotificationType.ERROR)
            added -> QuartusActions.notify(
                project, "$module is the top-level entity of $settings now, and ${file.name} was added to it.",
            )
            else -> QuartusActions.notify(project, "$module is the top-level entity of $settings now.")
        }
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("v", "sv")
    }
}

/**
 * Open in Quartus Prime: starts the Quartus GUI on the active project, for what the IDE does not do,
 * such as Platform Designer or the Pin Planner. The GUI runs on its own and outlives the IDE.
 */
class QuartusOpenInQuartusAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && QuartusProjectService.getInstance(project).active != null &&
            Quartus.find() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // The .qsf of a project without a .qpf is what Quartus opens then.
        val quartusProject = QuartusProjectService.getInstance(project).active ?: return
        QuartusActions.inBackground(project, { start(quartusProject) }) { error ->
            if (error != null) QuartusActions.notify(project, error, NotificationType.ERROR)
            else QuartusActions.notify(project, "Starting Quartus Prime on ${quartusProject.file.name}…")
        }
    }

    /** Starts the GUI; returns what went wrong, or null. */
    private fun start(quartusProject: QuartusProject): String? {
        val installation = Quartus.find()
            ?: return "Quartus Prime was not found. Set its installation in Settings | Tools | Verilog Toolbox."
        val gui = installation.tool(GUI) ?: return "${installation.displayName} has no ${Quartus.binary(GUI)}."
        return try {
            val builder = installation.commandLine(gui, quartusProject.directory)
                .withParameters(quartusProject.file.toString())
                .toProcessBuilder()
                // Nobody reads what the GUI prints, so it must not fill a pipe and block.
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.start().outputStream.close()
            null
        } catch (e: ExecutionException) {
            "Quartus Prime could not be started: ${e.message}"
        } catch (e: IOException) {
            "Quartus Prime could not be started: ${e.message}"
        }
    }

    private companion object {
        const val GUI = "quartus"
    }
}
