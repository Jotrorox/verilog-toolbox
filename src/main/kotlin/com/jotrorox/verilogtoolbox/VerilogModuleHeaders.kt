package com.jotrorox.verilogtoolbox

import com.intellij.psi.TokenType

/**
 * Reads the parameters and ports of module declarations from the token stream. There is no parser, so this
 * understands the header only (plus, for Verilog-1995 port lists, the declarations in the body) and keeps
 * every type, dimension and default value as the text it was written as.
 */
object VerilogModuleHeaders {
    /** [INTERFACE] is an interface port such as `my_if.master bus`, which has no direction. */
    enum class Direction { INPUT, OUTPUT, INOUT, REF, INTERFACE }

    /**
     * [type] is what stands between the keyword and the name, such as `int` or `signed [3:0]`; for a type
     * parameter it is empty and [default] is a type. A local parameter cannot be overridden by an instance.
     */
    data class Parameter(
        val name: String,
        val type: String = "",
        val unpacked: String = "",
        val default: String? = null,
        val isType: Boolean = false,
        val isLocal: Boolean = false,
    )

    /**
     * [dataType] is null for a plain bit vector (`wire`, `reg`, `logic` or no type at all), which [signed]
     * and [packed] describe. Otherwise it is the type as written, dimensions included: `int`,
     * `pkg::state_t [3:0]`, or the interface and modport of an interface port.
     */
    data class Port(
        val name: String,
        val direction: Direction,
        val dataType: String? = null,
        val signed: Boolean = false,
        val packed: String = "",
        val unpacked: String = "",
        val default: String? = null,
    )

    /**
     * [range] spans `module` to `endmodule`. [imports] are the package imports of the header. [includes] are
     * the `` `include `` lines before the module and [defines] the `` `define `` lines by macro name, which
     * the macros of the header may come from. [notes] say what could not be read faithfully.
     */
    data class Module(
        val name: String,
        val nameOffset: Int,
        val range: IntRange,
        val parameters: List<Parameter>,
        val ports: List<Port>,
        val imports: List<String> = emptyList(),
        val includes: List<String> = emptyList(),
        val defines: Map<String, String> = emptyMap(),
        val notes: List<String> = emptyList(),
    )

    private class Token(val text: String, val start: Int, val end: Int, val isIdentifier: Boolean)

    /** A declared name with the tokens before it, split off its unpacked dimensions and default value. */
    private class Declarator(val prefix: List<Token>, val name: String, val unpacked: String, val default: String?)

    fun modules(source: CharSequence): List<Module> {
        val directives = mutableListOf<Pair<Int, String>>()
        val conditionals = mutableListOf<Int>()
        val tokens = codeTokens(source, directives, conditionals)
        return tokens.indices.filter { tokens[it].text in MODULE_KEYWORDS }
            .mapNotNull { module(source, tokens, it, directives, conditionals) }
    }

