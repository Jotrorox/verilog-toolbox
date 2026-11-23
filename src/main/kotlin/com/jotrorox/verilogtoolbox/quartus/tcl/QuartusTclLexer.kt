package com.jotrorox.verilogtoolbox.quartus.tcl

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class QuartusTclTokenType(debugName: String) : IElementType(debugName, QuartusTclLanguage)

object QuartusTclTokenTypes {
    @JvmField val COMMENT = QuartusTclTokenType("COMMENT")
    @JvmField val STRING = QuartusTclTokenType("STRING")
    @JvmField val NUMBER = QuartusTclTokenType("NUMBER")
    /** `$name`, `${name}` or `$::ns::name(index)`. */
    @JvmField val VARIABLE = QuartusTclTokenType("VARIABLE")
    /** A flag such as `-name` or `-period`. */
    @JvmField val OPTION = QuartusTclTokenType("OPTION")
    /** A command word that Quartus, the Timing Analyzer or Tcl itself defines, such as `create_clock`. */
    @JvmField val KNOWN_COMMAND = QuartusTclTokenType("KNOWN_COMMAND")
    /** Any other command word, such as a procedure of the script's own. */
    @JvmField val COMMAND = QuartusTclTokenType("COMMAND")
    /** The assignment after `-name`, such as `FAMILY`, or the key of a `.qpf` line such as `PROJECT_REVISION`. */
    @JvmField val ASSIGNMENT_NAME = QuartusTclTokenType("ASSIGNMENT_NAME")
    /** Any other bare word: a value, a pin, a file name. */
    @JvmField val WORD = QuartusTclTokenType("WORD")
    @JvmField val LBRACE = QuartusTclTokenType("{")
    @JvmField val RBRACE = QuartusTclTokenType("}")
    @JvmField val LBRACKET = QuartusTclTokenType("[")
    @JvmField val RBRACKET = QuartusTclTokenType("]")
    @JvmField val LPAREN = QuartusTclTokenType("(")
    @JvmField val RPAREN = QuartusTclTokenType(")")
    @JvmField val SEMICOLON = QuartusTclTokenType(";")

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
}

/** The commands the editor knows by name: for highlighting, and per file type for completion. */
internal object QuartusTclCommands {
    /** Commands of Quartus settings files (`.qsf`, `.qip`) and of the `::quartus::project` package. */
    val QUARTUS = """
        set_global_assignment set_location_assignment set_instance_assignment set_parameter set_io_assignment
        set_user_option set_power_file_assignment get_global_assignment get_instance_assignment
        get_location_assignment get_parameter get_all_global_assignments get_all_instance_assignments
        get_all_parameters remove_all_global_assignments remove_all_instance_assignments remove_all_parameters
        export_assignments project_new project_open project_close project_exists create_revision
        set_current_revision get_current_revision delete_revision load_package execute_flow execute_module
        post_message
    """.words()

    /** Commands of Synopsys Design Constraints as the Quartus Timing Analyzer understands them. */
    val SDC = """
        create_clock create_generated_clock derive_pll_clocks derive_clock_uncertainty set_input_delay
        set_output_delay set_false_path set_multicycle_path set_max_delay set_min_delay set_clock_groups
        set_clock_uncertainty set_clock_latency set_input_transition set_max_skew set_net_delay
        set_disable_timing set_clock_sense set_time_format remove_clock remove_input_delay remove_output_delay
        get_ports get_clocks get_pins get_registers get_nets get_cells get_keepers get_nodes get_fanouts
        get_fanins all_inputs all_outputs all_clocks all_registers get_clock_info get_port_info
        get_collection_size query_collection foreach_in_collection post_message
    """.words()

    /** The Tcl built-ins that scripts and constraint files commonly use. */
    val TCL = """
        if else elseif for foreach while proc return expr puts list lindex llength lappend lsearch lsort
        lrange lreplace lassign join split string file incr append array dict info catch error global upvar
        uplevel variable namespace switch break continue eval format regexp regsub source set unset package
        open close gets glob after exit concat subst clock
    """.words()

    /** Commands whose `-name` is followed by an assignment name. */
    val ASSIGNMENT = """
        set_global_assignment set_instance_assignment set_io_assignment get_global_assignment
        get_instance_assignment get_all_global_assignments get_all_instance_assignments
        remove_all_global_assignments remove_all_instance_assignments
    """.words()

