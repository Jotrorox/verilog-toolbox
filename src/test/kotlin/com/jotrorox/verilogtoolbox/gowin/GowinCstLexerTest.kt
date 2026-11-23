package com.jotrorox.verilogtoolbox.gowin

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Test

class GowinCstLexerTest {
    /** Lexes [text] into "TYPE:text" pairs, leaving out whitespace, and checks the tokens cover it exactly. */
    private fun lex(text: String): List<String> {
        val lexer = GowinCstLexer()
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
    fun pinLocation() = assertEquals(
        listOf("KEYWORD:IO_LOC", "STRING:\"led[0]\"", "LOCATION:G5", ";:;"),
        lex("IO_LOC  \"led[0]\" G5;"),
    )

    @Test
    fun portAttributes() = assertEquals(
        listOf("KEYWORD:IO_PORT", "STRING:\"clk\"", "ATTRIBUTE:IO_TYPE", "=:=", "VALUE:LVCMOS33",
            "ATTRIBUTE:DRIVE", "=:=", "NUMBER:8", "ATTRIBUTE:BANK_VCCIO", "=:=", "NUMBER:3.3", ";:;"),
        lex("IO_PORT \"clk\" IO_TYPE=LVCMOS33 DRIVE=8 BANK_VCCIO=3.3;"),
    )

    @Test
    fun spacesAroundEquals() = assertEquals(
        listOf("KEYWORD:IO_PORT", "STRING:\"a\"", "ATTRIBUTE:PULL_MODE", "=:=", "VALUE:UP", ";:;"),
        lex("IO_PORT \"a\" PULL_MODE = UP;"),
    )

    @Test
    fun differentialPairsSitesAndAlternatives() = assertEquals(
        listOf("KEYWORD:IO_LOC", "STRING:\"lvds\"", "LOCATION:A1", ",:,", "LOCATION:B1", ";:;",
            "KEYWORD:INS_LOC", "STRING:\"q\"", "LOCATION:R2C3[0][A]", ";:;",
            "KEYWORD:CLOCK_LOC", "STRING:\"clk\"", "LOCATION:BUFG", "|:|", "LOCATION:BUFS", ";:;"),
        lex("IO_LOC \"lvds\" A1,B1;\nINS_LOC \"q\" R2C3[0][A];\nCLOCK_LOC \"clk\" BUFG|BUFS;"),
    )

    @Test
    fun comments() = assertEquals(
        listOf("COMMENT:// pins", "KEYWORD:IO_LOC", "STRING:\"a\"", "LOCATION:E2", ";:;", "COMMENT:// after",
            "COMMENT:/* block\n comment */", "KEYWORD:IO_LOC", "COMMENT:/* inline */", "STRING:\"b\"",
            "LOCATION:F1", "COMMENT://glued", "COMMENT:/* unclosed"),
        lex("// pins\nIO_LOC \"a\" E2; // after\n/* block\n comment */\nIO_LOC/* inline */\"b\" F1//glued\n/* unclosed"),
    )

    @Test
    fun unknownStatementsAndAMissingSemicolon() = assertEquals(
        listOf("STATEMENT:FOO_LOC", "STRING:\"x\"", "LOCATION:A1", ";:;",
            "KEYWORD:IO_LOC", "STRING:\"y\"", "LOCATION:A2", "KEYWORD:IO_PORT", "STRING:\"y\""),
        lex("FOO_LOC \"x\" A1;\nIO_LOC \"y\" A2\nIO_PORT \"y\""),
    )

    @Test
    fun unclosedNameEndsWithTheLine() = assertEquals(
        listOf("KEYWORD:IO_LOC", "STRING:\"a", "KEYWORD:IO_LOC", "STRING:\"b\"", "LOCATION:C1", ";:;"),
        lex("IO_LOC \"a\nIO_LOC \"b\" C1;"),
    )

    /** The editor restarts lexing at a token with the state lexing reached there, and must get the same tokens. */
    @Test
    fun restartsAtAnyToken() {
        val text = "IO_PORT \"clk\" IO_TYPE=LVCMOS33 DRIVE=8;\nIO_LOC \"led\" G5,H5;\n"
        val lexer = GowinCstLexer()
        lexer.start(text, 0, text.length, 0)
        val expected = mutableListOf<Triple<Int, Int, Any?>>()
        val states = mutableListOf<Int>()
        while (lexer.tokenType != null) {
            expected += Triple(lexer.tokenStart, lexer.tokenEnd, lexer.tokenType)
            states += lexer.state
            lexer.advance()
        }
        for ((index, token) in expected.withIndex()) {
            val restarted = GowinCstLexer()
            restarted.start(text, token.first, text.length, states[index])
            val actual = mutableListOf<Triple<Int, Int, Any?>>()
            while (restarted.tokenType != null) {
                actual += Triple(restarted.tokenStart, restarted.tokenEnd, restarted.tokenType)
                restarted.advance()
            }
            assertEquals(expected.drop(index), actual)
        }
    }
}
