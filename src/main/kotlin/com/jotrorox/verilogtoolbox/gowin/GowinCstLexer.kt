package com.jotrorox.verilogtoolbox.gowin

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class GowinCstTokenType(debugName: String) : IElementType(debugName, GowinCstLanguage)

object GowinCstTokenTypes {
    /** `// to the end of the line` or `/* a block */`. */
    @JvmField val COMMENT = GowinCstTokenType("COMMENT")
    /** The quoted name of a port, instance or group, such as `"led[0]"`. */
    @JvmField val STRING = GowinCstTokenType("STRING")
    /** A constraint the tools know, such as `IO_LOC` or `IO_PORT`. */
    @JvmField val KEYWORD = GowinCstTokenType("KEYWORD")
    /** Any other word that starts a statement. */
    @JvmField val STATEMENT = GowinCstTokenType("STATEMENT")
    /** The name of an attribute: the word before `=`, such as `IO_TYPE`. */
    @JvmField val ATTRIBUTE = GowinCstTokenType("ATTRIBUTE")
    /** The value after `=` when it is a word, such as `LVCMOS33`. */
    @JvmField val VALUE = GowinCstTokenType("VALUE")
    /** The value after `=` when it is a number, such as `8` or `3.3`. */
    @JvmField val NUMBER = GowinCstTokenType("NUMBER")
    /** Any other word of a statement: a pin such as `E2` or `35`, a site such as `R2C3[0][A]`, a clock buffer. */
    @JvmField val LOCATION = GowinCstTokenType("LOCATION")
    @JvmField val EQ = GowinCstTokenType("=")
    @JvmField val COMMA = GowinCstTokenType(",")
    @JvmField val BAR = GowinCstTokenType("|")
    @JvmField val SEMICOLON = GowinCstTokenType(";")

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
}

/** The constraints of Gowin's physical constraints guide (SUG935) that the editor knows by name. */
internal object GowinCstKeywords {
    val ALL = setOf("IO_LOC", "IO_PORT", "INS_LOC", "GRP_LOC", "CLOCK_LOC")
}

/**
 * Hand-written lexer for Gowin `.cst` files.
 *
 * A statement is a constraint name, a quoted object name and then locations or `NAME=VALUE` attributes, ended by
 * `;`. The only state is where in a statement the lexer is, so the editor can restart lexing at any token.
 */
class GowinCstLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0
    private var position = STATEMENT_START

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        position = initialState
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenState = position
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        val c = buffer[start]
        when {
            c.isWhitespace() -> token(TokenType.WHITE_SPACE, skipWhile(start) { it.isWhitespace() })
            c == '/' && at(start + 1) == '/' -> token(GowinCstTokenTypes.COMMENT, skipWhile(start) { it != '\n' })
            c == '/' && at(start + 1) == '*' -> token(GowinCstTokenTypes.COMMENT, blockCommentEnd(start))
            c == ';' -> {
                position = STATEMENT_START
                token(GowinCstTokenTypes.SEMICOLON, start + 1)
            }
            c == '=' -> {
                position = AFTER_EQUALS
                token(GowinCstTokenTypes.EQ, start + 1)
            }
            c == ',' -> argument(GowinCstTokenTypes.COMMA, start + 1)
            // Separates alternatives: the word after it is of the same kind as the one before.
            c == '|' -> token(GowinCstTokenTypes.BAR, start + 1)
            c == '"' -> argument(GowinCstTokenTypes.STRING, stringEnd(start))
            else -> lexWord(start)
        }
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end
    }

    private fun argument(type: IElementType, end: Int) {
        position = ARGUMENT
        token(type, end)
    }

    private fun lexWord(start: Int) {
        val end = wordEnd(start)
        val word = buffer.subSequence(start, end).toString()
        val type = when {
            isFollowedByEquals(end) -> GowinCstTokenTypes.ATTRIBUTE
            position == AFTER_EQUALS ->
                if (NUMBER.matches(word)) GowinCstTokenTypes.NUMBER else GowinCstTokenTypes.VALUE
            // Also after a forgotten `;`: no pin or site is called IO_LOC.
            word in GowinCstKeywords.ALL -> GowinCstTokenTypes.KEYWORD
            position == STATEMENT_START -> GowinCstTokenTypes.STATEMENT
            else -> GowinCstTokenTypes.LOCATION
        }
        argument(type, end)
    }

    /** The character at [offset], or NUL past the end of the buffer. */
    private fun at(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    /** An unclosed block comment runs to the end of the text. */
    private fun blockCommentEnd(start: Int): Int {
        var i = start + 2
        while (i < bufferEnd) {
            if (buffer[i] == '*' && at(i + 1) == '/') return i + 2
            i++
        }
        return bufferEnd
    }

    /** Names are on one line; an unclosed one ends with it. */
    private fun stringEnd(start: Int): Int {
        val close = skipWhile(start + 1) { it != '"' && it != '\n' }
        return if (at(close) == '"') close + 1 else close
    }

    /** A word ends at whitespace, a separator, a quote or a comment; brackets belong to it, as in `R2C3[0][A]`. */
    private fun wordEnd(start: Int): Int {
        var i = start
        while (i < bufferEnd) {
            val c = buffer[i]
            if (c.isWhitespace() || c in WORD_BREAKS || c == '/' && (at(i + 1) == '/' || at(i + 1) == '*')) break
            i++
        }
        // A lone '/' that starts no comment is a word of its own.
        return maxOf(i, start + 1)
    }

    private fun isFollowedByEquals(end: Int): Boolean = at(skipWhile(end) { it == ' ' || it == '\t' }) == '='

    private companion object {
        const val STATEMENT_START = 0
        const val ARGUMENT = 1
        const val AFTER_EQUALS = 2

        const val WORD_BREAKS = ";=,|\""
        val NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""")
    }
}
