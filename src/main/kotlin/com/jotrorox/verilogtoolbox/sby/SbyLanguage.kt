package com.jotrorox.verilogtoolbox.sby

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/**
 * SymbiYosys configurations (`.sby`): the tasks, options, engines, Yosys script and files of a formal
 * verification run, in sections such as `[options]`, with lines that only some tasks see.
 */
object SbyLanguage : Language("SymbiYosys") {
    private fun readResolve(): Any = SbyLanguage
    override fun getDisplayName(): String = "SymbiYosys"
}

object SbyFileType : LanguageFileType(SbyLanguage) {
    val ICON: Icon = IconLoader.getIcon("/icons/sby.svg", SbyFileType::class.java)

    override fun getName(): String = "SymbiYosys"
    override fun getDisplayName(): String = "SymbiYosys"
    override fun getDescription(): String = "SymbiYosys formal verification configuration"
    override fun getDefaultExtension(): String = "sby"
    override fun getIcon(): Icon = ICON
}

class SbyFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SbyLanguage) {
    override fun getFileType(): FileType = SbyFileType
    override fun toString(): String = "SymbiYosys File"
}

/** A flat token tree, like the plugin's other languages: the tokens drive highlighting, commenting and the gutter. */
class SbyParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = SbyLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = SbyTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = SbyFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(SbyLanguage)
    }
}
