package com.jotrorox.verilogtoolbox

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Finds the modules of a Verilog source and tells which of them are testbenches. */
object VerilogTestbenches {
    /** A module declaration. A module without ports cannot be instantiated usefully, so it is a testbench. */
    data class Module(val name: String, val nameOffset: Int, val isTestbench: Boolean)

    private class Token(val type: IElementType, val start: Int, val text: String)

    fun modules(source: CharSequence): List<Module> {
        val tokens = codeTokens(source)
        val modules = mutableListOf<Module>()
        for (i in tokens.indices) {
            if (tokens[i].type != VerilogTokenTypes.MODULE) continue
            var next = i + 1
            // Skip a lifetime qualifier: `module automatic foo`.
            if (tokens.getOrNull(next)?.text in LIFETIMES) next++
            val name = tokens.getOrNull(next)?.takeIf { it.type == VerilogTokenTypes.IDENTIFIER } ?: continue
            modules += Module(name.text, name.start, !hasPorts(tokens, next + 1))
        }
        return modules
    }

    fun testbenches(source: CharSequence): List<Module> = modules(source).filter { it.isTestbench }

    /** [index] is the token after the module name: `;`, a port list or a `#(...)` parameter list. */
    private fun hasPorts(tokens: List<Token>, index: Int): Boolean {
        var i = index
        if (tokens.getOrNull(i)?.text == "#" && tokens.getOrNull(i + 1)?.type == VerilogTokenTypes.LPAREN) {
            i = closingParenthesis(tokens, i + 1) + 1
        }
        return tokens.getOrNull(i)?.type == VerilogTokenTypes.LPAREN &&
            tokens.getOrNull(i + 1)?.type != VerilogTokenTypes.RPAREN
    }

    private fun closingParenthesis(tokens: List<Token>, open: Int): Int {
        var depth = 0
        for (i in open until tokens.size) {
            when (tokens[i].type) {
                VerilogTokenTypes.LPAREN -> depth++
                VerilogTokenTypes.RPAREN -> if (--depth == 0) return i
            }
        }
        return tokens.size
    }

    private fun codeTokens(source: CharSequence): List<Token> {
        val lexer = VerilogLexer()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in VerilogTokenTypes.COMMENTS) {
                tokens += Token(type, lexer.tokenStart, source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString())
            }
            lexer.advance()
        }
        return tokens
    }

    private val LIFETIMES = setOf("automatic", "static")
}