    private fun module(
        source: CharSequence, tokens: List<Token>, keyword: Int, directives: List<Pair<Int, String>>, conditionals: List<Int>,
    ): Module? {
        fun text(index: Int): String? = tokens.getOrNull(index)?.text
        fun indexOf(word: String, from: Int): Int = (from until tokens.size).firstOrNull { tokens[it].text == word } ?: tokens.size
        fun entries(from: Int, to: Int): List<List<Token>> = split(tokens.subList(minOf(from, tokens.size), minOf(to, tokens.size)))

        var next = keyword + 1
        if (text(next) in LIFETIMES) next++
        val name = tokens.getOrNull(next)?.takeIf { it.isIdentifier } ?: return null
        next++
        val notes = mutableListOf<String>()

        // `module m import pkg::*; #(...) (...);`
        val imports = mutableListOf<String>()
        while (text(next) == "import") {
            val end = indexOf(";", next)
            imports += join(tokens.subList(next, minOf(end + 1, tokens.size)))
            next = end + 1
        }
        val hasParameterList = text(next) == "#" && text(next + 1) == "("
        var parameters = emptyList<Parameter>()
        if (hasParameterList) {
            val close = closing(tokens, next + 1)
            parameters = parameters(entries(next + 2, close), false, notes)
            next = close + 1
        }
        var ports = emptyList<Port>()
        // A Verilog-1995 port list has names only; directions and types follow in the body.
        var portNames: List<String>? = null
        if (text(next) == "(") {
            val close = closing(tokens, next)
            val entries = entries(next + 1, close)
            if (entries.all { it.size == 1 && it[0].isIdentifier }) {
                portNames = entries.map { it[0].text }
            } else {
                ports = ports(entries, true, notes)
            }
            next = close + 1
        }
        val headerEnd = tokens.getOrNull(next)?.start ?: source.length
        if (conditionals.any { it in name.start..headerEnd }) {
            notes += "the header of ${name.text} has `ifdef branches; every branch was read, so check the parameters and ports"
        }

        val declared = mutableListOf<Port>()
        val bodyParameters = mutableListOf<Parameter>()
        while (next < tokens.size && text(next) != "endmodule") {
            when (val word = text(next)) {
                // Functions and tasks declare inputs and outputs of their own.
                "function", "task" -> next = indexOf("end$word", next)
                // `import "DPI-C" function ...;` has no body to skip.
                "import", "export", "extern" -> next = indexOf(";", next)
                "input", "output", "inout" -> {
                    val end = indexOf(";", next)
                    if (portNames != null) declared += ports(entries(next, end), false, notes)
                    next = end
                }
                "parameter", "localparam" -> {
                    val end = indexOf(";", next)
                    // Body parameters can only be overridden when the header has no parameter list.
                    bodyParameters += parameters(entries(next, end), hasParameterList, mutableListOf())
                    next = end
                }
            }
            next++
        }
        if (portNames != null) {
            ports = portNames.map { port ->
                declared.firstOrNull { it.name == port } ?: Port(port, Direction.INOUT).also {
                    notes += "no input, output or inout declaration was found for port '$port'"
                }
            }
        }
        ports = ports.distinctBy { it.name }
        val (defines, includes) = directives.filter { it.first < tokens[keyword].start }.map { it.second }
            .partition { it.startsWith("`define") }
        return Module(
            name.text, name.start, tokens[keyword].start until (tokens.getOrNull(next)?.end ?: source.length),
            referenced(parameters + bodyParameters, ports).distinctBy { it.name }, ports, imports, includes,
            defines.associateBy { DEFINED.find(it)?.groupValues?.get(1).orEmpty() }, notes,
        )
    }

    private fun ports(entries: List<List<Token>>, ansi: Boolean, notes: MutableList<String>): List<Port> {
        val ports = mutableListOf<Port>()
        for (entry in entries.flatMap { byDirection(it) }) {
            val declarator = declarator(entry)
            if (declarator == null) {
                notes += "the port '${join(entry)}' could not be read"
                continue
            }
            val previous = ports.lastOrNull()
            val direction = DIRECTIONS[declarator.prefix.firstOrNull()?.text]
            val prefix = if (direction != null) declarator.prefix.drop(1) else declarator.prefix
            val type = prefix.dropWhile { it.text in NET_TYPES }
            val port = when {
                // `input a, b`: a bare name shares everything but its own dimensions with the port before it.
                declarator.prefix.isEmpty() && previous != null -> previous
                // Without a direction a named type is taken for an interface, which is far more common
                // in a port list than a user-defined type that inherits its direction.
                direction == null && ansi && type.size == prefix.size && isInterface(type) ->
                    Port("", Direction.INTERFACE, join(type))
                else -> {
                    val inherited = direction ?: previous?.direction?.takeIf { it != Direction.INTERFACE } ?: Direction.INOUT
                    if (isVector(type)) {
                        Port("", inherited, null, type.any { it.text == "signed" }, join(type.filter { it.text !in SIGNING }))
                    } else {
                        Port("", inherited, join(type))
                    }
                }
            }
            ports += port.copy(name = declarator.name, unpacked = declarator.unpacked, default = declarator.default)
        }
        return ports
    }

    /**
     * [entry] split before every direction that does not begin it. Dropping an `` `ifdef `` around two
     * alternatives of a port leaves them without a comma in between.
     */
    private fun byDirection(entry: List<Token>): List<List<Token>> {
        val starts = topLevel(entry, DIRECTIONS.keys).filter { it > 0 && entry[it - 1].text != ")" }
        return (listOf(0) + starts + entry.size).zipWithNext { from, to -> entry.subList(from, to) }
    }

