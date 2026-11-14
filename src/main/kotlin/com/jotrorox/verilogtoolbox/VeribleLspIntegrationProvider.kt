package com.jotrorox.verilogtoolbox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.ui.EditorNotifications
import java.nio.file.Path

/** Starts Verible's language server for Verilog files: diagnostics, formatting and quick-fixes. */
class VeribleLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (!file.isVerilog()) return
        val executable = VeribleSettings.getInstance().findLanguageServer() ?: return
        clientStarter.ensureClientStarted(VeribleLspClientDescriptor(project, executable))
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
        LspClientWidgetItem(lspClient, currentFile, settingsPageClass = VeribleConfigurable::class.java)

    companion object {
        /** Applies a changed language server path: restarts running servers and refreshes editor banners. */
        fun restartEverywhere() {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                LspClientManager.getInstance(project)
                    .stopAndRestartClientsIfNeeded(VeribleLspIntegrationProvider::class.java)
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }
}

private class VeribleLspClientDescriptor(project: Project, private val executable: Path) :
    ProjectWideLspClientDescriptor(project, "Verible") {

    override fun isSupportedFile(file: VirtualFile): Boolean = file.isVerilog()

    override fun getLanguageId(file: VirtualFile): String = file.verilogLanguageId()

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(executable.toString(), "--rules_config_search")
            .withWorkDirectory(project.basePath)
            .withCharset(Charsets.UTF_8)
}
