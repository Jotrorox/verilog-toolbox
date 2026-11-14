package com.jotrorox.verilogtoolbox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
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
        /** Applies changed language server paths: restarts running servers and refreshes editor banners. */
        fun restartEverywhere() {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val clients = LspClientManager.getInstance(project)
                clients.stopAndRestartClientsIfNeeded(VeribleLspIntegrationProvider::class.java)
                clients.stopAndRestartClientsIfNeeded(SlangLspIntegrationProvider::class.java)
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

    // With slang-server running, leave navigation, hover and completion to it instead of answering twice.
    override val lspCustomization: LspCustomization =
        if (ToolchainSettings.getInstance().findSlangServer() == null) LspCustomization()
        else object : LspCustomization() {
            override val goToDefinitionCustomizer = LspGoToDefinitionDisabled
            override val goToTypeDefinitionCustomizer = LspGoToTypeDefinitionDisabled
            override val hoverCustomizer = LspHoverDisabled
            override val completionCustomizer = LspCompletionDisabled
            override val findReferencesCustomizer = LspFindReferencesDisabled
            override val renameCustomizer = LspRenameDisabled
            override val documentSymbolCustomizer = LspDocumentSymbolDisabled
            override val documentHighlightsCustomizer = LspDocumentHighlightsDisabled
        }
}
