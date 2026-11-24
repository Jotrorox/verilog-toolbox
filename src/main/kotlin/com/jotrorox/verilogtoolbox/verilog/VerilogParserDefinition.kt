package com.jotrorox.verilogtoolbox.verilog

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class VerilogFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, VerilogLanguage) {
    override fun getFileType(): FileType = VerilogFileType
    override fun toString(): String = "Verilog File"
}

/**
 * Gives Verilog files a flat token tree. There is no grammar: code analysis comes from the language
 * server, while the tokens drive highlighting, commenting and brace matching.
 */
class VerilogParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = VerilogLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = VerilogTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = VerilogTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = VerilogFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(VerilogLanguage)
    }
}
