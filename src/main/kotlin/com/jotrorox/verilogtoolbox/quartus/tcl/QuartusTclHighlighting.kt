package com.jotrorox.verilogtoolbox.quartus.tcl

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
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
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

/** Every key falls back to a default language colour, so no colour scheme needs to mention them. */
object QuartusTclColors {
    @JvmField val COMMENT = createTextAttributesKey("QUARTUS_TCL_COMMENT", Default.LINE_COMMENT)
    @JvmField val STRING = createTextAttributesKey("QUARTUS_TCL_STRING", Default.STRING)
    @JvmField val NUMBER = createTextAttributesKey("QUARTUS_TCL_NUMBER", Default.NUMBER)
    @JvmField val VARIABLE = createTextAttributesKey("QUARTUS_TCL_VARIABLE", Default.INSTANCE_FIELD)
    @JvmField val OPTION = createTextAttributesKey("QUARTUS_TCL_OPTION", Default.METADATA)
    @JvmField val KNOWN_COMMAND = createTextAttributesKey("QUARTUS_TCL_KNOWN_COMMAND", Default.KEYWORD)
    @JvmField val COMMAND = createTextAttributesKey("QUARTUS_TCL_COMMAND", Default.FUNCTION_CALL)
    @JvmField val ASSIGNMENT_NAME = createTextAttributesKey("QUARTUS_TCL_ASSIGNMENT_NAME", Default.CONSTANT)
    @JvmField val WORD = createTextAttributesKey("QUARTUS_TCL_WORD", Default.IDENTIFIER)
    @JvmField val BRACES = createTextAttributesKey("QUARTUS_TCL_BRACES", Default.BRACES)
    @JvmField val BRACKETS = createTextAttributesKey("QUARTUS_TCL_BRACKETS", Default.BRACKETS)
    @JvmField val PARENTHESES = createTextAttributesKey("QUARTUS_TCL_PARENTHESES", Default.PARENTHESES)
    @JvmField val SEMICOLON = createTextAttributesKey("QUARTUS_TCL_SEMICOLON", Default.SEMICOLON)
}

class QuartusTclSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = QuartusTclLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    private companion object {
        val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            QuartusTclTokenTypes.COMMENT to QuartusTclColors.COMMENT,
            QuartusTclTokenTypes.STRING to QuartusTclColors.STRING,
            QuartusTclTokenTypes.NUMBER to QuartusTclColors.NUMBER,
            QuartusTclTokenTypes.VARIABLE to QuartusTclColors.VARIABLE,
            QuartusTclTokenTypes.OPTION to QuartusTclColors.OPTION,
            QuartusTclTokenTypes.KNOWN_COMMAND to QuartusTclColors.KNOWN_COMMAND,
            QuartusTclTokenTypes.COMMAND to QuartusTclColors.COMMAND,
            QuartusTclTokenTypes.ASSIGNMENT_NAME to QuartusTclColors.ASSIGNMENT_NAME,
            QuartusTclTokenTypes.WORD to QuartusTclColors.WORD,
            QuartusTclTokenTypes.LBRACE to QuartusTclColors.BRACES,
            QuartusTclTokenTypes.RBRACE to QuartusTclColors.BRACES,
            QuartusTclTokenTypes.LBRACKET to QuartusTclColors.BRACKETS,
            QuartusTclTokenTypes.RBRACKET to QuartusTclColors.BRACKETS,
            QuartusTclTokenTypes.LPAREN to QuartusTclColors.PARENTHESES,
            QuartusTclTokenTypes.RPAREN to QuartusTclColors.PARENTHESES,
            QuartusTclTokenTypes.SEMICOLON to QuartusTclColors.SEMICOLON,
        )
    }
}

class QuartusTclSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        QuartusTclSyntaxHighlighter()
}

/** Settings | Editor | Color Scheme | Quartus Tcl. */
class QuartusTclColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Quartus Tcl"
    override fun getIcon(): Icon = QuartusTclSettingsFileType.icon
    override fun getHighlighter(): SyntaxHighlighter = QuartusTclSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getDemoText(): String = DEMO

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Known command", QuartusTclColors.KNOWN_COMMAND),
            AttributesDescriptor("Other command", QuartusTclColors.COMMAND),
            AttributesDescriptor("Option flag", QuartusTclColors.OPTION),
            AttributesDescriptor("Assignment name", QuartusTclColors.ASSIGNMENT_NAME),
            AttributesDescriptor("Variable", QuartusTclColors.VARIABLE),
            AttributesDescriptor("Word", QuartusTclColors.WORD),
            AttributesDescriptor("Number", QuartusTclColors.NUMBER),
            AttributesDescriptor("String", QuartusTclColors.STRING),
            AttributesDescriptor("Comment", QuartusTclColors.COMMENT),
            AttributesDescriptor("Braces and Operators//Braces", QuartusTclColors.BRACES),
            AttributesDescriptor("Braces and Operators//Brackets", QuartusTclColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Parentheses", QuartusTclColors.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Semicolon", QuartusTclColors.SEMICOLON),
        )

        val DEMO = """
            # Settings file (.qsf)
            set_global_assignment -name FAMILY "Cyclone IV E"
            set_global_assignment -name DEVICE EP4CE22F17C6
            set_global_assignment -name TOP_LEVEL_ENTITY blinky
            set_global_assignment -name SEED 3
            set_location_assignment PIN_R8 -to clk
            set_instance_assignment -name IO_STANDARD "3.3-V LVTTL" -to led[0]
            PROJECT_REVISION = "blinky"

            # Constraints file (.sdc)
            create_clock -name clk -period 20.000 [get_ports {clk}]
            derive_pll_clocks; derive_clock_uncertainty
            set out_delay 2.5
            set_output_delay -clock clk -max ${'$'}out_delay [get_ports {led[*]}]
            foreach port [list a b] {
                set_false_path -from [get_ports ${'$'}port] -to ${'$'}::env(SINK)
            }
            my_procedure (1)
        """.trimIndent()
    }
}

class QuartusTclCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class QuartusTclBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(QuartusTclTokenTypes.LBRACE, QuartusTclTokenTypes.RBRACE, true),
            BracePair(QuartusTclTokenTypes.LBRACKET, QuartusTclTokenTypes.RBRACKET, false),
            BracePair(QuartusTclTokenTypes.LPAREN, QuartusTclTokenTypes.RPAREN, false),
        )
    }
}

class QuartusTclQuoteHandler : SimpleTokenSetQuoteHandler(QuartusTclTokenTypes.STRING)
