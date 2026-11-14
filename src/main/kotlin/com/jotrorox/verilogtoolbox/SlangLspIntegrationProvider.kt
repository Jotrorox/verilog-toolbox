package com.jotrorox.verilogtoolbox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import java.nio.file.Path

/**
 * Starts slang-server next to Verible when its path is configured. slang elaborates the design, so it
 * takes over navigation, hover and completion, while Verible keeps linting and formatting.
 */
class SlangLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (!file.isVerilog()) return
        val executable = ToolchainSettings.getInstance().findSlangServer() ?: return
        clientStarter.ensureClientStarted(SlangLspClientDescriptor(project, executable))
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
        LspClientWidgetItem(lspClient, currentFile, settingsPageClass = VeribleConfigurable::class.java)
}

private class SlangLspClientDescriptor(project: Project, private val executable: Path) :
    ProjectWideLspClientDescriptor(project, "slang-server") {

    override fun isSupportedFile(file: VirtualFile): Boolean = file.isVerilog()

    override fun getLanguageId(file: VirtualFile): String = file.verilogLanguageId()

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(executable.toString())
            .withWorkDirectory(project.basePath)
            .withCharset(Charsets.UTF_8)

    // Formatting stays with Verible.
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val formattingCustomizer: LspFormattingCustomizer = LspFormattingDisabled
    }
}
