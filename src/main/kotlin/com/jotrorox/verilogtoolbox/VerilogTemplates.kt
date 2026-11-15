package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.util.elementType

/** Where the live templates of `liveTemplates/Verilog.xml` apply: Verilog code, outside comments and strings. */
class VerilogTemplateContextType : TemplateContextType("Verilog") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file as? VerilogFile ?: return false
        // The abbreviation being expanded ends at the offset, so look at what is in front of it.
        val type = file.findElementAt(maxOf(context.startOffset - 1, 0)).elementType
        return type !in VerilogTokenTypes.COMMENTS && type !in VerilogTokenTypes.STRINGS
    }
}

/** New | Verilog/SystemVerilog File, from the templates in `fileTemplates/internal`. */
class VerilogCreateFileAction : CreateFileFromTemplateAction(), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Verilog/SystemVerilog File")
        for (kind in KINDS) builder.addKind(kind, VerilogFileType.icon, kind)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        "Create $templateName $newName"

    private companion object {
        /** Each kind is the name of its file template. */
        val KINDS = listOf(
            "SystemVerilog Module", "Verilog Module", "SystemVerilog Package", "SystemVerilog Interface",
            "SystemVerilog Header", "Verilog Header",
        )
    }
}
