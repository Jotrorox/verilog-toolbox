package com.jotrorox.verilogtoolbox

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Hand-written lexer for Verilog and SystemVerilog. It keeps no state between tokens. */
class VerilogLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        val c = buffer[start]
        when {
            c.isWhitespace() -> token(TokenType.WHITE_SPACE, skipWhile(start) { it.isWhitespace() })
            c == '/' && at(start + 1) == '/' -> token(VerilogTokenTypes.LINE_COMMENT, skipWhile(start) { it != '\n' })
            c == '/' && at(start + 1) == '*' -> token(VerilogTokenTypes.BLOCK_COMMENT, blockCommentEnd(start))
            c == '"' -> token(VerilogTokenTypes.STRING, stringEnd(start))
            c.isAsciiDigit() -> token(VerilogTokenTypes.NUMBER, numberEnd(start))
            c == '\'' -> lexTick(start)
            c.isIdentifierStart() -> {
                val end = skipWhile(start) { it.isIdentifierPart() }
                token(VerilogKeywords.tokenTypeOf(buffer.subSequence(start, end).toString()), end)
            }
            c == '$' ->
                if (at(start + 1).isIdentifierPart()) {
                    token(VerilogTokenTypes.SYSTEM_TASK, skipWhile(start + 1) { it.isIdentifierPart() })
                } else {
                    token(VerilogTokenTypes.OPERATOR, start + 1)
                }
            c == '`' -> lexBacktick(start)
            // Escaped identifier: a backslash followed by anything up to the next whitespace.
            c == '\\' -> token(VerilogTokenTypes.IDENTIFIER, skipWhile(start + 1) { !it.isWhitespace() })
            c == '(' -> token(VerilogTokenTypes.LPAREN, start + 1)
            c == ')' -> token(VerilogTokenTypes.RPAREN, start + 1)
            c == '[' -> token(VerilogTokenTypes.LBRACKET, start + 1)
            c == ']' -> token(VerilogTokenTypes.RBRACKET, start + 1)
            c == '{' -> token(VerilogTokenTypes.LBRACE, start + 1)
            c == '}' -> token(VerilogTokenTypes.RBRACE, start + 1)
            c == ';' -> token(VerilogTokenTypes.SEMICOLON, start + 1)
            c == ',' -> token(VerilogTokenTypes.COMMA, start + 1)
            c == '.' -> token(VerilogTokenTypes.DOT, start + 1)
            c in OPERATOR_CHARS -> token(VerilogTokenTypes.OPERATOR, operatorEnd(start))
            else -> token(TokenType.BAD_CHARACTER, start + 1)
        }
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end
    }

    /** The character at [offset], or NUL past the end of the buffer. */
    private fun at(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    private fun blockCommentEnd(start: Int): Int {
        var i = start + 2
        while (i < bufferEnd) {
            if (buffer[i] == '*' && at(i + 1) == '/') return i + 2
            i++
        }
        return bufferEnd
    }

    /** A string ends at its closing quote, or at the end of the line if it is left open. */
    private fun stringEnd(start: Int): Int {
        var i = start + 1
        while (i < bufferEnd) {
            when (buffer[i]) {
                '"' -> return i + 1
                '\n' -> return i
                '\\' -> i++
            }
            i++
        }
        return bufferEnd
    }

    /** Operators are lexed as runs (`<=`, `===`, `|->`), stopping where a comment begins. */
    private fun operatorEnd(start: Int): Int {
        var i = start + 1
        while (i < bufferEnd && buffer[i] in OPERATOR_CHARS) {
            if (buffer[i] == '/' && (at(i + 1) == '/' || at(i + 1) == '*')) break
            i++
        }
        return i
    }

    private fun numberEnd(start: Int): Int {
        var i = skipWhile(start) { it.isAsciiDigit() || it == '_' }
        // Sized based literal such as 8'hFF; a space may separate the size from the base.
        basedLiteralEnd(skipWhile(i) { it == ' ' || it == '\t' })?.let { return it }
        if (at(i) == '.' && at(i + 1).isAsciiDigit()) {
            i = skipWhile(i + 1) { it.isAsciiDigit() || it == '_' }
        }
        if (at(i) == 'e' || at(i) == 'E') {
            val digits = if (at(i + 1) == '+' || at(i + 1) == '-') i + 2 else i + 1
            if (at(digits).isAsciiDigit()) i = skipWhile(digits) { it.isAsciiDigit() || it == '_' }
        }
        // Time literal such as 10ns.
        for (unit in TIME_UNITS) {
            val end = i + unit.length
            if (end <= bufferEnd && buffer.subSequence(i, end).toString() == unit && !at(end).isIdentifierPart()) {
                return end
            }
        }
        return i
    }

    /** End of a based literal (`'hFF`, `'sb1010`) whose apostrophe is at [tick], or null if there is none. */
    private fun basedLiteralEnd(tick: Int): Int? {
        if (at(tick) != '\'') return null
        var i = tick + 1
        if (at(i) == 's' || at(i) == 'S') i++
        if (at(i).lowercaseChar() !in "bodh") return null
        i = skipWhile(i + 1) { it == ' ' || it == '\t' }
        return skipWhile(i) { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '?' }
    }

    private fun lexTick(start: Int) {
        val basedEnd = basedLiteralEnd(start)
        when {
            basedEnd != null -> token(VerilogTokenTypes.NUMBER, basedEnd)
            // Unbased unsized literal: '0, '1, 'x, 'z.
            at(start + 1).lowercaseChar() in "01xz" && !at(start + 2).isIdentifierPart() ->
                token(VerilogTokenTypes.NUMBER, start + 2)
            // Cast or assignment pattern: type'(x), '{a, b}.
            else -> token(VerilogTokenTypes.OPERATOR, start + 1)
        }
    }

    private fun lexBacktick(start: Int) {
        if (!at(start + 1).isIdentifierStart()) {
            // Macro body punctuation such as `` and `".
            token(VerilogTokenTypes.MACRO, start + 1)
            return
        }
        val end = skipWhile(start + 1) { it.isIdentifierPart() }
        val name = buffer.subSequence(start + 1, end).toString()
        token(if (VerilogKeywords.isDirective(name)) VerilogTokenTypes.DIRECTIVE else VerilogTokenTypes.MACRO, end)
    }

    private companion object {
        const val OPERATOR_CHARS = "+-*/%=!<>&|^~?:@#"
        val TIME_UNITS = listOf("ms", "us", "ns", "ps", "fs", "s")

        fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
        fun Char.isIdentifierStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '_'
        fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isAsciiDigit() || this == '$'
    }
}
