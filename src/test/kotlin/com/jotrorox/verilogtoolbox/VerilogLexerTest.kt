package com.jotrorox.verilogtoolbox

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Test

class VerilogLexerTest {
    /** Lexes [text] into "TYPE:text" pairs, leaving out whitespace, and checks the tokens cover it exactly. */
    private fun lex(text: String): List<String> {
        val lexer = VerilogLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = mutableListOf<String>()
        var offset = 0
        while (true) {
            val type = lexer.tokenType ?: break
            assertEquals("tokens must be contiguous", offset, lexer.tokenStart)
            assert(lexer.tokenEnd > lexer.tokenStart) { "empty token at $offset" }
            offset = lexer.tokenEnd
            if (type != TokenType.WHITE_SPACE) tokens += "$type:${text.substring(lexer.tokenStart, lexer.tokenEnd)}"
            lexer.advance()
        }
        assertEquals(text.length, offset)
        return tokens
    }

    @Test
    fun keywordsTypesAndIdentifiers() = assertEquals(
        listOf("module:module", "IDENTIFIER:top", ";:;", "TYPE:logic", "IDENTIFIER:a_1\$x", ";:;",
            "KEYWORD:always_ff", "begin:begin", "end:end", "endmodule:endmodule", "IDENTIFIER:\\esc+id"),
        lex("module top; logic a_1\$x; always_ff begin end endmodule \\esc+id"),
    )

    @Test
    fun numbers() = assertEquals(
        listOf("NUMBER:42", "NUMBER:8'hFF", "NUMBER:4 'b10_xz", "NUMBER:'sd3", "NUMBER:'0", "NUMBER:'z",
            "NUMBER:1.5e-3", "NUMBER:10ns", "NUMBER:2.5ps", "NUMBER:1_000"),
        lex("42 8'hFF 4 'b10_xz 'sd3 '0 'z 1.5e-3 10ns 2.5ps 1_000"),
    )

    @Test
    fun ticksThatAreNotNumbers() = assertEquals(
        listOf("TYPE:int", "OPERATOR:'", "(:(", "IDENTIFIER:x", "):)", "OPERATOR:'", "{:{", "NUMBER:1", "}:}"),
        lex("int'(x) '{1}"),
    )

    @Test
    fun commentsAndStrings() = assertEquals(
        listOf("LINE_COMMENT:// a /* b", "BLOCK_COMMENT:/* c\n// d */", "STRING:\"e \\\" f\"", "OPERATOR:/",
            "STRING:\"open", "BLOCK_COMMENT:/* unterminated"),
        lex("// a /* b\n/* c\n// d */ \"e \\\" f\" / \"open\n/* unterminated"),
    )

    @Test
    fun directivesMacrosAndSystemTasks() = assertEquals(
        listOf("DIRECTIVE:`define", "IDENTIFIER:W", "NUMBER:8", "DIRECTIVE:`ifdef", "IDENTIFIER:X", "MACRO:`W",
            "DIRECTIVE:`endif", "SYSTEM_TASK:\$display", "(:(", "):)", "[:[", "OPERATOR:\$", "]:]"),
        lex("`define W 8\n`ifdef X `W `endif \$display() [\$]"),
    )

    @Test
    fun operators() = assertEquals(
        listOf("IDENTIFIER:a", "OPERATOR:<=", "IDENTIFIER:b", "OPERATOR:===", "IDENTIFIER:c", "OPERATOR:?",
            "IDENTIFIER:d", "OPERATOR::", "OPERATOR:~", "IDENTIFIER:e", ";:;", "OPERATOR:@", "(:(",
            "OPERATOR:*", "):)", "OPERATOR:+", "LINE_COMMENT://x", "BAD_CHARACTER:§"),
        lex("a <= b === c ? d : ~e; @(*) +//x\n§"),
    )
}