    private fun isInterface(type: List<Token>): Boolean =
        type.firstOrNull()?.text == "interface" ||
            type.size == 1 && type[0].isIdentifier ||
            type.size == 3 && type[0].isIdentifier && type[1].text == "." && type[2].isIdentifier

    /** Whether [type] is at most a signing and packed dimensions, so the port is a bit vector. */
    private fun isVector(type: List<Token>): Boolean {
        var depth = 0
        for (token in type) {
            when {
                token.text == "[" -> depth++
                token.text == "]" -> depth--
                depth == 0 && token.text !in SIGNING -> return false
            }
        }
        return true
    }

    /** [local] is whether a `parameter` here is one in name only, because it cannot be overridden. */
    private fun parameters(entries: List<List<Token>>, local: Boolean, notes: MutableList<String>): List<Parameter> {
        val parameters = mutableListOf<Parameter>()
        var isLocal = local
        for (entry in entries) {
            val declarator = declarator(entry)
            if (declarator == null) {
                notes += "the parameter '${join(entry)}' could not be read"
                continue
            }
            var prefix = declarator.prefix
            when (prefix.firstOrNull()?.text) {
                "parameter" -> isLocal = local
                "localparam" -> isLocal = true
            }
            if (prefix.firstOrNull()?.text in PARAMETER_KEYWORDS) prefix = prefix.drop(1)
            val previous = parameters.lastOrNull()
            // `parameter int A = 1, B = 2`: a bare name has the type of the parameter before it.
            val inherits = declarator.prefix.isEmpty() && previous != null
            val isType = if (inherits) previous!!.isType else prefix.firstOrNull()?.text == "type"
            parameters += Parameter(
                declarator.name,
                if (inherits) previous!!.type else join(if (isType) prefix.drop(1) else prefix),
                declarator.unpacked, declarator.default, isType, isLocal,
            )
        }
        return parameters
    }

    /**
     * Every parameter an instance can override, and the local ones the ports depend on: a testbench needs
     * `localparam AW = $clog2(DEPTH)` to declare `[AW-1:0] addr`, but not the state encodings of the body.
     */
    private fun referenced(parameters: List<Parameter>, ports: List<Port>): List<Parameter> {
        val used = HashSet<String>()
        fun use(vararg texts: String?) = texts.forEach { text -> IDENTIFIER.findAll(text.orEmpty()).forEach { used += it.value } }
        ports.forEach { use(it.dataType, it.packed, it.unpacked, it.default) }
        val kept = parameters.filterTo(HashSet()) { !it.isLocal }
        kept.forEach { use(it.type, it.unpacked, it.default) }
        while (true) {
            val more = parameters.filter { it !in kept && it.name in used }
            if (more.isEmpty()) break
            kept += more
            more.forEach { use(it.type, it.unpacked, it.default) }
        }
        return parameters.filter { it in kept }
    }

    private fun declarator(entry: List<Token>): Declarator? {
        var tokens = entry
        // Attributes: `(* keep *) input a`.
        while (tokens.size > 1 && tokens[0].text == "(" && tokens[1].text == "*") {
            tokens = tokens.drop(closing(tokens, 0) + 1)
        }
        val assignment = topLevel(tokens, setOf("=")).firstOrNull()
        val default = assignment?.let { join(tokens.drop(it + 1)) }?.takeIf { it.isNotEmpty() }
        if (assignment != null) tokens = tokens.take(assignment)
        var end = tokens.size
        while (end > 0 && tokens[end - 1].text == "]") end = opening(tokens, end - 1)
        val name = tokens.getOrNull(end - 1)?.takeIf { it.isIdentifier } ?: return null
        return Declarator(tokens.take(end - 1), name.text, join(tokens.drop(end)), default)
    }

    /** The parts of [tokens] between its commas, not counting commas inside brackets. */
    private fun split(tokens: List<Token>): List<List<Token>> {
        val bounds = listOf(-1) + topLevel(tokens, setOf(",")) + tokens.size
        return bounds.zipWithNext { from, to -> tokens.subList(from + 1, to) }.filter { it.isNotEmpty() }
    }

