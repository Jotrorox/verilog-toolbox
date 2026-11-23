package com.jotrorox.verilogtoolbox

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import org.eclipse.lsp4j.ExecuteCommandParams
import java.nio.file.Files
import java.nio.file.Path

/**
 * The design of this project: a top-level file or a file list (`.f`, a build file to slang-server).
 * Without one slang-server checks each file on its own; with one it reports errors across the whole
 * hierarchy. A file list is the design for Icarus runs too, so that both tools take their sources, include
 * directories and defines from one place; a top-level file only concerns slang-server.
 *
 * The names of the class and its state date from when only slang-server used the selection, and stay
 * so that workspaces keep theirs.
 */
@Service(Service.Level.PROJECT)
@State(name = "VerilogToolboxSlangDesign", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SlangDesign(private val project: Project) :
    SerializablePersistentStateComponent<SlangDesign.State>(State()) {

    /** Fields are `var` so that the platform saves them, see [ToolchainSettings.State]. */
    data class State(@JvmField var path: String = "", @JvmField var buildFile: Boolean = false)

    /** The top-level or build file, or empty when no design is selected. */
    val path: String get() = state.path

    /** Whether the selection is a file list rather than a top-level file. */
    val isFileList: Boolean get() = state.buildFile

    /**
     * The selected file list, or null when none is selected or it no longer exists. For a design read from
     * a Quartus settings file it is the list written from that, which may be written here.
     */
    val fileList: Path?
        get() = state.takeIf { it.buildFile && it.path.isNotEmpty() }
            ?.let { runCatching { Path.of(it.path) }.getOrNull() }?.takeIf { Files.isRegularFile(it) }
            ?.let { if (QuartusDesign.isSettingsFile(it.toString())) QuartusDesign.fileList(project, it) else it }

    fun select(file: VirtualFile) {
        updateState { State(file.path, file.isBuildFile()) }
        sendToServer()
    }

    fun clear() {
        updateState { State() }
        sendToServer()
    }

    /** Tells every running slang-server about the selection. Does nothing while no server is running. */
    fun sendToServer() {
        var selection = state
        if (selection.path.isNotEmpty() && !Files.isRegularFile(Path.of(selection.path))) {
            // The file was deleted or renamed outside the IDE.
            selection = updateState { State() }
        }
        // An empty top level puts the server back to checking files on their own.
        val command = if (selection.buildFile) "slang.setBuildFile" else "slang.setTopLevel"
        val clients = LspClientManager.getInstance(project).getClients(SlangLspIntegrationProvider::class.java)
        if (clients.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            // The server reads a file list; of a Quartus project that is the one written from its settings.
            val path = if (selection.buildFile && QuartusDesign.isSettingsFile(selection.path)) {
                QuartusDesign.fileList(project, Path.of(selection.path))?.toString().orEmpty()
            } else {
                selection.path
            }
            for (client in clients) {
                try {
                    client.sendRequestSync(30_000) {
                        it.workspaceService.executeCommand(ExecuteCommandParams(command, listOf(path)))
                    }
                } catch (e: Exception) {
                    LOG.warn("slang-server did not accept $command $path", e)
                }
            }
        }
    }

    companion object {
        private val LOG = logger<SlangDesign>()

        fun getInstance(project: Project): SlangDesign = project.service()
    }
}

/** A file list for the slang driver, such as `compile.f`, or the Quartus settings file one is written from. */
private fun VirtualFile.isBuildFile(): Boolean = extension?.lowercase().let { it == "f" || it == "qsf" }

private fun VirtualFile.isQuartusSettings(): Boolean = extension?.lowercase() == "qsf"

/**
 * The file a design can be selected from. A file list is offered even while slang-server is switched
 * off, because Icarus runs use it; a top-level file means something to slang-server only.
 */
private fun AnActionEvent.designFile(): VirtualFile? =
    getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf {
        !it.isDirectory && it.isInLocalFileSystem &&
            (it.isBuildFile() || it.isVerilog() && ToolchainSettings.getInstance().slangServerEnabled)
    }

/** Makes the selected `.f` file the project's design file list, or the selected Verilog file slang-server's top level. */
class SlangSelectDesignAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.designFile()
        e.presentation.isEnabledAndVisible =
            project != null && file != null && file.path != SlangDesign.getInstance(project).path
        if (file != null) {
            e.presentation.text = when {
                file.isQuartusSettings() -> "Use Quartus Project as Design"
                file.isBuildFile() -> "Use as Design File List"
                else -> "Set as slang-server Top Level"
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.designFile() ?: return
        SlangDesign.getInstance(project).select(file)
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                when {
                    file.isQuartusSettings() ->
                        "slang-server, Verilator lint, Check Synthesis and Icarus Verilog runs now use the sources of ${file.name}."
                    file.isBuildFile() -> "slang-server and Icarus Verilog runs now use the design listed in ${file.name}."
                    else -> "slang-server now elaborates the design below ${file.name}."
                },
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}

/** Drops the selection: slang-server checks every file on its own again, Icarus runs compile the testbench's directory. */
class SlangClearDesignAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && SlangDesign.getInstance(project).path.isNotEmpty()
        if (project != null) {
            val design = SlangDesign.getInstance(project)
            e.presentation.text = when {
                QuartusDesign.isSettingsFile(design.path) -> "Stop Using Quartus Project as Design"
                design.isFileList -> "Clear Design File List"
                else -> "Clear slang-server Top Level"
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // A Quartus project is made the design on its own, so that it is not wanted has to be remembered.
        if (QuartusDesign.isSettingsFile(SlangDesign.getInstance(project).path)) QuartusProjectService.getInstance(project).declineDesign()
        else SlangDesign.getInstance(project).clear()
    }
}
