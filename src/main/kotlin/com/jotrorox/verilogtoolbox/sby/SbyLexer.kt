package com.jotrorox.verilogtoolbox.sby

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class SbyTokenType(debugName: String) : IElementType(debugName, SbyLanguage)

object SbyTokenTypes {
    /** `# to the end of the line`. */
    @JvmField val COMMENT = SbyTokenType("COMMENT")
    /** A section header, such as `[options]` or `[file defines.sv]`. */
    @JvmField val SECTION = SbyTokenType("SECTION")
    /** A task the `[tasks]` section declares. */
    @JvmField val TASK = SbyTokenType("TASK")
    /** The task in front of a line that only that task sees, such as `bmc` in `bmc: mode bmc`, or `~bmc` for all others. */
    @JvmField val TAG = SbyTokenType("TAG")
    @JvmField val COLON = SbyTokenType(":")
    /** `default` in the `[tasks]` section. */
    @JvmField val KEYWORD = SbyTokenType("KEYWORD")
    /** `--`, which ends the lines of a task tag that stood alone. */
    @JvmField val BLOCK_END = SbyTokenType("BLOCK_END")
    /** `--pycode-begin--` or `--pycode-end--`. */
    @JvmField val PYCODE_MARKER = SbyTokenType("PYCODE_MARKER")
    /** The name of an option, such as `mode` or `depth`. */
    @JvmField val OPTION = SbyTokenType("OPTION")
    /** The engine of a line of `[engines]`, such as `smtbmc` or `abc`. */
    @JvmField val ENGINE = SbyTokenType("ENGINE")
    /** The Yosys command of a line of `[script]`, such as `read` or `prep`. */
    @JvmField val COMMAND = SbyTokenType("COMMAND")
    /** An option of an engine or command, such as `-formal` or `--nomem`. */
    @JvmField val FLAG = SbyTokenType("FLAG")
    /** A file of `[files]`. */
    @JvmField val PATH = SbyTokenType("PATH")
    @JvmField val NUMBER = SbyTokenType("NUMBER")
    /** Any other word of a line: an option's value, a solver, a command's argument. */
    @JvmField val VALUE = SbyTokenType("VALUE")
    /** What is taken as it is: the contents of a `[file]` section, Python code, text before the first section. */
    @JvmField val TEXT = SbyTokenType("TEXT")

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
}

/** The sections SymbiYosys knows, by the first word of their header. */
internal enum class SbySection {
    NONE, TASKS, OPTIONS, ENGINES, SCRIPT, FILES, FILE, OTHER;

    companion object {
        private val HEADER = Regex("""^\s*\[(.*)]\s*$""")

        /** The section that [line] starts, or null if it is no section header. */
        fun of(line: CharSequence): SbySection? {
            val name = HEADER.find(line)?.groupValues?.get(1)?.trim()?.substringBefore(' ') ?: return null
            return when (name) {
                "tasks" -> TASKS
                "options" -> OPTIONS
                "engines" -> ENGINES
                "script" -> SCRIPT
                "files" -> FILES
                "file" -> FILE
                else -> OTHER
            }
        }
    }
}

/**
 * Hand-written lexer for `.sby` files.
 *
 * The state is the section, where in its line the lexer is and whether it is in a Python block, so that the
 * editor can restart lexing at any token. The contents of a `[file]` section and of a Python block are taken
 * as they are, in tokens that end at the start of a line.
 */
class SbyLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferStart = 0
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0

    private var section = SbySection.NONE
    private var position = LINE_START
    private var pycode = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferStart = startOffset
        bufferEnd = endOffset
        tokenEnd = startOffset
        section = SbySection.entries[initialState and SECTION_MASK]
        position = (initialState shr POSITION_SHIFT) and POSITION_MASK
        pycode = initialState and PYCODE_FLAG != 0
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    private fun state(): Int = section.ordinal or (position shl POSITION_SHIFT) or (if (pycode) PYCODE_FLAG else 0)

    override fun advance() {
        tokenState = state()
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        when {
            pycode -> lexPycode(start)
            section == SbySection.FILE && !(position == LINE_START && SbySection.of(line(start)) != null) -> {
                // Verbatim up to the next section header.
                position = LINE_START
                token(SbyTokenTypes.TEXT, verbatimEnd(start) { SbySection.of(it) != null })
            }
            buffer[start].isWhitespace() -> {
                val end = skipWhile(start) { it.isWhitespace() }
                if ((start until end).any { buffer[it] == '\n' }) position = LINE_START
                token(TokenType.WHITE_SPACE, end)
            }
            buffer[start] == '#' -> token(SbyTokenTypes.COMMENT, lineEnd(start))
            position == LINE_START || position == AFTER_TAGS -> lexLineStart(start)
            position == TAGS -> lexTags(start)
            else -> lexArgument(start)
        }
    }

    private fun lexPycode(start: Int) {
        if (line(start).trim() == PYCODE_END) {
            pycode = false
            position = ARGUMENTS
            token(SbyTokenTypes.PYCODE_MARKER, lineEnd(start))
        } else {
            token(SbyTokenTypes.TEXT, verbatimEnd(start) { it.trim() == PYCODE_END })
        }
    }

    private fun lexLineStart(start: Int) {
        val line = line(start)
        val text = line.trim()
        when {
            position == LINE_START && SbySection.of(line) != null -> {
                section = SbySection.of(line)!!
                position = ARGUMENTS
                token(SbyTokenTypes.SECTION, start + line.trimEnd().length)
            }
            text == BLOCK_END -> {
                position = ARGUMENTS
                token(SbyTokenTypes.BLOCK_END, start + 2)
            }
            text.startsWith(PYCODE_BEGIN) -> {
                // The marker takes its line break, so that every token of the block starts a line.
                pycode = true
                position = LINE_START
                val end = lineEnd(start)
                token(SbyTokenTypes.PYCODE_MARKER, if (end < bufferEnd) end + 1 else end)
            }
            section == SbySection.TASKS -> {
                // `a b: tags` declares several tasks; `a tags` one.
                position = if (':' in line.toString().substringBefore('#')) TAGS else ARGUMENTS
                token(SbyTokenTypes.TASK, wordEnd(start, stopAtColon = true))
            }
            position == LINE_START && TAG_LINE.containsMatchIn(line) -> lexTags(start)
            else -> {
                position = ARGUMENTS
                val type = when (section) {
                    SbySection.OPTIONS, SbySection.OTHER -> SbyTokenTypes.OPTION
                    SbySection.ENGINES -> SbyTokenTypes.ENGINE
                    SbySection.SCRIPT -> SbyTokenTypes.COMMAND
                    SbySection.FILES -> SbyTokenTypes.PATH
                    else -> SbyTokenTypes.TEXT
                }
                token(type, wordEnd(start, stopAtColon = false))
            }
        }
    }

    /** The task names in front of the `:` of a task-specific line, or of a line of `[tasks]` that has one. */
    private fun lexTags(start: Int) {
        if (buffer[start] == ':') {
            position = if (section == SbySection.TASKS) ARGUMENTS else AFTER_TAGS
            return token(SbyTokenTypes.COLON, start + 1)
        }
        position = TAGS
        token(if (section == SbySection.TASKS) SbyTokenTypes.TASK else SbyTokenTypes.TAG, wordEnd(start, stopAtColon = true))
    }

    private fun lexArgument(start: Int) {
        val end = wordEnd(start, stopAtColon = false)
        val word = buffer.subSequence(start, end).toString()
        val type = when (section) {
            SbySection.TASKS -> if (word == "default") SbyTokenTypes.KEYWORD else SbyTokenTypes.TAG
            SbySection.FILES -> SbyTokenTypes.PATH
            SbySection.ENGINES, SbySection.SCRIPT ->
                if (word.startsWith("-") && word.length > 1) SbyTokenTypes.FLAG
                else if (NUMBER.matches(word)) SbyTokenTypes.NUMBER else SbyTokenTypes.VALUE
            else -> if (NUMBER.matches(word)) SbyTokenTypes.NUMBER else SbyTokenTypes.VALUE
        }
        token(type, end)
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end
    }

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    private fun lineEnd(from: Int): Int = skipWhile(from) { it != '\n' }

    /** From [from] to the end of its line, without the line break. */
    private fun line(from: Int): CharSequence = buffer.subSequence(from, lineEnd(from))

    /**
     * The end of text taken as it is: the rest of the line at [from] and every following line, up to the start
     * of the first line that [stop] holds for.
     */
    private inline fun verbatimEnd(from: Int, stop: (CharSequence) -> Boolean): Int {
        var lineStart = lineEnd(from) + 1
        while (lineStart < bufferEnd) {
            if (stop(line(lineStart))) return lineStart
            lineStart = lineEnd(lineStart) + 1
        }
        return bufferEnd
    }

    private fun wordEnd(start: Int, stopAtColon: Boolean): Int {
        val end = skipWhile(start) { !it.isWhitespace() && !(stopAtColon && it == ':') }
        return maxOf(end, start + 1)
    }

    private companion object {
        const val LINE_START = 0
        /** In front of the `:` of a task tag, or among the tasks that a line of `[tasks]` declares. */
        const val TAGS = 1
        /** Behind a task tag: the line's first word comes, but no other tag. */
        const val AFTER_TAGS = 2
        const val ARGUMENTS = 3

        const val SECTION_MASK = 0x7
        const val POSITION_SHIFT = 3
        const val POSITION_MASK = 0x3
        const val PYCODE_FLAG = 0x20

        const val BLOCK_END = "--"
        const val PYCODE_BEGIN = "--pycode-begin--"
        const val PYCODE_END = "--pycode-end--"

        /** `bmc:` or `~bmc:` in front of a line. A `:` followed by more, as in `C:\`, is part of a word. */
        val TAG_LINE = Regex("""^~?[A-Za-z_]\w*:(?=\s|$)""")
        val NUMBER = Regex("""[+-]?\d+(\.\d+)?""")
    }
}
