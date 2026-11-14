package com.jotrorox.verilogtoolbox

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.io.IOException
import java.util.function.Function
import javax.swing.JComponent

/** Banner on Verilog files while no Verible language server is available, offering to download it. */
class VeribleNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (!file.isVerilog() || VeribleSettings.getInstance().findLanguageServer() != null) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "The Verible language server was not found, so diagnostics and formatting are off."
                createActionLabel("Download Verible") { downloadInBackground(project) }
                createActionLabel("Configure…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
                }
            }
        }
    }

    private fun downloadInBackground(project: Project) {
        object : Task.Backgroundable(project, "Downloading Verible", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val executable = VeribleDownloader.downloadLatest(indicator)
                    VeribleSettings.getInstance().languageServerPath = executable.toString()
                    ApplicationManager.getApplication().invokeLater {
                        VeribleLspIntegrationProvider.restartEverywhere()
                    }
                } catch (e: IOException) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                        .createNotification(
                            "Verible download failed", e.message ?: e.toString(), NotificationType.ERROR
                        )
                        .notify(project)
                }
            }
        }.queue()
    }
}