    /** The indices in [tokens] at which one of [words] stands outside every bracket. */
    private fun topLevel(tokens: List<Token>, words: Set<String>): List<Int> {
        var depth = 0
        return tokens.indices.filter {
            when (tokens[it].text) {
                in OPENING -> depth++
                in CLOSING -> depth--
            }
            depth == 0 && tokens[it].text in words
        }
    }

    /** The index of the bracket that closes the one at [open], or the size of [tokens] if it is left open. */
    private fun closing(tokens: List<Token>, open: Int): Int {
        var depth = 0
        for (i in open until tokens.size) {
            when (tokens[i].text) {
                in OPENING -> depth++
                in CLOSING -> if (--depth == 0) return i
            }
        }
        return tokens.size
    }

    private fun opening(tokens: List<Token>, close: Int): Int {
        var depth = 0
        for (i in close downTo 0) {
            when (tokens[i].text) {
                in CLOSING -> depth++
                in OPENING -> if (--depth == 0) return i
            }
        }
        return 0
    }

    /** The source text of [tokens], with one space wherever the source had whitespace or a comment. */
    private fun join(tokens: List<Token>): String = buildString {
        for ((index, token) in tokens.withIndex()) {
            if (index > 0 && tokens[index - 1].end != token.start) append(' ')
            append(token.text)
        }
    }

    /**
     * The tokens that are code. Conditional directives are dropped, so that every branch is read, and so are
     * directives that take the rest of their line, such as `` `define ``, whose body is not code yet. The
     * `` `include `` and `` `define `` lines go to [directives] with their offsets.
     */
    private fun codeTokens(
        source: CharSequence, directives: MutableList<Pair<Int, String>>, conditionals: MutableList<Int>,
    ): List<Token> {
        val lexer = VerilogLexer()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<Token>()
        var skipUntil = 0
        var skipNext = false
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            lexer.advance()
            if (type == TokenType.WHITE_SPACE || type in VerilogTokenTypes.COMMENTS || start < skipUntil) continue
            if (skipNext) {
                skipNext = false
                continue
            }
            // Keywords are told apart by their text: only identifiers are sure to keep their token type.
            val text = source.subSequence(start, end).toString()
            when (text) {
                "`ifdef", "`ifndef", "`elsif" -> {
                    conditionals += start
                    skipNext = true
                }
                "`else", "`endif" -> conditionals += start
                in LINE_DIRECTIVES -> {
                    skipUntil = lineEnd(source, start)
                    if (text == "`include" || text == "`define") {
                        directives += start to source.subSequence(start, skipUntil).toString().trim()
                    }
                }
                else -> tokens += Token(text, start, end, type == VerilogTokenTypes.IDENTIFIER)
            }
        }
        return tokens
    }

    /** The end of the line at [offset], following backslash continuations. */
    private fun lineEnd(source: CharSequence, offset: Int): Int {
        var i = offset
        while (i < source.length) {
            if (source[i] == '\n' && source.subSequence(offset, i).trimEnd().lastOrNull() != '\\') break
            i++
        }
        return i
    }

    private val MODULE_KEYWORDS = setOf("module", "macromodule")
    private val LIFETIMES = setOf("automatic", "static")
    private val PARAMETER_KEYWORDS = setOf("parameter", "localparam")
    private val DIRECTIONS = mapOf(
        "input" to Direction.INPUT, "output" to Direction.OUTPUT, "inout" to Direction.INOUT, "ref" to Direction.REF,
    )

    /** Net types and the variable types that are plain bit vectors; a testbench drives them all from `logic`. */
    private val NET_TYPES = setOf(
        "wire", "tri", "tri0", "tri1", "triand", "trior", "trireg", "wand", "wor", "uwire", "supply0", "supply1",
        "var", "reg", "logic",
    )
    private val SIGNING = setOf("signed", "unsigned")
    private val OPENING = setOf("(", "[", "{")
    private val CLOSING = setOf(")", "]", "}")
    private val LINE_DIRECTIVES = setOf(
        "`define", "`undef", "`include", "`timescale", "`default_nettype", "`pragma", "`line", "`resetall",
        "`celldefine", "`endcelldefine", "`begin_keywords", "`end_keywords", "`undefineall",
        "`unconnected_drive", "`nounconnected_drive",
    )
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    /** The name in a `` `define `` line. */
    private val DEFINED = Regex("""`define\s+(\w+)""")
}
