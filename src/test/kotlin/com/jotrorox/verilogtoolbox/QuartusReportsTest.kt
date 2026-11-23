package com.jotrorox.verilogtoolbox

import com.intellij.execution.process.ProcessOutputTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class QuartusReportsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    private val fitSummary = """
        Fitter Status : Successful - Thu Nov 19 15:36:20 2026
        Quartus Prime Version : 25.1std.0 Build 1129 10/21/2025 SC Lite Edition
        Revision Name : blinky
        Top-level Entity Name : blinky
        Family : MAX 10
        Device : 10M50DAF484C7G
        Timing Models : Final
        Total logic elements : 28 / 49,760 ( < 1 % )
            Total combinational functions : 28 / 49,760 ( < 1 % )
            Dedicated logic registers : 26 / 49,760 ( < 1 % )
        Total registers : 26
        Total pins : 6 / 360 ( 2 % )
        Total memory bits : 0 / 1,677,312 ( 0 % )
    """.trimIndent() + "\n"

    private val staSummary = """
        ------------------------------------------------------------
        Timing Analyzer Summary
        ------------------------------------------------------------

        Type  : Slow 1200mV 85C Model Setup 'clk_50'
        Slack : 16.789
        TNS   : 0.000

        Type  : Slow 1200mV 85C Model Hold 'clk_50'
        Slack : 0.418
        TNS   : 0.000

        Type  : Slow 1200mV 85C Model Minimum Pulse Width 'clk_50'
        Slack : 9.638
        TNS   : 0.000

        Type  : Fast 1200mV 0C Model Setup 'u_pll|pll1|clk[0]'
        Slack : -1.250
        TNS   : -37.500

        Type  : Fast 1200mV 0C Model Hold 'clk_50'
        Slack : 0.167
        TNS   : 0.000
    """.trimIndent() + "\n"

    private val staReport = """
        +---------------------------------------------------------------------------+
        ; Slow 1200mV 85C Model Fmax Summary                                        ;
        +------------+-----------------+------------+-------------------------------+
        ; Fmax       ; Restricted Fmax ; Clock Name ; Note                          ;
        +------------+-----------------+------------+-------------------------------+
        ; 311.43 MHz ; 250.0 MHz       ; clk_50     ; limit due to minimum period   ;
        ; 98.5 MHz   ; 98.5 MHz        ; spi_sclk   ;                               ;
        +------------+-----------------+------------+-------------------------------+
        This panel reports FMAX for every clock in the design.

        +---------------------------------------------------+
        ; Slow 1200mV 85C Model Setup Summary               ;
        +--------+--------+---------------+
        ; Clock  ; Slack  ; End Point TNS ;
        +--------+--------+---------------+
        ; clk_50 ; 16.789 ; 0.000         ;
        +--------+--------+---------------+

        +---------------------------------------------------------------------------+
        ; Slow 1200mV 0C Model Fmax Summary                                         ;
        +------------+-----------------+------------+-------------------------------+
        ; Fmax       ; Restricted Fmax ; Clock Name ; Note                          ;
        +------------+-----------------+------------+-------------------------------+
        ; 347.46 MHz ; 240.0 MHz       ; clk_50     ; limit due to minimum period   ;
        +------------+-----------------+------------+-------------------------------+
    """.trimIndent() + "\n"

    private val flowReport = """
        +--------------------------------------------------------------------------------------+
        ; Flow Summary                                                                         ;
        +------------------------------------+-------------------------------------------------+
        ; Flow Status                        ; Successful - Thu Nov 19 15:36:25 2026           ;
        ; Revision Name                      ; blinky                                          ;
        +------------------------------------+-------------------------------------------------+

        +--------------------------------------------------------------------------------------------------------------------------+
        ; Flow Elapsed Time                                                                                                        ;
        +----------------------+--------------+-------------------------+---------------------+------------------------------------+
        ; Module Name          ; Elapsed Time ; Average Processors Used ; Peak Virtual Memory ; Total CPU Time (on all processors) ;
        +----------------------+--------------+-------------------------+---------------------+------------------------------------+
        ; Analysis & Synthesis ; 00:00:11     ; 1.0                     ; 4849 MB             ; 00:00:22                           ;
        ; Fitter               ; 00:00:15     ; 1.0                     ; 6297 MB             ; 00:00:17                           ;
        ; Total                ; 00:00:26     ; --                      ; --                  ; 00:00:39                           ;
        +----------------------+--------------+-------------------------+---------------------+------------------------------------+

        +------------------------------------------------------------------------------------+
        ; Flow OS Summary                                                                    ;
    """.trimIndent() + "\n"

    private val mapReport = """
        Analysis & Synthesis report for blinky
        +-------------------------------+
        ; Analysis & Synthesis Messages ;
        +-------------------------------+
        Info: *******************************************************************
        Info: Running Quartus Prime Analysis & Synthesis
            Info: Version 25.1std.0 Build 1129 10/21/2025 SC Lite Edition
            Info: Processing started: Thu Nov 19 15:35:53 2026
        Info: Command: quartus_map blinky -c blinky
        Warning (18236): Number of processors has not been specified which may cause overloading on shared machines.
        Info (12021): Found 1 design units, including 1 entities, in source file rtl/counter.sv
            Info (12023): Found entity 1: counter File: C:/work/blinky/rtl/counter.sv Line: 1
        Warning (10230): Verilog HDL assignment warning at blinky.sv(9): truncated value with size 8 to match size of target (4) File: C:/work/blinky/blinky.sv Line: 9
        Critical Warning (169085): No exact pin location assignment(s) for 3 pins of 6 total pins.
        Info: Quartus Prime Analysis & Synthesis was successful. 0 errors, 3 warnings
            Info: Peak virtual memory: 4849 megabytes
    """.trimIndent() + "\n"

    @Test
    fun messageWithLocationIsParsed() {
        val message = QuartusMessage.parse(
            "Warning (10230): Verilog HDL assignment warning at blinky.sv(9): truncated value File: C:/work/blinky/blinky.sv Line: 9"
        )!!
        assertEquals(QuartusSeverity.WARNING, message.severity)
        assertEquals("10230", message.code)
        assertEquals("Verilog HDL assignment warning at blinky.sv(9): truncated value", message.text)
        assertEquals("C:/work/blinky/blinky.sv", message.file)
        assertEquals(9, message.line)
        assertEquals(0, message.depth)
    }

    @Test
    fun severitiesAndDepthAreRead() {
        assertEquals(QuartusSeverity.CRITICAL_WARNING, QuartusMessage.parse("Critical Warning (169085): No exact pin location")!!.severity)
        val detail = QuartusMessage.parse("        Extra Info (176219): No registers were packed into other blocks")!!
        assertEquals(QuartusSeverity.EXTRA_INFO, detail.severity)
        assertEquals(2, detail.depth)
        val banner = QuartusMessage.parse("Info: Running Quartus Prime Fitter")!!
        assertNull(banner.code)
        assertNull(QuartusMessage.parse("TBBmalloc: skip allocation functions replacement"))
        assertNull(QuartusMessage.parse("1) USB-Blaster [USB-0]"))
    }

    @Test
    fun adviceOfSyntaxErrorsIsLeftOut() {
        val message = QuartusMessage.parse(
            "Error (10170): Verilog HDL syntax error at blinky.sv(10) near text: \"assign\";  expecting \";\". Check for and fix any " +
                "syntax errors that appear immediately before or at the specified keyword. The Intel FPGA Knowledge Database contains " +
                "many articles with specific details on how to resolve this error. Visit the Knowledge Database at " +
                "https://www.altera.com/support and search for this specific error message number. File: C:/a b/blinky.sv Line: 10"
        )!!
        assertTrue(message.text, message.text.endsWith("at the specified keyword."))
        assertEquals("C:/a b/blinky.sv", message.file)
        assertEquals(10, message.line)
    }

    @Test
    fun toolsOwnLinesAreNotReported() {
        fun reported(line: String) = QuartusReports.isReported(QuartusMessage.parse(line)!!)
        assertTrue(reported("Info (12021): Found 1 design units"))
        assertTrue(reported("Error: Top-level design entity \"x\" is undefined"))
        assertFalse(reported("Info: Running Quartus Prime Fitter"))
        assertFalse(reported("Error: Quartus Prime Analysis & Elaboration was unsuccessful. 2 errors, 1 warning"))
        assertFalse(reported("    Error: Peak virtual memory: 4744 megabytes"))
    }

    @Test
    fun summaryLinesAreRead() {
        val summary = QuartusReports.summary(fitSummary)
        assertEquals("Fitter Status" to "Successful - Thu Nov 19 15:36:20 2026", summary.first())
        assertTrue(summary.contains("Total logic elements" to "28 / 49,760 (< 1 %)"))
        // A part of the line above keeps its indentation.
        assertTrue(summary.contains("  Dedicated logic registers" to "26 / 49,760 (< 1 %)"))
    }

    @Test
    fun slacksAreRead() {
        val slacks = QuartusReports.slacks(staSummary)
        assertEquals(5, slacks.size)
        assertEquals(QuartusReports.Slack("Slow 1200mV 85C", "Setup", "clk_50", 16.789, 0.0), slacks[0])
        assertEquals(QuartusReports.Slack("Slow 1200mV 85C", "Minimum Pulse Width", "clk_50", 9.638, 0.0), slacks[2])
        assertEquals(QuartusReports.Slack("Fast 1200mV 0C", "Setup", "u_pll|pll1|clk[0]", -1.25, -37.5), slacks[3])
    }

    @Test
    fun fmaxIsReadPerCorner() {
        val fmax = QuartusReports.fmax(staReport)
        assertEquals(
            listOf(
                QuartusReports.Fmax("Slow 1200mV 85C", "clk_50", 311.43, 250.0),
                QuartusReports.Fmax("Slow 1200mV 85C", "spi_sclk", 98.5, 98.5),
                QuartusReports.Fmax("Slow 1200mV 0C", "clk_50", 347.46, 240.0),
            ),
            fmax,
        )
    }

    @Test
    fun stepsOfTheFlowAreRead() {
        assertEquals(
            listOf(QuartusReports.Step("Analysis & Synthesis", "00:00:11"), QuartusReports.Step("Fitter", "00:00:15")),
            QuartusReports.steps(flowReport),
        )
        assertTrue(QuartusReports.steps("no table").isEmpty())
    }

    @Test
    fun messagesOfAReportAreRead() {
        val messages = QuartusReports.messages(mapReport)
        assertEquals(listOf("18236", "12021", "12023", "10230", "169085"), messages.map { it.code })
        assertEquals(1, messages[2].depth)
        assertEquals("C:/work/blinky/blinky.sv", messages[3].file)
        assertTrue(QuartusReports.messages("Fitter report without messages").isEmpty())
    }

    @Test
    fun suppressedMessagesOfTheFitterEndItsMessages() {
        val report = """
            +-----------------+
            ; Fitter Messages ;
            +-----------------+
            Critical Warning (169085): No exact pin location assignment(s) for 3 pins of 6 total pins.
            Info: Quartus Prime Fitter was successful. 0 errors, 5 warnings


            +----------------------------+
            ; Fitter Suppressed Messages ;
            +----------------------------+
            The suppressed messages can be found in C:/work/output_files/blinky.fit.smsg.
        """.trimIndent() + "\n"
        assertEquals(listOf("169085"), QuartusReports.messages(report).map { it.code })
    }

    private fun revision(): QuartusRevision {
        Files.writeString(root.resolve("blinky.qsf"), "set_global_assignment -name FAMILY \"MAX 10\"\n" +
            "set_global_assignment -name DEVICE 10M50DAF484C7G\nset_global_assignment -name PROJECT_OUTPUT_DIRECTORY output_files\n")
        val output = Files.createDirectories(root.resolve("output_files"))
        Files.writeString(output.resolve("blinky.fit.summary"), fitSummary)
        Files.writeString(output.resolve("blinky.sta.summary"), staSummary)
        Files.writeString(output.resolve("blinky.sta.rpt"), staReport)
        Files.writeString(output.resolve("blinky.flow.rpt"), flowReport)
        Files.writeString(output.resolve("blinky.map.rpt"), mapReport)
        Files.writeString(output.resolve("blinky.sof"), "")
        return QuartusProject.of(root.resolve("blinky.qsf"))!!.revision()
    }

    @Test
    fun compilationSumsUpTheReports() {
        val compilation = QuartusCompilation(revision())
        assertEquals("Successful - Thu Nov 19 15:36:25 2026", compilation.status)
        assertEquals(2, compilation.steps.size)
        // What the header shows already is not a resource.
        assertEquals("Total logic elements", compilation.resources.first().first)
        assertEquals(listOf("Setup", "Hold", "Minimum Pulse Width"), compilation.worstSlacks.map { it.check })
        assertEquals(-1.25, compilation.worstSlacks.first().slack, 0.0)
        assertEquals(listOf("u_pll|pll1|clk[0]"), compilation.failingSlacks.map { it.clock })
        // The Fmax that holds is the lowest of the corners.
        assertEquals(240.0, compilation.worstFmax.first { it.clock == "clk_50" }.restricted, 0.0)
        assertEquals(listOf(root.resolve("output_files/blinky.sof")), compilation.programmingFiles)
        assertEquals(listOf("Flow Summary", "Analysis & Synthesis", "Timing Analyzer"), compilation.reports.map { it.first })
        assertEquals(root.resolve("output_files/blinky.sta.rpt"), compilation.report("sta.rpt"))
        assertEquals(5, QuartusCompilation.messages(revision()).size)
    }

    @Test
    fun compilationWithoutReportsIsEmpty() {
        Files.writeString(root.resolve("fresh.qsf"), "set_global_assignment -name DEVICE X\n")
        val compilation = QuartusCompilation(QuartusProject.of(root.resolve("fresh.qsf"))!!.revision())
        assertNull(compilation.status)
        assertTrue(compilation.resources.isEmpty() && compilation.slacks.isEmpty() && compilation.reports.isEmpty())
    }

    @Test
    fun conclusionOfACompilationNamesResourcesAndFailingTiming() {
        val messages = listOf(
            QuartusMessage(QuartusSeverity.WARNING, "10230", "truncated value"),
            QuartusMessage(QuartusSeverity.CRITICAL_WARNING, "332148", "Timing requirements not met"),
        )
        val lines = QuartusConsole.conclusion(revision(), QuartusFlow.COMPILE, 0, messages, 95_000)
        val text = lines.joinToString("\n") { it.first }
        assertTrue(text, "Compile Design of blinky for MAX 10 10M50DAF484C7G finished in 1:35 min, with 1 critical warning, 1 warning." in text)
        assertTrue(text, "Total logic elements: 28 / 49,760 (< 1 %)" in text)
        assertTrue(text, "Timing is NOT met:" in text)
        assertTrue(text, "Setup slack -1.25 ns for clock 'u_pll|pll1|clk[0]' (Fast 1200mV 0C), total negative slack -37.5 ns" in text)
        assertTrue(text, "Programming file: ${root.resolve("output_files/blinky.sof")}" in text)
        assertEquals(ProcessOutputTypes.STDERR, lines.first { "NOT met" in it.first }.second)
    }

    @Test
    fun conclusionOfAFailureListsTheErrors() {
        val messages = listOf(
            QuartusMessage(QuartusSeverity.ERROR, "10170", "Verilog HDL syntax error at blinky.sv(10)", "C:/work/blinky.sv", 10),
            QuartusMessage(QuartusSeverity.ERROR, "10112", "Ignored design unit", "C:/work/blinky.sv", 1),
        )
        val lines = QuartusConsole.conclusion(revision(), QuartusFlow.ANALYSIS, 3, messages, 4_000)
        assertTrue(lines.all { it.first.isEmpty() || it.second == ProcessOutputTypes.STDERR })
        val text = lines.joinToString("\n") { it.first }
        assertTrue(text, "Analysis & Elaboration of blinky failed after 4 s with 2 errors:" in text)
        assertTrue(text, "  (10170) Verilog HDL syntax error at blinky.sv(10) File: C:/work/blinky.sv Line: 10" in text)
    }

    @Test
    fun elapsedTimeIsShort() {
        assertEquals("34 s", QuartusConsole.elapsed(34_400))
        assertEquals("1:05 min", QuartusConsole.elapsed(65_000))
    }
}
