package com.jotrorox.verilogtoolbox.sby

import com.intellij.lang.Commenter
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

/** Every key falls back to a default language colour, so no colour scheme needs to mention them. */
object SbyColors {
    @JvmField val COMMENT = createTextAttributesKey("SBY_COMMENT", Default.LINE_COMMENT)
    @JvmField val SECTION = createTextAttributesKey("SBY_SECTION", Default.KEYWORD)
    @JvmField val TASK = createTextAttributesKey("SBY_TASK", Default.FUNCTION_DECLARATION)
    @JvmField val TAG = createTextAttributesKey("SBY_TAG", Default.METADATA)
    @JvmField val KEYWORD = createTextAttributesKey("SBY_KEYWORD", Default.KEYWORD)
    @JvmField val MARKER = createTextAttributesKey("SBY_MARKER", Default.KEYWORD)
    @JvmField val OPTION = createTextAttributesKey("SBY_OPTION", Default.INSTANCE_FIELD)
    @JvmField val ENGINE = createTextAttributesKey("SBY_ENGINE", Default.FUNCTION_CALL)
    @JvmField val COMMAND = createTextAttributesKey("SBY_COMMAND", Default.FUNCTION_CALL)
    @JvmField val FLAG = createTextAttributesKey("SBY_FLAG", Default.PARAMETER)
    @JvmField val PATH = createTextAttributesKey("SBY_PATH", Default.STRING)
    @JvmField val NUMBER = createTextAttributesKey("SBY_NUMBER", Default.NUMBER)
    @JvmField val VALUE = createTextAttributesKey("SBY_VALUE", Default.CONSTANT)
    @JvmField val COLON = createTextAttributesKey("SBY_COLON", Default.OPERATION_SIGN)
}

class SbySyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = SbyLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    private companion object {
        val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            SbyTokenTypes.COMMENT to SbyColors.COMMENT,
            SbyTokenTypes.SECTION to SbyColors.SECTION,
            SbyTokenTypes.TASK to SbyColors.TASK,
            SbyTokenTypes.TAG to SbyColors.TAG,
            SbyTokenTypes.COLON to SbyColors.COLON,
            SbyTokenTypes.KEYWORD to SbyColors.KEYWORD,
            SbyTokenTypes.BLOCK_END to SbyColors.MARKER,
            SbyTokenTypes.PYCODE_MARKER to SbyColors.MARKER,
            SbyTokenTypes.OPTION to SbyColors.OPTION,
            SbyTokenTypes.ENGINE to SbyColors.ENGINE,
            SbyTokenTypes.COMMAND to SbyColors.COMMAND,
            SbyTokenTypes.FLAG to SbyColors.FLAG,
            SbyTokenTypes.PATH to SbyColors.PATH,
            SbyTokenTypes.NUMBER to SbyColors.NUMBER,
            SbyTokenTypes.VALUE to SbyColors.VALUE,
        )
    }
}

class SbySyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = SbySyntaxHighlighter()
}

/** Settings | Editor | Color Scheme | SymbiYosys. */
class SbyColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "SymbiYosys"
    override fun getIcon(): Icon = SbyFileType.icon
    override fun getHighlighter(): SyntaxHighlighter = SbySyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getDemoText(): String = DEMO

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Section", SbyColors.SECTION),
            AttributesDescriptor("Task", SbyColors.TASK),
            AttributesDescriptor("Task tag", SbyColors.TAG),
            AttributesDescriptor("Keyword", SbyColors.KEYWORD),
            AttributesDescriptor("Block and Python markers", SbyColors.MARKER),
            AttributesDescriptor("Option", SbyColors.OPTION),
            AttributesDescriptor("Engine", SbyColors.ENGINE),
            AttributesDescriptor("Yosys command", SbyColors.COMMAND),
            AttributesDescriptor("Flag", SbyColors.FLAG),
            AttributesDescriptor("File", SbyColors.PATH),
            AttributesDescriptor("Value", SbyColors.VALUE),
            AttributesDescriptor("Number", SbyColors.NUMBER),
            AttributesDescriptor("Comment", SbyColors.COMMENT),
            AttributesDescriptor("Colon", SbyColors.COLON),
        )

        val DEMO = """
            # Formal checks of the FIFO
            [tasks]
            bmc
            prove default
            cover

            [options]
            bmc: mode bmc
            prove: mode prove
            cover: mode cover
            depth 20
            ~cover:
            multiclock on
            --

            [engines]
            smtbmc --nomem yices
            abc pdr

            [script]
            read -formal -DFORMAL fifo.sv
            prep -top fifo

            [files]
            rtl/fifo.sv
        """.trimIndent()
    }
}

class SbyCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
