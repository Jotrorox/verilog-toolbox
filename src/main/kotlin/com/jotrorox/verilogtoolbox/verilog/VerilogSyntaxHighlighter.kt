package com.jotrorox.verilogtoolbox.verilog

import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.Lexer
import com.intellij.lexer.StringLiteralLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.StringEscapesTokenTypes
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object VerilogColors {
    @JvmField val KEYWORD = createTextAttributesKey("VERILOG_KEYWORD", Default.KEYWORD)
    @JvmField val TYPE = createTextAttributesKey("VERILOG_TYPE", Default.KEYWORD)
    @JvmField val IDENTIFIER = createTextAttributesKey("VERILOG_IDENTIFIER", Default.IDENTIFIER)
    @JvmField val DECLARATION = createTextAttributesKey("VERILOG_DECLARATION", Default.FUNCTION_DECLARATION)
    @JvmField val SYSTEM_TASK = createTextAttributesKey("VERILOG_SYSTEM_TASK", Default.FUNCTION_CALL)
    @JvmField val DIRECTIVE = createTextAttributesKey("VERILOG_DIRECTIVE", Default.METADATA)
    @JvmField val MACRO = createTextAttributesKey("VERILOG_MACRO", Default.METADATA)
    @JvmField val NUMBER = createTextAttributesKey("VERILOG_NUMBER", Default.NUMBER)
    @JvmField val STRING = createTextAttributesKey("VERILOG_STRING", Default.STRING)
    @JvmField val VALID_ESCAPE = createTextAttributesKey("VERILOG_VALID_ESCAPE", Default.VALID_STRING_ESCAPE)
    @JvmField val INVALID_ESCAPE = createTextAttributesKey("VERILOG_INVALID_ESCAPE", Default.INVALID_STRING_ESCAPE)
    @JvmField val LINE_COMMENT = createTextAttributesKey("VERILOG_LINE_COMMENT", Default.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = createTextAttributesKey("VERILOG_BLOCK_COMMENT", Default.BLOCK_COMMENT)
    @JvmField val OPERATOR = createTextAttributesKey("VERILOG_OPERATOR", Default.OPERATION_SIGN)
    @JvmField val PARENTHESES = createTextAttributesKey("VERILOG_PARENTHESES", Default.PARENTHESES)
    @JvmField val BRACKETS = createTextAttributesKey("VERILOG_BRACKETS", Default.BRACKETS)
    @JvmField val BRACES = createTextAttributesKey("VERILOG_BRACES", Default.BRACES)
    @JvmField val SEMICOLON = createTextAttributesKey("VERILOG_SEMICOLON", Default.SEMICOLON)
    @JvmField val COMMA = createTextAttributesKey("VERILOG_COMMA", Default.COMMA)
    @JvmField val DOT = createTextAttributesKey("VERILOG_DOT", Default.DOT)
    @JvmField val BAD_CHARACTER = createTextAttributesKey("VERILOG_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
}

class VerilogSyntaxHighlighter : SyntaxHighlighterBase() {
    /** The plain lexer plus a layer that splits escape sequences out of string literals. */
    override fun getHighlightingLexer(): Lexer = object : LayeredLexer(VerilogLexer()) {
        init {
            registerSelfStoppingLayer(
                StringLiteralLexer('"', VerilogTokenTypes.STRING),
                arrayOf(VerilogTokenTypes.STRING),
                IElementType.EMPTY_ARRAY,
            )
        }
    }

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        pack(if (tokenType in VerilogTokenTypes.KEYWORDS) VerilogColors.KEYWORD else KEYS[tokenType])

    private companion object {
        val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            VerilogTokenTypes.TYPE to VerilogColors.TYPE,
            VerilogTokenTypes.IDENTIFIER to VerilogColors.IDENTIFIER,
            VerilogTokenTypes.SYSTEM_TASK to VerilogColors.SYSTEM_TASK,
            VerilogTokenTypes.DIRECTIVE to VerilogColors.DIRECTIVE,
            VerilogTokenTypes.MACRO to VerilogColors.MACRO,
            VerilogTokenTypes.NUMBER to VerilogColors.NUMBER,
            VerilogTokenTypes.STRING to VerilogColors.STRING,
            StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN to VerilogColors.VALID_ESCAPE,
            StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN to VerilogColors.INVALID_ESCAPE,
            StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN to VerilogColors.INVALID_ESCAPE,
            VerilogTokenTypes.LINE_COMMENT to VerilogColors.LINE_COMMENT,
            VerilogTokenTypes.BLOCK_COMMENT to VerilogColors.BLOCK_COMMENT,
            VerilogTokenTypes.OPERATOR to VerilogColors.OPERATOR,
            VerilogTokenTypes.LPAREN to VerilogColors.PARENTHESES,
            VerilogTokenTypes.RPAREN to VerilogColors.PARENTHESES,
            VerilogTokenTypes.LBRACKET to VerilogColors.BRACKETS,
            VerilogTokenTypes.RBRACKET to VerilogColors.BRACKETS,
            VerilogTokenTypes.LBRACE to VerilogColors.BRACES,
            VerilogTokenTypes.RBRACE to VerilogColors.BRACES,
            VerilogTokenTypes.SEMICOLON to VerilogColors.SEMICOLON,
            VerilogTokenTypes.COMMA to VerilogColors.COMMA,
            VerilogTokenTypes.DOT to VerilogColors.DOT,
            TokenType.BAD_CHARACTER to VerilogColors.BAD_CHARACTER,
        )
    }
}

class VerilogSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        VerilogSyntaxHighlighter()
}
