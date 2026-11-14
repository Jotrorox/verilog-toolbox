package com.jotrorox.verilogtoolbox

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/** Banner on Verilog files while slang-server is being downloaded, or while no language server is available. */
class LanguageServerNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (!file.isVerilog()) return null
        val toolchain = ToolchainSettings.getInstance()
        if (toolchain.findSlangServer() != null) return null

        if (!toolchain.slangServerEnabled) {
            if (VeribleSettings.getInstance().findLanguageServer() != null) return null
            return banner(EditorNotificationPanel.Status.Warning, project) {
                text = "No language server is available: slang-server is switched off and Verible was not found."
            }
        }
        if (SlangServerDownloader.isDownloading) {
            return Function { editor ->
                EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                    text = "Downloading slang-server. Diagnostics, navigation and completion start when it is done."
                }
            }
        }
        return banner(EditorNotificationPanel.Status.Warning, project) {
            text = if (toolchain.slangServerPath.isEmpty()) {
                "slang-server was not found, so diagnostics, navigation and completion are off."
            } else {
                "The configured slang-server cannot be run, so diagnostics, navigation and completion are off."
            }
            createActionLabel("Download slang-server") {
                // Without this the downloaded copy would lose to the path that does not work.
                toolchain.slangServerPath = ""
                SlangServerDownloader.downloadInBackground(project)
            }
        }
    }

    private fun banner(
        status: EditorNotificationPanel.Status,
        project: Project,
        configure: EditorNotificationPanel.() -> Unit,
    ): Function<in FileEditor, out JComponent?> = Function { editor ->
        EditorNotificationPanel(editor, status).apply {
            configure()
            createActionLabel("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            }
        }
    }
}
