package com.jotrorox.verilogtoolbox.gowin

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
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
object GowinCstColors {
    @JvmField val COMMENT = createTextAttributesKey("GOWIN_CST_COMMENT", Default.LINE_COMMENT)
    @JvmField val STRING = createTextAttributesKey("GOWIN_CST_STRING", Default.STRING)
    @JvmField val KEYWORD = createTextAttributesKey("GOWIN_CST_KEYWORD", Default.KEYWORD)
    @JvmField val STATEMENT = createTextAttributesKey("GOWIN_CST_STATEMENT", Default.FUNCTION_CALL)
    @JvmField val ATTRIBUTE = createTextAttributesKey("GOWIN_CST_ATTRIBUTE", Default.METADATA)
    @JvmField val VALUE = createTextAttributesKey("GOWIN_CST_VALUE", Default.CONSTANT)
    @JvmField val NUMBER = createTextAttributesKey("GOWIN_CST_NUMBER", Default.NUMBER)
    @JvmField val LOCATION = createTextAttributesKey("GOWIN_CST_LOCATION", Default.INSTANCE_FIELD)
    @JvmField val OPERATOR = createTextAttributesKey("GOWIN_CST_OPERATOR", Default.OPERATION_SIGN)
    @JvmField val COMMA = createTextAttributesKey("GOWIN_CST_COMMA", Default.COMMA)
    @JvmField val SEMICOLON = createTextAttributesKey("GOWIN_CST_SEMICOLON", Default.SEMICOLON)
}

class GowinCstSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GowinCstLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    private companion object {
        val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            GowinCstTokenTypes.COMMENT to GowinCstColors.COMMENT,
            GowinCstTokenTypes.STRING to GowinCstColors.STRING,
            GowinCstTokenTypes.KEYWORD to GowinCstColors.KEYWORD,
            GowinCstTokenTypes.STATEMENT to GowinCstColors.STATEMENT,
            GowinCstTokenTypes.ATTRIBUTE to GowinCstColors.ATTRIBUTE,
            GowinCstTokenTypes.VALUE to GowinCstColors.VALUE,
            GowinCstTokenTypes.NUMBER to GowinCstColors.NUMBER,
            GowinCstTokenTypes.LOCATION to GowinCstColors.LOCATION,
            GowinCstTokenTypes.EQ to GowinCstColors.OPERATOR,
            GowinCstTokenTypes.BAR to GowinCstColors.OPERATOR,
            GowinCstTokenTypes.COMMA to GowinCstColors.COMMA,
            GowinCstTokenTypes.SEMICOLON to GowinCstColors.SEMICOLON,
        )
    }
}

class GowinCstSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        GowinCstSyntaxHighlighter()
}

/** Settings | Editor | Color Scheme | Gowin Constraints. */
class GowinCstColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Gowin Constraints"
    override fun getIcon(): Icon = GowinCstFileType.icon
    override fun getHighlighter(): SyntaxHighlighter = GowinCstSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getDemoText(): String = DEMO

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Known constraint", GowinCstColors.KEYWORD),
            AttributesDescriptor("Other constraint", GowinCstColors.STATEMENT),
            AttributesDescriptor("Object name", GowinCstColors.STRING),
            AttributesDescriptor("Pin or site", GowinCstColors.LOCATION),
            AttributesDescriptor("Attribute name", GowinCstColors.ATTRIBUTE),
            AttributesDescriptor("Attribute value", GowinCstColors.VALUE),
            AttributesDescriptor("Number", GowinCstColors.NUMBER),
            AttributesDescriptor("Comment", GowinCstColors.COMMENT),
            AttributesDescriptor("Braces and Operators//Operator", GowinCstColors.OPERATOR),
            AttributesDescriptor("Braces and Operators//Comma", GowinCstColors.COMMA),
            AttributesDescriptor("Braces and Operators//Semicolon", GowinCstColors.SEMICOLON),
        )

        val DEMO = """
            // Tang Primer 25K
            IO_LOC  "clk" E2;
            IO_PORT "clk" IO_TYPE=LVCMOS33 PULL_MODE=NONE DRIVE=OFF BANK_VCCIO=3.3;
            IO_LOC  "led[0]" G5;
            IO_PORT "led[0]" IO_TYPE=LVCMOS33 PULL_MODE=NONE DRIVE=8;
            IO_LOC  "lvds_p" A1,B1;
            /* placement */
            INS_LOC "counter_reg" R2C3[0][A];
            CLOCK_LOC "clk" BUFG;
        """.trimIndent()
    }
}

class GowinCstCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class GowinCstQuoteHandler : SimpleTokenSetQuoteHandler(GowinCstTokenTypes.STRING)
