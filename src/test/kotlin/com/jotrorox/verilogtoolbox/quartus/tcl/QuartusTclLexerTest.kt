package com.jotrorox.verilogtoolbox.quartus.tcl

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuartusTclLexerTest {
    /** Lexes [text] into "TYPE:text" pairs, leaving out whitespace, and checks the tokens cover it exactly. */
    private fun lex(text: String): List<String> {
        val lexer = QuartusTclLexer()
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
    fun globalAssignment() = assertEquals(
        listOf("KNOWN_COMMAND:set_global_assignment", "OPTION:-name", "ASSIGNMENT_NAME:FAMILY", "STRING:\"MAX 10\""),
        lex("set_global_assignment -name FAMILY \"MAX 10\""),
    )

    @Test
    fun instanceAndLocationAssignments() = assertEquals(
        listOf("KNOWN_COMMAND:set_instance_assignment", "OPTION:-name", "ASSIGNMENT_NAME:IO_STANDARD",
            "STRING:\"3.3-V LVTTL\"", "OPTION:-to", "WORD:led", "[:[", "NUMBER:0", "]:]",
            "KNOWN_COMMAND:set_location_assignment", "WORD:PIN_A1", "OPTION:-to", "WORD:clk"),
        lex(
            "set_instance_assignment -name IO_STANDARD \"3.3-V LVTTL\" -to led[0]\n" +
                "set_location_assignment PIN_A1 -to clk",
        ),
    )

    /** Only assignment commands have an assignment after -name; create_clock's is a clock name. */
    @Test
    fun nameOfOtherCommandsIsAWord() = assertEquals(
        listOf("KNOWN_COMMAND:create_clock", "OPTION:-name", "WORD:clk", "OPTION:-period", "NUMBER:20.000",
            "[:[", "KNOWN_COMMAND:get_ports", "{:{", "WORD:clk", "}:}", "]:]",
            "KNOWN_COMMAND:set_parameter", "OPTION:-name", "WORD:WIDTH", "NUMBER:8"),
        lex("create_clock -name clk -period 20.000 [get_ports {clk}]\nset_parameter -name WIDTH 8"),
    )

    @Test
    fun commentsOnlyWhereACommandStarts() = assertEquals(
        listOf("COMMENT:# a comment", "KNOWN_COMMAND:puts", "WORD:a#b", "WORD:#c", ";:;", "COMMENT:# after ;",
            "{:{", "COMMENT:# in braces", "}:}"),
        lex("# a comment\nputs a#b #c; # after ;\n{# in braces\n}"),
    )

    @Test
    fun backslashNewlineContinuesCommandsAndComments() = assertEquals(
        listOf("KNOWN_COMMAND:set_false_path", "OPTION:-from", "WORD:a", "OPTION:-to", "WORD:b",
            "COMMENT:# one \\\n two", "COMMAND:three"),
        lex("set_false_path -from a \\\n    -to b\n# one \\\n two\nthree"),
    )

    @Test
    fun stringsWithEscapesMaySpanLines() = assertEquals(
        listOf("KNOWN_COMMAND:puts", "STRING:\"a \\\" b\n c\"", "WORD:x", "STRING:\"open"),
        lex("puts \"a \\\" b\n c\" x \"open"),
    )

    @Test
    fun variables() = assertEquals(
        listOf("KNOWN_COMMAND:set", "WORD:a", "VARIABLE:\$b", "VARIABLE:\${c d}", "VARIABLE:\$::quartus(qip_path)",
            "WORD:pin_", "VARIABLE:\$n", "WORD:\$", "VARIABLE:\$arr(\$i)"),
        lex("set a \$b \${c d} \$::quartus(qip_path) pin_\$n \$ \$arr(\$i)"),
    )

    @Test
    fun numbersAndOptions() = assertEquals(
        listOf("KNOWN_COMMAND:set_input_delay", "OPTION:-clock_fall", "NUMBER:-5.5", "NUMBER:1e-3", "NUMBER:0x1F",
            "WORD:10M50DAF484C7G", "WORD:25.1STD.0", "WORD:-", "OPTION:-max"),
        lex("set_input_delay -clock_fall -5.5 1e-3 0x1F 10M50DAF484C7G 25.1STD.0 - -max"),
    )

    @Test
    fun nestedBracesAndScriptBodies() = assertEquals(
        listOf("KNOWN_COMMAND:for", "{:{", "KNOWN_COMMAND:set", "WORD:n", "NUMBER:0", "}:}",
            "{:{", "VARIABLE:\$n", "WORD:<", "NUMBER:3", "}:}", "{:{", "KNOWN_COMMAND:incr", "WORD:n", "}:}", "{:{",
            "KNOWN_COMMAND:create_generated_clock", "OPTION:-name", "WORD:c", "[:[", "KNOWN_COMMAND:get_registers",
            "{:{", "WORD:*|q", "[:[", "WORD:*", "]:]", "{:{", "}:}", "}:}", "]:]", "}:}",
            "KNOWN_COMMAND:if", "{:{", "WORD:x", "}:}", "{:{", "}:}", "KNOWN_COMMAND:else", "{:{", "}:}"),
        lex("for {set n 0} {\$n < 3} {incr n} {\n  create_generated_clock -name c [get_registers {*|q[*]{}}]\n}\n" +
            "if {x} {} else {}"),
    )

    @Test
    fun unknownCommandsAndParentheses() = assertEquals(
        listOf("COMMAND:my_proc", "(:(", "NUMBER:1", "):)", "KNOWN_COMMAND:package", "WORD:require", "WORD:Tcl"),
        lex("my_proc (1)\npackage require Tcl"),
    )

    @Test
    fun projectFileLines() = assertEquals(
        listOf("ASSIGNMENT_NAME:QUARTUS_VERSION", "WORD:=", "STRING:\"25.1\"",
            "ASSIGNMENT_NAME:PROJECT_REVISION", "WORD:=", "STRING:\"blinky\""),
        lex("QUARTUS_VERSION = \"25.1\"\r\nPROJECT_REVISION = \"blinky\"\r\n"),
    )

    /** The editor restarts lexing at any token with the state it had there; that must give the same tokens. */
    @Test
    fun restartingAtAnyTokenGivesTheSameTokens() {
        val text = "set_global_assignment -name SEED 2 ;# x\nif {\$a} {\n  set_instance_assignment -name \\\n" +
            "  VIRTUAL_PIN ON -to {b[0]}\n}\nPROJECT_REVISION = \"r\"\n"
        val lexer = QuartusTclLexer()
        lexer.start(text, 0, text.length, 0)
        val all = mutableListOf<Triple<Int, Int, String>>()
        while (lexer.tokenType != null) {
            all += Triple(lexer.tokenStart, lexer.state, lexer.tokenType.toString())
            lexer.advance()
        }
        for ((index, token) in all.withIndex()) {
            val restarted = QuartusTclLexer()
            restarted.start(text, token.first, text.length, token.second)
            val rest = mutableListOf<Triple<Int, Int, String>>()
            while (restarted.tokenType != null) {
                rest += Triple(restarted.tokenStart, restarted.state, restarted.tokenType.toString())
                restarted.advance()
            }
            assertEquals("restart at ${token.first}", all.drop(index), rest)
        }
    }

    @Test
    fun readsAssignmentDefaults() {
        val defaults = QuartusTclAssignments.parseDefaults(
            listOf(
                "# In 4.1, the default value of assignment FITTER_EFFORT has changed to \"Auto Fit\"",
                "set_global_assignment -name SEED 1",
                "set_global_assignment -name SMART_RECOMPILE Off",
                "set_global_assignment -name REVISION_TYPE Base -family \"Arria V\"",
                "set_global_assignment -name REVISION_TYPE Base -family \"Cyclone V\"",
                "set_global_assignment -name AUTO_RAM_RECOGNITION On",
            ),
        )
        assertEquals(listOf("SEED", "SMART_RECOMPILE", "REVISION_TYPE", "AUTO_RAM_RECOGNITION"), defaults.names)
        assertEquals(setOf("SMART_RECOMPILE", "AUTO_RAM_RECOGNITION"), defaults.booleans)
    }

    @Test
    fun contextOfTheWordBeingTyped() {
        val qsf = "set_instance_assignment -name IO_STANDARD \"3.3"
        val value = QuartusTclCompletionContext.at(qsf, qsf.length)!!
        assertEquals(listOf("set_instance_assignment", "-name", "IO_STANDARD"), value.words)
        assertEquals("\"3.3", value.prefix)

        val sdc = "set_false_path -from a \\\n  -to [get_po"
        val nested = QuartusTclCompletionContext.at(sdc, sdc.length)!!
        assertTrue(nested.atCommand)
        assertEquals("get_po", nested.prefix)

        val list = "set_false_path -to [get_ports {cl"
        assertEquals(false, QuartusTclCompletionContext.at(list, list.length)!!.atCommand)

        val comment = "# set_glo"
        assertEquals(null, QuartusTclCompletionContext.at(comment, comment.length))
    }
}
