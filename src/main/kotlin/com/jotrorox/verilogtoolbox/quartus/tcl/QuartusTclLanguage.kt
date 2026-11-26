package com.jotrorox.verilogtoolbox.quartus.tcl

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
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.swing.Icon

/** The Tcl dialect of Quartus settings files and of the constraint files its Timing Analyzer reads. */
object QuartusTclLanguage : Language("QuartusTcl") {
    private fun readResolve(): Any = QuartusTclLanguage
    override fun getDisplayName(): String = "Quartus Tcl"
}

/** Quartus project (`.qpf`), settings (`.qsf`, `.qdf`) and IP (`.qip`) files: lists of assignments. */
object QuartusTclSettingsFileType : LanguageFileType(QuartusTclLanguage) {
    override fun getName(): String = "Quartus Settings"
    override fun getDisplayName(): String = "Quartus Settings"
    override fun getDescription(): String = "Quartus Prime project and settings"
    override fun getDefaultExtension(): String = "qsf"
    override fun getIcon(): Icon = AllIcons.FileTypes.Config
}

/** Timing constraints. Secondary, so that the language stays associated with the settings file type. */
object QuartusTclSdcFileType : LanguageFileType(QuartusTclLanguage, true) {
    override fun getName(): String = "SDC Constraints"
    override fun getDisplayName(): String = "SDC Constraints"
    override fun getDescription(): String = "Synopsys Design Constraints"
    override fun getDefaultExtension(): String = "sdc"
    override fun getIcon(): Icon = AllIcons.Vcs.History
}

/**
 * The text reports and summaries Quartus writes, opened as plain text instead of asking what they are.
 * Secondary, so that plain text keeps its own file type; it is registered by patterns such as `*.fit.rpt`
 * so that other `.rpt` files stay unclaimed.
 */
object QuartusTclReportFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName(): String = "Quartus Report"
    override fun getDisplayName(): String = "Quartus Report"
    override fun getDescription(): String = "Quartus Prime report"
    override fun getDefaultExtension(): String = "rpt"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text

    /** Quartus on Windows writes in the Windows code page, where `°C` is one byte; such a report is no UTF-8. */
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = if (isUtf8(content)) null else WINDOWS_1252.name()

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private fun isUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** The text of a report: UTF-8 when it is, else the Windows code page. */
    fun decode(bytes: ByteArray): String = String(bytes, if (isUtf8(bytes)) StandardCharsets.UTF_8 else WINDOWS_1252)
}

class QuartusTclFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, QuartusTclLanguage) {
    /** Settings and constraint files share the language, so the file type comes from the file. */
    override fun getFileType(): FileType = viewProvider.fileType
    override fun toString(): String = "Quartus Tcl File"
}

/**
 * Gives Quartus Tcl files a flat token tree. Like Verilog files they have no grammar: the tokens drive
 * highlighting, commenting, brace matching and completion.
 */
class QuartusTclParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = QuartusTclLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = QuartusTclTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = QuartusTclTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = QuartusTclFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(QuartusTclLanguage)
    }
}
