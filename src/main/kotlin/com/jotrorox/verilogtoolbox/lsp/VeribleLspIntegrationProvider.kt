package com.jotrorox.verilogtoolbox.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.ui.EditorNotifications
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import com.jotrorox.verilogtoolbox.settings.VeribleSettings
import com.jotrorox.verilogtoolbox.verilog.isVerilog
import com.jotrorox.verilogtoolbox.verilog.verilogLanguageId
import org.eclipse.lsp4j.Diagnostic
import java.nio.file.Path

/**
 * Starts Verible's language server when it is installed. Next to slang-server it adds formatting, style lint
 * and its quick-fixes; without slang-server it is the only language server and does everything it can.
 */
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

    // With slang-server running, leave what both servers can do to it instead of answering twice.
    override val lspCustomization: LspCustomization =
        if (ToolchainSettings.getInstance().findSlangServer() == null) object : LspCustomization() {}
        else object : LspCustomization() {
            override val goToDefinitionCustomizer = LspGoToDefinitionDisabled
            override val goToTypeDefinitionCustomizer = LspGoToTypeDefinitionDisabled
            override val hoverCustomizer = LspHoverDisabled
            override val completionCustomizer = LspCompletionDisabled
            override val findReferencesCustomizer = LspFindReferencesDisabled
            override val renameCustomizer = LspRenameDisabled
            override val documentSymbolCustomizer = LspDocumentSymbolDisabled
            override val documentHighlightsCustomizer = LspDocumentHighlightsDisabled

            // slang-server reports syntax errors too, and more precisely. Verible's lint findings stay.
            override val diagnosticsCustomizer = object : LspDiagnosticsSupport() {
                override fun createAnnotation(
                    holder: AnnotationHolder,
                    diagnostic: Diagnostic,
                    textRange: TextRange,
                    quickFixes: List<IntentionAction>,
                ) {
                    // Not diagnostic.message: lsp4j changed that getter's return type in 2026.3, so it
                    // only links against one platform version. The platform's own accessor works on all.
                    if (isVeribleSyntaxError(getMessage(diagnostic))) return
                    super.createAnnotation(holder, diagnostic, textRange, quickFixes)
                }
            }
        }
}

/** Whether a Verible diagnostic is a parse error rather than a lint finding, which ends in its `[rule-name]`. */
internal fun isVeribleSyntaxError(message: String?): Boolean =
    message != null && !message.trimEnd().endsWith("]") &&
        (message.startsWith("syntax error") || message.startsWith("Parse error"))
