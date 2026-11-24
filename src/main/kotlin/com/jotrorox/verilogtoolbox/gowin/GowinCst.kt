package com.jotrorox.verilogtoolbox.gowin

import com.intellij.psi.TokenType

/** What a `.cst` file constrains, read with [GowinCstLexer]. */
object GowinCst {
    /**
     * A statement that names a port: `IO_LOC "led[0]" G5;` or `IO_PORT "led[0]" IO_TYPE=LVCMOS33;`.
     * [name] is without its quotes; [nameRange] is where the quoted name stands in the text.
     */
    data class PortConstraint(val keyword: String, val name: String, val nameRange: IntRange) {
        /** The port without a bit index: `led` for `led[0]`. */
        val port: String get() = name.substringBefore('[').trim()

        /** The bit of a vector port, such as 0 for `led[0]`; null for a whole port. */
        val bit: Int? get() = BIT.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The statements that place or configure a port, in the order of the text. */
    fun portConstraints(text: CharSequence): List<PortConstraint> {
        val lexer = GowinCstLexer()
        lexer.start(text, 0, text.length, 0)
        val found = mutableListOf<PortConstraint>()
        var keyword: String? = null
        var expectName = false
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            when {
                type == GowinCstTokenTypes.KEYWORD || type == GowinCstTokenTypes.STATEMENT -> {
                    keyword = tokenText
                    expectName = tokenText in PORT_KEYWORDS
                }
                type == GowinCstTokenTypes.STRING && expectName -> {
                    val name = tokenText.removePrefix("\"").removeSuffix("\"")
                    if (name.isNotBlank()) found += PortConstraint(keyword!!, name, lexer.tokenStart until lexer.tokenEnd)
                    expectName = false
                }
                type == GowinCstTokenTypes.COMMENT || type == TokenType.WHITE_SPACE -> Unit
                else -> expectName = false
            }
            lexer.advance()
        }
        return found
    }

    /** The ports and bits that have a pin, as written: `clk`, `led[0]`. */
    fun locatedNames(text: CharSequence): Set<String> =
        portConstraints(text).filter { it.keyword == "IO_LOC" }.map { it.name.replace(" ", "") }.toSet()

    private val PORT_KEYWORDS = setOf("IO_LOC", "IO_PORT")
    private val BIT = Regex("""\[\s*(\d+)\s*]\s*$""")
}