    val ALL: Set<String> = QUARTUS + SDC + TCL

    private fun String.words(): Set<String> = split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
}

/**
 * Hand-written lexer for the Tcl of Quartus settings and constraint files.
 *
 * Tcl's meaning depends on where a word stands, so the lexer keeps a little state between tokens: whether
 * the next word starts a command, how deep it is in braces, and whether it is inside an assignment command
 * whose `-name` was just seen. All of it fits in [getState], so the editor can restart lexing at any token.
 */
class QuartusTclLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0

    private var position = COMMAND_POSITION
    private var inAssignment = false
    private var afterName = false
    private var braceDepth = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        position = initialState and POSITION_MASK
        inAssignment = initialState and IN_ASSIGNMENT != 0
        afterName = initialState and AFTER_NAME != 0
        braceDepth = initialState ushr DEPTH_SHIFT
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    private fun encodeState(): Int =
        position or (if (inAssignment) IN_ASSIGNMENT else 0) or (if (afterName) AFTER_NAME else 0) or
            (braceDepth shl DEPTH_SHIFT)

    override fun advance() {
        tokenState = encodeState()
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        val c = buffer[start]
        when {
            c.isWhitespace() -> {
                val end = skipWhile(start) { it.isWhitespace() }
                if ((start until end).any { buffer[it] == '\n' }) endCommand()
                token(TokenType.WHITE_SPACE, end)
            }
            // A backslash-newline joins two lines into one command, so it changes nothing.
            c == '\\' && continuationEnd(start) > 0 ->
                token(TokenType.WHITE_SPACE, skipWhile(continuationEnd(start)) { it == ' ' || it == '\t' })
            c == '#' && position != ARGUMENT_POSITION -> token(QuartusTclTokenTypes.COMMENT, commentEnd(start))
            c == ';' -> {
                endCommand()
                token(QuartusTclTokenTypes.SEMICOLON, start + 1)
            }
            c == '[' -> {
                // Inside braces a bracket may be a literal, as in {*|sync_q[*]}, and settings files write bus
                // bits unescaped, as in -to led[0]: there only known commands count.
                val glued = start > 0 && !buffer[start - 1].isWhitespace() && buffer[start - 1] !in "[{;"
                position = if (braceDepth > 0 || glued) WEAK_COMMAND_POSITION else COMMAND_POSITION
                inAssignment = false
                afterName = false
                token(QuartusTclTokenTypes.LBRACKET, start + 1)
            }
            c == ']' -> argument(QuartusTclTokenTypes.RBRACKET, start + 1)
            c == '{' -> {
                // A brace word may be a script body or a list; only a known command makes it a body.
                braceDepth = minOf(braceDepth + 1, MAX_DEPTH)
                position = WEAK_COMMAND_POSITION
                token(QuartusTclTokenTypes.LBRACE, start + 1)
            }
            c == '}' -> {
                braceDepth = maxOf(braceDepth - 1, 0)
                argument(QuartusTclTokenTypes.RBRACE, start + 1)
            }
            c == '(' -> argument(QuartusTclTokenTypes.LPAREN, start + 1)
            c == ')' -> argument(QuartusTclTokenTypes.RPAREN, start + 1)
            c == '"' -> argument(QuartusTclTokenTypes.STRING, stringEnd(start))
            c == '$' && isVariableStart(start) -> argument(QuartusTclTokenTypes.VARIABLE, variableEnd(start))
            else -> lexWord(start)
        }
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end
    }

    /** A token that is, or ends, an argument of the current command. */
    private fun argument(type: IElementType, end: Int) {
        position = ARGUMENT_POSITION
        afterName = false
        token(type, end)
    }

    private fun endCommand() {
        position = COMMAND_POSITION
        inAssignment = false
        afterName = false
    }

    private fun lexWord(start: Int) {
        val end = wordEnd(start)
        val word = buffer.subSequence(start, end).toString()
        val type = when {
            position != ARGUMENT_POSITION && word in QuartusTclCommands.ALL || word == "else" || word == "elseif" -> {
                if (position != ARGUMENT_POSITION) inAssignment = word in QuartusTclCommands.ASSIGNMENT
                QuartusTclTokenTypes.KNOWN_COMMAND
            }
            // A .qpf line: PROJECT_REVISION = "blinky".
            position == COMMAND_POSITION && isFollowedByEquals(end) -> QuartusTclTokenTypes.ASSIGNMENT_NAME
            position == COMMAND_POSITION -> {
                inAssignment = false
                QuartusTclTokenTypes.COMMAND
            }
            afterName -> QuartusTclTokenTypes.ASSIGNMENT_NAME
            NUMBER.matches(word) -> QuartusTclTokenTypes.NUMBER
            OPTION.matches(word) -> QuartusTclTokenTypes.OPTION
            else -> QuartusTclTokenTypes.WORD
        }
        position = ARGUMENT_POSITION
        afterName = inAssignment && word == "-name"
        token(type, end)
    }

    /** The character at [offset], or NUL past the end of the buffer. */
    private fun at(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    /** The offset after the newline when a backslash-newline starts at [backslash], else -1. */
    private fun continuationEnd(backslash: Int): Int = when {
        at(backslash + 1) == '\n' -> backslash + 2
        at(backslash + 1) == '\r' && at(backslash + 2) == '\n' -> backslash + 3
        else -> -1
    }

    /** A comment runs to the end of the line; like any Tcl line, a backslash-newline continues it. */
    private fun commentEnd(start: Int): Int {
        var i = start
        while (i < bufferEnd) {
            when (buffer[i]) {
                '\\' -> i = if (continuationEnd(i) > 0) continuationEnd(i) else i + 2
                '\n' -> return i
                else -> i++
            }
        }
        return bufferEnd
    }

    /** Tcl strings may span lines; an unclosed one runs to the end of the text. */
    private fun stringEnd(start: Int): Int {
        var i = start + 1
        while (i < bufferEnd) {
            when (buffer[i]) {
                '"' -> return i + 1
                '\\' -> i += 2
                else -> i++
            }
        }
        return bufferEnd
    }

    private fun isVariableStart(dollar: Int): Boolean {
        val next = at(dollar + 1)
        return next.isNameChar() || next == '{' || next == ':' && at(dollar + 2) == ':'
    }

    private fun variableEnd(start: Int): Int {
        if (at(start + 1) == '{') {
            val close = skipWhile(start + 2) { it != '}' && it != '\n' }
            return if (at(close) == '}') close + 1 else close
        }
        var i = start + 1
        while (true) {
            i = when {
                at(i).isNameChar() -> i + 1
                at(i) == ':' && at(i + 1) == ':' -> i + 2
                else -> break
            }
        }
        // An array element: $arr(index). Its index may itself hold substitutions, so it ends at the ')'.
        if (at(i) == '(') {
            val close = skipWhile(i + 1) { it != ')' && it != '\n' }
            if (at(close) == ')') return close + 1
        }
        return i
    }

    /** A bare word ends at whitespace, a separator, a bracket or a variable; backslashes escape one character. */
    private fun wordEnd(start: Int): Int {
        var i = start
        while (i < bufferEnd) {
            val c = buffer[i]
            when {
                c == '\\' -> if (continuationEnd(i) > 0) break else i += 2
                c.isWhitespace() || c in WORD_BREAKS -> break
                c == '$' && i > start && isVariableStart(i) -> break
                else -> i++
            }
        }
        return minOf(i, bufferEnd)
    }

    private fun isFollowedByEquals(end: Int): Boolean {
        val i = skipWhile(end) { it == ' ' || it == '\t' }
        return at(i) == '=' && at(i + 1) != '='
    }

    private companion object {
        const val COMMAND_POSITION = 0
        /** After `{` or a `[` inside braces: a command may start here, but only a known one is taken for it. */
        const val WEAK_COMMAND_POSITION = 1
        const val ARGUMENT_POSITION = 2
        const val POSITION_MASK = 3
        const val IN_ASSIGNMENT = 4
        const val AFTER_NAME = 8
        const val DEPTH_SHIFT = 4
        const val MAX_DEPTH = 0xFFFF

        const val WORD_BREAKS = ";[]{}()"
        val NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?|0[xX][0-9a-fA-F]+""")
        val OPTION = Regex("""-[A-Za-z_][A-Za-z0-9_]*""")

        fun Char.isNameChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'
    }
}
