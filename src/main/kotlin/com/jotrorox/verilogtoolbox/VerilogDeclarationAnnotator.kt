package com.jotrorox.verilogtoolbox

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType

/** Colours the name being declared after `module`, `package`, `class`, `` `define `` and the like. */
class VerilogDeclarationAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.elementType != VerilogTokenTypes.IDENTIFIER) return
        val key = declarationColor(element) ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(key).create()
    }

    private fun declarationColor(name: PsiElement): TextAttributesKey? {
        var previous = name.previousCodeLeaf() ?: return null
        // Skip a lifetime qualifier: `module automatic foo`.
        if (previous.elementType == VerilogTokenTypes.KEYWORD && previous.text in LIFETIMES) {
            previous = previous.previousCodeLeaf() ?: return null
        }
        return when (previous.elementType) {
            VerilogTokenTypes.MODULE, VerilogTokenTypes.PACKAGE -> VerilogColors.DECLARATION
            VerilogTokenTypes.KEYWORD -> VerilogColors.DECLARATION.takeIf { previous.text in DECLARING_KEYWORDS }
            VerilogTokenTypes.DIRECTIVE -> VerilogColors.MACRO.takeIf { previous.text == "`define" }
            else -> null
        }
    }

    private fun PsiElement.previousCodeLeaf(): PsiElement? {
        var sibling = prevSibling
        while (sibling is PsiWhiteSpace || sibling is PsiComment) sibling = sibling.prevSibling
        return sibling
    }

    private companion object {
        val LIFETIMES = setOf("automatic", "static")
        val DECLARING_KEYWORDS = setOf("class", "primitive", "program", "checker")
    }
}
