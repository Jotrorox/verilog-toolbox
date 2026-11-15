package com.jotrorox.verilogtoolbox

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/** Writes `<module>_tb.sv` next to a module: the one at the caret, the file's only one, or one the user picks. */
class GenerateTestbenchAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && it.isInLocalFileSystem && it.isVerilog() }
        // The editor's text is at hand; a file in the project view is only read once the action is chosen.
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && file != null &&
            (editor == null || designs(editor.document.immutableCharSequence).isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val document = editor?.document ?: FileDocumentManager.getInstance().getDocument(file) ?: return
        val designs = designs(document.immutableCharSequence)
        val offset = editor?.caretModel?.offset
        val design = designs.firstOrNull { offset != null && offset in it.range } ?: designs.singleOrNull()
        when {
            design != null -> create(project, file, design)
            designs.isEmpty() -> notify(
                project, "${file.name} has no module with ports to write a testbench for.", NotificationType.WARNING,
            )
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(designs.map { it.name })
                .setTitle("Generate Testbench For")
                .setItemChosenCallback { name -> create(project, file, designs.first { it.name == name }) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }

    /** A module without ports is a testbench itself. */
    private fun designs(source: CharSequence): List<VerilogModuleHeaders.Module> =
        VerilogModuleHeaders.modules(source).filter { it.ports.isNotEmpty() }

    private fun create(project: Project, source: VirtualFile, design: VerilogModuleHeaders.Module) {
        val directory = source.parent ?: return
        val name = VerilogTestbenchGenerator.fileName(design)
        val existing = directory.findChild(name)
        if (existing != null) {
            // A testbench that exists has been worked on; opening it is the safe default.
            val choice = Messages.showDialog(
                project, "'$name' already exists in ${directory.presentableUrl}.", "Generate Testbench",
                arrayOf("Overwrite", "Open Existing", Messages.getCancelButton()), 1, Messages.getWarningIcon(),
            )
            if (choice == 1) return OpenFileDescriptor(project, existing).navigate(true)
            if (choice != 0) return
        }
        val text = VerilogTestbenchGenerator.generate(design)
        val testbench = try {
            WriteCommandAction.writeCommandAction(project).withName("Generate Testbench").compute<VirtualFile, IOException> {
                val target = existing ?: directory.createChildData(this, name)
                // An open testbench is replaced through its document, so the editor and undo follow.
                val document = FileDocumentManager.getInstance().getDocument(target)
                if (document != null) document.setText(text) else VfsUtil.saveText(target, text)
                target
            }
        } catch (e: IOException) {
            return notify(project, "'$name' could not be written: ${e.message}", NotificationType.ERROR)
        }
        val stimulus = text.indexOf(VerilogTestbenchGenerator.STIMULUS_MARKER).coerceAtLeast(0)
        OpenFileDescriptor(project, testbench, stimulus).navigate(true)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(content, type).notify(project)
    }
}
