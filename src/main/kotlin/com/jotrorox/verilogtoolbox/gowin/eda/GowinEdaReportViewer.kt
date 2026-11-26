package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.ex.FileTypeIdentifiableByVirtualFile
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jotrorox.verilogtoolbox.quartus.QuartusReportEditor
import com.jotrorox.verilogtoolbox.quartus.QuartusReportView
import java.nio.file.Path
import javax.swing.Icon

/**
 * Opens GOWIN EDA's reports in the viewer of the Quartus reports: sections listed and searched, tables that sort
 * and filter, messages that lead to the source, and the text one click away.
 */
class GowinEdaReportEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = !file.isDirectory && GowinEdaReportDocument.isReport(file.path)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val view = QuartusReportView(project, file) { GowinEdaReportDocument.parse(it, file.name) }
        return QuartusReportEditor(project, text, view, "GOWIN EDA Report")
    }

    override fun getEditorTypeId(): String = "gowin-eda-report"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The timing report, `impl/pnr/<name>.tr`, as text. Its extension says nothing on its own, so the file type
 * claims only the `.tr` files in a GOWIN EDA project's `impl/pnr`; without one they would be taken for binary.
 */
object GowinEdaReportFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true), FileTypeIdentifiableByVirtualFile {
    override fun getName(): String = "GOWIN EDA Report"
    override fun getDisplayName(): String = "GOWIN EDA Report"
    override fun getDescription(): String = "GOWIN EDA timing report"
    override fun getDefaultExtension(): String = "tr"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text

    override fun isMyFileType(file: VirtualFile): Boolean =
        !file.isDirectory && file.name.endsWith(".tr") && GowinEdaReportDocument.isReport(file.path)
}

/** Opens [file] in the report viewer, on the first section whose title contains [section]. */
internal fun openGowinReport(project: Project, file: Path, section: String) {
    GowinEdaActions.open(project, file)
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return
    FileEditorManager.getInstance(project).getEditorList(virtualFile).filterIsInstance<QuartusReportEditor>().firstOrNull()?.select(section)
}
