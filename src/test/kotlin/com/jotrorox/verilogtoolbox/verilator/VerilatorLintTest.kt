package com.jotrorox.verilogtoolbox.verilator

import com.intellij.lang.annotation.HighlightSeverity
import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class VerilatorLintTest {
    private val file: Path = Path.of("/tmp/lint/w.sv").toAbsolutePath()

    private val text = """
        module w(input logic [3:0] a, output logic [2:0] y, output logic z);
          logic unused;
          assign y = a;
          always_comb begin
            case (a[1:0])
              2'd0: z = 1;
              2'd1: z = 0;
            endcase
          end
          counter #(.WIDTH(4)) c(.clk(a[0]), .rst(a[1]), .en(a[2]), .count());
        endmodule
    """.trimIndent() + "\n"

    /** Each problem as "severity line:column-length text|message", lines and columns one-based. */
    private fun problems(output: String, linted: CharSequence = text): List<String> =
        VerilatorLint.parse(output, file, linted).map { problem ->
            val severity = problem.severity.myName
            val range = problem.range ?: return@map "$severity file|${problem.message}"
            val line = linted.subSequence(0, range.startOffset).count { it == '\n' } + 1
            val column = range.startOffset - (linted.lastIndexOf('\n', range.startOffset - 1) + 1) + 1
            "$severity $line:$column-${range.length} ${linted.subSequence(range.startOffset, range.endOffset)}|${problem.message}"
        }

    @Test
    fun warningsAreReadWithTheMarkedCode() {
        // Output of Verilator 5.055, abridged.
        val output = """
            %Warning-PINCONNECTEMPTY: $file:10:62: Instance pin connected by name with empty reference: 'count'
               10 |   counter #(.WIDTH(4)) c(.clk(a[0]), .rst(a[1]), .en(a[2]), .count());
                  |                                                              ^~~~~
                                      ... For warning description see https://verilator.org/warn/PINCONNECTEMPTY?v=5.055
                                      ... Use "/* verilator lint_off PINCONNECTEMPTY */" and lint_on around source to disable this message.
            %Warning-WIDTHTRUNC: $file:3:12: Operator ASSIGNW expects 3 bits on the Assign RHS, but Assign RHS's VARREF 'a' generates 4 bits.
                                                                                                                                           : ... note: In instance 'w'
                3 |   assign y = a;
                  |            ^
            %Warning-CASEINCOMPLETE: $file:5:5: Case values incompletely covered (example pattern 0x2)
                5 |     case (a[1:0])
                  |     ^~~~
            %Warning-LATCH: $file:4:3: Latch inferred for signal 'z' (not all control paths of combinational always assign a value)
                                     : ... Suggest use of always_latch for intentional latches
                4 |   always_comb begin
                  |   ^~~~~~~~~~~
            - V e r i l a t i o n   R e p o r t: Verilator 5.055 devel rev v5.054-313-g979d7fdf2 (mod)
        """.trimIndent()
        assertEquals(
            listOf(
                "WARNING 10:62-5 count|Instance pin connected by name with empty reference: 'count' (Verilator PINCONNECTEMPTY)",
                "WARNING 3:12-1 =|Operator ASSIGNW expects 3 bits on the Assign RHS, but Assign RHS's VARREF 'a' generates 4 bits. (Verilator WIDTHTRUNC)",
                "WARNING 5:5-4 case|Case values incompletely covered (example pattern 0x2) (Verilator CASEINCOMPLETE)",
                "WARNING 4:3-11 always_comb|Latch inferred for signal 'z' (not all control paths of combinational always assign a value) (Verilator LATCH)",
            ),
            problems(output),
        )
    }

    @Test
    fun errorsAreErrorsAndTheirConsequencesAreLeftOut() {
        val output = """
            %Error: $file:6:5: syntax error, unexpected initial, expecting ',' or ';'
                6 |       2'd0: z = 1;
                  |     ^~~~~~~
                    ... See the manual at https://verilator.org/verilator_doc.html?v=5.055 for more assistance.
            %Error: Cannot continue
                    ... This fatal error may be caused by the earlier error(s); resolve those first.
            %Error: Exiting due to 1 error(s)
        """.trimIndent()
        assertEquals(
            listOf("ERROR 6:5-7   2'd0:|syntax error, unexpected initial, expecting ',' or ';' (Verilator)"),
            problems(output),
        )
    }

    @Test
    fun syntaxErrorsAreKnownAsSuch() {
        val output = "%Error: $file:6:5: syntax error, unexpected initial\n%Error-PINNOTFOUND: $file:1:8: Pin not found: 'x'"
        assertEquals(listOf(true, false), VerilatorLint.parse(output, file, text).map { VerilatorLint.isSyntaxError(it) })
    }

    @Test
    fun problemsWithoutAPlaceConcernTheWholeFile() = assertEquals(
        listOf("ERROR file|Invalid option: -Wbogus (Verilator)"),
        problems("%Error: Invalid option: -Wbogus\n        ... See the manual at https://verilator.org for more assistance."),
    )

    @Test
    fun problemsInOtherFilesAreLeftOut() {
        // The module without a timescale is another file; its notes name places in this one.
        val output = """
            %Warning-TIMESCALEMOD: /tmp/lint/rtl/counter.sv:4:8: Timescale missing on this module as other modules have it
                4 | module counter #(
                  |        ^~~~~~~
                                   $file:13:1: ... note: In file included from 'w.sv'
                                   $file:1:8: ... Location of module with timescale
                1 | module w;
                  |        ^~~~~~~~~~
        """.trimIndent()
        assertEquals(emptyList<String>(), problems(output))
    }

    @Test
    fun aMarkerOfAnotherPlaceIsNotTaken() {
        // The first source line shown belongs to the note's place, not to the message's.
        val output = """
            %Warning-MULTIDRIVEN: $file:3:10: Signal has multiple driving blocks with different clocking: 'y'
                                  $file:10:3: ... Location of other driver
               10 |   counter #(.WIDTH(4)) c(.clk(a[0]), .rst(a[1]), .en(a[2]), .count());
                  |   ^~~~~~~
        """.trimIndent()
        assertEquals(
            listOf("WARNING 3:10-1 y|Signal has multiple driving blocks with different clocking: 'y' (Verilator MULTIDRIVEN)"),
            problems(output),
        )
    }

    @Test
    fun placesBeyondTheTextAreKeptInIt() {
        val output = "%Warning-WIDTH: $file:2:200: Too far right\n%Warning-WIDTH: $file:99:1: Too far down"
        assertEquals(listOf("WARNING 2:16-0 |Too far right (Verilator WIDTH)"), problems(output))
    }

    @Test
    fun windowsLineEndsAreNotPartOfTheRange() {
        val crlf = "module m;\r\nwire  x;\r\nendmodule\r\n"
        assertEquals(
            listOf("WARNING 2:7-1 x|Signal is not driven (Verilator UNDRIVEN)"),
            problems("%Warning-UNDRIVEN: $file:2:7: Signal is not driven", crlf),
        )
    }

    @Test
    fun copyTakesThePlaceOfTheFileInTheDesign() {
        val root = Path.of("/project").toAbsolutePath()
        val original = root.resolve("rtl/alu.sv")
        val copy = Path.of("/tmp/lint/alu.sv").toAbsolutePath()
        val design = FileList(
            sources = listOf(root.resolve("rtl/pkg.sv"), original),
            includeDirectories = listOf(root.resolve("inc")),
            defines = listOf("WIDTH=8"),
            libraryDirectories = listOf(root.resolve("lib")),
            libraryFiles = listOf(root.resolve("cells.v")),
            libraryExtensions = listOf(".vp"),
            topModules = listOf("chip"),
            ignored = emptyList(),
            errors = emptyList(),
        )
        assertEquals(
            listOf(
                "--lint-only", "-Wno-fatal", "-Wno-MULTITOP", "-Wall", "-Wno-DECLFILENAME",
                "-I${root.resolve("rtl")}", "-I${root.resolve("inc")}", "-DWIDTH=8", "-y", "${root.resolve("lib")}",
                "+libext+.v+.sv+.vp", "-v", "${root.resolve("cells.v")}", "${root.resolve("rtl/pkg.sv")}", "$copy",
            ),
            VerilatorLint.arguments(design, original, copy, "-Wall -Wno-DECLFILENAME"),
        )
    }

    @Test
    fun aFileOnItsOwnIsLintedWithTheProjectDirectories() {
        val root = Path.of("/project").toAbsolutePath()
        val file = root.resolve("tb/alu_tb.sv")
        val copy = Path.of("/tmp/lint/alu_tb.sv").toAbsolutePath()
        val design = VerilogDesign.standalone(file, listOf(root.resolve("rtl"), root.resolve("tb")))
        assertEquals(
            listOf(
                "--lint-only", "-Wno-fatal", "-Wno-MULTITOP", "-I${root.resolve("tb")}", "-I${root.resolve("rtl")}",
                "-y", "${root.resolve("tb")}", "-y", "${root.resolve("rtl")}", "+libext+.v+.sv", "$copy",
            ),
            VerilatorLint.arguments(design, file, copy, ""),
        )
    }

    @Test
    fun onlySourcesAreLinted() {
        assertTrue(VerilatorLint.isLinted(Path.of("alu.v")))
        assertTrue(VerilatorLint.isLinted(Path.of("counter.SV")))
        assertFalse(VerilatorLint.isLinted(Path.of("defines.vh")))
        assertFalse(VerilatorLint.isLinted(Path.of("types.svh")))
    }

    @Test
    fun severitiesFollowVerilator() {
        val output = "%Error-PINNOTFOUND: $file:1:8: Pin not found\n%Warning-UNUSED: $file:2:9: Unused"
        assertEquals(
            listOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING),
            VerilatorLint.parse(output, file, text).map { it.severity },
        )
    }
}
