package com.jotrorox.verilogtoolbox.sby

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Test

class SbyLexerTest {
    /** Lexes [text] into "TYPE:text" pairs, leaving out whitespace, and checks the tokens cover it exactly. */
    private fun lex(text: String): List<String> {
        val lexer = SbyLexer()
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
    fun tasks() = assertEquals(
        listOf("SECTION:[tasks]", "TASK:bmc", "TASK:prove", "KEYWORD:default", "TASK:a", "TASK:b", ":::", "TAG:fast",
            "COMMENT:# both"),
        lex("[tasks]\nbmc\nprove default\na b: fast\n# both\n"),
    )

    @Test
    fun optionsWithTaskTags() = assertEquals(
        listOf("SECTION:[options]", "TAG:bmc", ":::", "OPTION:mode", "VALUE:bmc", "TAG:~cover", ":::", "OPTION:depth",
            "NUMBER:20", "OPTION:multiclock", "VALUE:on"),
        lex("[options]\nbmc: mode bmc\n~cover: depth 20\nmulticlock on\n"),
    )

    @Test
    fun taskBlock() = assertEquals(
        listOf("SECTION:[options]", "TAG:prove", ":::", "OPTION:mode", "VALUE:prove", "OPTION:depth", "NUMBER:5",
            "BLOCK_END:--", "OPTION:wait", "VALUE:on"),
        lex("[options]\nprove:\nmode prove\ndepth 5\n--\nwait on\n"),
    )

    @Test
    fun enginesAndScript() = assertEquals(
        listOf("SECTION:[engines]", "ENGINE:smtbmc", "FLAG:--nomem", "VALUE:yices", "ENGINE:abc", "VALUE:pdr",
            "SECTION:[script]", "COMMAND:read", "FLAG:-formal", "FLAG:-DFORMAL", "VALUE:counter.sv",
            "COMMAND:prep", "FLAG:-top", "VALUE:counter", "COMMENT:# the top"),
        lex("[engines]\nsmtbmc --nomem yices\nabc pdr\n\n[script]\nread -formal -DFORMAL counter.sv\nprep -top counter # the top\n"),
    )

    @Test
    fun filesAndWindowsPaths() = assertEquals(
        listOf("SECTION:[files]", "PATH:rtl/counter.sv", "PATH:top.sv", "PATH:C:\\rtl\\top.sv"),
        lex("[files]\nrtl/counter.sv\ntop.sv C:\\rtl\\top.sv\n"),
    )

    @Test
    fun fileSectionsAreTakenAsTheyAre() = assertEquals(
        listOf("SECTION:[file defines.sv]", "TEXT:\n`define WIDTH 8 # not a comment\ndefault: x;\n", "SECTION:[script]",
            "COMMAND:read"),
        lex("[file defines.sv]\n`define WIDTH 8 # not a comment\ndefault: x;\n[script]\nread\n"),
    )

    @Test
    fun pythonBlocks() = assertEquals(
        listOf("SECTION:[options]", "PYCODE_MARKER:--pycode-begin--\n", "TEXT:for t in tasks:\n  output(f\"{t}: mode {t}\")\n",
            "PYCODE_MARKER:--pycode-end--", "OPTION:depth", "NUMBER:4"),
        lex("[options]\n--pycode-begin--\nfor t in tasks:\n  output(f\"{t}: mode {t}\")\n--pycode-end--\ndepth 4\n"),
    )

    @Test
    fun unclosedFileSectionAndPythonBlockRunToTheEnd() {
        assertEquals(listOf("SECTION:[file a.v]", "TEXT:\nmodule a;"), lex("[file a.v]\nmodule a;"))
        assertEquals(listOf("PYCODE_MARKER:--pycode-begin--\n", "TEXT:x = 1\n"), lex("--pycode-begin--\nx = 1\n"))
    }

    /** The editor restarts lexing at a token with the state lexing reached there, and must get the same tokens. */
    @Test
    fun restartsAtAnyToken() {
        val text = """
            [tasks]
            bmc
            prove default

            [options]
            bmc: mode bmc
            prove:
            mode prove
            --
            --pycode-begin--
            output("depth 4")
            --pycode-end--

            [engines]
            smtbmc yices

            [script]
            read -formal counter.sv
            prep -top counter

            [file defines.sv]
            `define X 1

            [files]
            rtl/counter.sv
        """.trimIndent()
        val lexer = SbyLexer()
        lexer.start(text, 0, text.length, 0)
        val expected = mutableListOf<Triple<Int, Int, Any?>>()
        while (lexer.tokenType != null) {
            expected += Triple(lexer.tokenStart, lexer.state, lexer.tokenType)
            lexer.advance()
        }
        for ((index, token) in expected.withIndex()) {
            val (start, state) = token
            lexer.start(text, start, text.length, state)
            val actual = mutableListOf<Triple<Int, Int, Any?>>()
            while (lexer.tokenType != null) {
                actual += Triple(lexer.tokenStart, lexer.state, lexer.tokenType)
                lexer.advance()
            }
            assertEquals("restarted at token $index (offset $start)", expected.drop(index), actual)
        }
    }
}
