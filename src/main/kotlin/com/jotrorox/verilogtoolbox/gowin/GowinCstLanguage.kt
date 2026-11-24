package com.jotrorox.verilogtoolbox.gowin

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/**
 * Gowin physical constraints (`.cst`): the pin, I/O and placement constraints that the Gowin EDA and the open
 * source flow (nextpnr-himbaechel with apicula) read, each statement ended by `;`.
 */
object GowinCstLanguage : Language("GowinCst") {
    private fun readResolve(): Any = GowinCstLanguage
    override fun getDisplayName(): String = "Gowin Constraints"
}

object GowinCstFileType : LanguageFileType(GowinCstLanguage) {
    override fun getName(): String = "Gowin Constraints"
    override fun getDisplayName(): String = "Gowin Constraints"
    override fun getDescription(): String = "Gowin physical constraints"
    override fun getDefaultExtension(): String = "cst"
    override fun getIcon(): Icon = AllIcons.FileTypes.Config
}

class GowinCstFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GowinCstLanguage) {
    override fun getFileType(): FileType = GowinCstFileType
    override fun toString(): String = "Gowin Constraints File"
}

/** A flat token tree, like the Verilog and Quartus Tcl files: the tokens drive highlighting and commenting. */
class GowinCstParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GowinCstLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = GowinCstTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = GowinCstTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GowinCstFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(GowinCstLanguage)
    }
}
