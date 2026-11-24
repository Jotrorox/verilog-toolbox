package com.jotrorox.verilogtoolbox.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import com.jotrorox.verilogtoolbox.verilog.isVerilog
import com.jotrorox.verilogtoolbox.verilog.verilogLanguageId
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.nio.file.Path

/**
 * Starts slang-server, the default language server: diagnostics, navigation, hover, completion, rename,
 * inlay hints and symbols. It is downloaded first if it is not installed.
 */
class SlangLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (!file.isVerilog()) return
        val executable = ToolchainSettings.getInstance().findSlangServer()
        if (executable == null) {
            SlangServerDownloader.downloadAutomatically(project)
            return
        }
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

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient = SlangLsp4jClient(handler)

    // A restarted server knows nothing of the design it was elaborating.
    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            SlangDesign.getInstance(project).sendToServer()
        }
    }
}

private class SlangLsp4jClient(handler: LspServerNotificationsHandler) : Lsp4jClient(handler) {
    /** slang-server announces its configuration to its VS Code extension; accepted so it is not logged as an error. */
    @Suppress("unused", "UNUSED_PARAMETER")
    @JsonNotification("slang/setConfig")
    fun setConfig(config: Any?) {
    }
}
