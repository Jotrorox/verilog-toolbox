package com.jotrorox.verilogtoolbox.quartus

import com.jotrorox.verilogtoolbox.quartus.tcl.QuartusTclReportFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuartusReportDocumentTest {
    /** The shapes of a real Fitter report, cut short. */
    private val report = """
        Fitter report for blinky
        Mon Nov 16 11:56:52 2026
        Quartus Prime Version 25.1std.0 Build 1129 10/21/2025 SC Lite Edition


        ---------------------
        ; Table of Contents ;
        ---------------------
          1. Legal Notice
          2. Fitter Summary



        ----------------
        ; Legal Notice ;
        ----------------
        Copyright (C) 2025  Altera Corporation. All rights reserved.



        +--------------------------------------------------------------+
        ; Fitter Summary                                               ;
        +------------------------------------+-------------------------+
        ; Fitter Status                      ; Successful - Mon Nov 16 ;
        ; Total logic elements               ; 9,993 / 49,760 ( 20 % ) ;
        ;     Total combinational functions  ; 9,081 / 49,760 ( 18 % ) ;
        +------------------------------------+-------------------------+


        +----------------------------------+
        ; Assembler Settings               ;
        +--------+---------+---------------+
        ; Option ; Setting ; Default Value ;
        +--------+---------+---------------+


        +------------------------------------------------------+
        ; Fitter Resource Utilization by Entity                ;
        +----------------------------+-------------+-----------+
        ; Compilation Hierarchy Node ; Logic Cells ; Full Name ;
        +----------------------------+-------------+-----------+
        ; |blinky                    ; 28 (2)      ; a;b       ;
        ;    |counter:u_counter|     ; 26 (26)     ;           ;
        +----------------------------+-------------+-----------+
        Note: For table entries with two numbers listed, the numbers in parentheses are of the entity itself.


        Encoding Type:  One-Hot
        +----------------------------------------------+
        ; State Machine - |blinky|state                ;
        +-------------+---------------+----------------+
        ; Name        ; state.IDLE    ; state.RUN      ;
        +-------------+---------------+----------------+
        ; state.IDLE  ; 0             ; 0              ;
        +-------------+---------------+----------------+


        +----------------------------------------------+
        ; State Machine - |blinky|mode                 ;
        +-------------+---------------+----------------+
        ; Name        ; mode.A        ; mode.B         ;
        +-------------+---------------+----------------+
        ; mode.A      ; 0             ; 0              ;
        +-------------+---------------+----------------+


        +--------------+
        ; Pin-Out File ;
        +--------------+
        The pin-out file can be found in C:/work/output_files/blinky.pin.


        +-----------------+
        ; Fitter Messages ;
        +-----------------+
        Info (20032): Parallel compilation is enabled and will use up to 4 processors
        Warning (292013): Feature LogicLock is only available with a valid subscription license.
            Info (176445): Device 10M08DAF484I7G is compatible
        Critical Warning (332012): Synopsys Design Constraints File file not found: 'blinky.sdc'.


        +----------------------------+
        ; Fitter Suppressed Messages ;
        +----------------------------+
        The suppressed messages can be found in C:/work/output_files/blinky.fit.smsg.
    """.trimIndent() + "\n"

    private val document = QuartusReportDocument.parse(report)

    @Test
    fun headerAndStatus() {
        assertEquals("Fitter report for blinky", document.title)
        assertEquals(listOf("Mon Nov 16 11:56:52 2026", "Quartus Prime Version 25.1std.0 Build 1129 10/21/2025 SC Lite Edition"), document.details)
        assertEquals("Successful - Mon Nov 16", document.status)
    }

    @Test
    fun theContentsAndTheLegalNoticeAreLeftOut() {
        assertEquals(
            listOf(
                "Fitter Summary", "Assembler Settings", "Fitter Resource Utilization by Entity", "State Machine - |blinky|state",
                "State Machine - |blinky|mode", "Pin-Out File", "Fitter Messages", "Fitter Suppressed Messages",
            ),
            document.sections.map { it.title },
        )
    }

    @Test
    fun summaryHasNoColumnNamesAndKeepsItsIndentation() {
        val summary = document.summary!!
        assertTrue(summary.columns.isEmpty())
        assertEquals(2, summary.width)
        assertEquals("9,993 / 49,760 ( 20 % )", summary.value("Total logic elements"))
        val part = summary.rows[2].cells[0]
        assertEquals("Total combinational functions", part.text)
        assertEquals(4, part.indent)
        assertEquals(report.lines().indexOfFirst { it.startsWith("; Fitter Status") }, summary.rows[0].line)
    }

    @Test
    fun aTableOfItsHeaderAloneHasNoRows() {
        val settings = document.sections[1] as QuartusReportDocument.Table
        assertEquals(listOf("Option", "Setting", "Default Value"), settings.columns)
        assertTrue(settings.rows.isEmpty())
    }

    @Test
    fun cellsAreWhereTheRuleSaysEvenWithASemicolon() {
        val table = document.sections[2] as QuartusReportDocument.Table
        assertEquals(listOf("Compilation Hierarchy Node", "Logic Cells", "Full Name"), table.columns)
        assertEquals(listOf("|blinky", "28 (2)", "a;b"), table.rows[0].cells.map { it.text })
        assertEquals(3, table.rows[1].cells[0].indent)
        assertEquals(0, table.rows[1].cells[2].indent)
        assertEquals(listOf("Note: For table entries with two numbers listed, the numbers in parentheses are of the entity itself."), table.notes)
        assertTrue(table.contains("COUNTER"))
    }

    @Test
    fun textAboveABoxIsItsPreface() {
        assertEquals(listOf("Encoding Type:  One-Hot"), document.sections[3].preface)
        assertTrue(document.sections[2].preface.isEmpty())
    }

    @Test
    fun boxesOfTextAndMessages() {
        val pinOut = document.sections[5] as QuartusReportDocument.Text
        assertEquals("The pin-out file can be found in C:/work/output_files/blinky.pin.", pinOut.text)
        val messages = document.sections[6] as QuartusReportDocument.Messages
        assertEquals(4, messages.messages.size)
        assertEquals(QuartusSeverity.CRITICAL_WARNING, messages.worst)
        assertEquals(1, messages.count(QuartusSeverity.WARNING))
        assertEquals(1, messages.count(QuartusSeverity.INFO))
        val (detail, line) = messages.messages[2]
        assertEquals(1, detail.depth)
        assertEquals("    Info (176445): Device 10M08DAF484I7G is compatible", report.lines()[line])
        // The suppressed messages are a pointer to another file, not messages.
        assertTrue(document.sections[7] is QuartusReportDocument.Text)
    }

    @Test
    fun pinFileIsOneTableWithItsLegend() {
        val pin = QuartusReportDocument.parse(
            """
             -- Copyright (C) 2025  Altera Corporation. All rights reserved.
             ---------------------------------------------------------------------------------

             ---------------------------------------------------------------------------------
             -- NC            : No Connect. This pin has no internal connection to the device.
             ---------------------------------------------------------------------------------

            Quartus Prime Version 25.1std.0 Build 1129 10/21/2025 SC Lite Edition
            CHIP  "blinky"  ASSIGNED TO AN: 10M50DAF484C7G

            Pin Name/Usage               : Location  : Dir.   : I/O Standard      : Voltage : I/O Bank  : User Assignment
            -------------------------------------------------------------------------------------------------------------
            GND                          : A1        : gnd    :                   :         :           :
            led[0]                       : A8        : output : 3.3-V LVTTL       :         : 7         : Y
            """.trimIndent(),
        )
        assertEquals("Pin-out of blinky on 10M50DAF484C7G", pin.title)
        val table = pin.sections[0] as QuartusReportDocument.Table
        assertEquals("Location", table.columns[1])
        assertEquals(listOf("led[0]", "A8", "output", "3.3-V LVTTL", "", "7", "Y"), table.rows[1].cells.map { it.text })
        val legend = pin.sections[1] as QuartusReportDocument.Text
        assertEquals("NC            : No Connect. This pin has no internal connection to the device.", legend.text)
    }

    @Test
    fun groupsAreRunsWithACommonStart() {
        val groups = QuartusReportGroups.group(document.sections)
        assertEquals("State Machine", groups[3].first)
        assertEquals(2, groups[3].second.size)
        assertEquals("|blinky|state", QuartusReportGroups.label(groups[3].second[0].title, "State Machine"))
        assertNull(groups[0].first)
        assertEquals("Slow 1200mV 85C Model", QuartusReportGroups.key("Slow 1200mV 85C Model Fmax Summary"))
        assertEquals("Fmax Summary", QuartusReportGroups.label("Slow 1200mV 85C Model Fmax Summary", "Slow 1200mV 85C Model"))
        assertEquals("Source assignments", QuartusReportGroups.key("Source assignments for top|ram:u_ram"))
        assertEquals("top|ram:u_ram", QuartusReportGroups.label("Source assignments for top|ram:u_ram", "Source assignments"))
        assertEquals("top|sub:u", QuartusReportGroups.label("Port Connectivity Checks: \"top|sub:u\"", "Port Connectivity Checks"))
        assertNull(QuartusReportGroups.key("Signal Integrity Metrics (Slow 1200mv 0c Model)"))
    }

    @Test
    fun valuesSortAsNumbers() {
        assertEquals(9993.0, QuartusReportValues.number("9993 (893)")!!, 0.0)
        assertEquals(54.45, QuartusReportValues.number("54.45 MHz")!!, 0.0)
        assertEquals(-1.25, QuartusReportValues.number("-1.250")!!, 0.0)
        assertEquals(93.0, QuartusReportValues.number("00:01:33")!!, 0.0)
        assertNull(QuartusReportValues.number("clk_50"))
        assertNull(QuartusReportValues.number("25.1std.0 Build 1129"))
        assertEquals(100.0, QuartusReportValues.number("100.0%")!!, 0.0)
        val cells = listOf("10 MHz", "", "9.5 MHz", "clk", "100 MHz").map { QuartusReportDocument.Cell(it) }
        assertEquals(listOf("9.5 MHz", "10 MHz", "100 MHz", "clk", ""), cells.sortedWith(QuartusReportValues.COMPARATOR).map { it.text })
    }

    private fun timing(setup: String, hold: String) = QuartusReportDocument.parse(
        """
        +-----------------------------------------------------+
        ; Multicorner Timing Analysis Summary                 ;
        +------------------+---------+-------+----------------+
        ; Clock            ; Setup   ; Hold  ; Recovery       ;
        +------------------+---------+-------+----------------+
        ; Worst-case Slack ; $setup  ; $hold ; N/A            ;
        ;  clk_50          ; $setup  ; $hold ; N/A            ;
        +------------------+---------+-------+----------------+
        """.trimIndent(),
    )

    @Test
    fun timingReportsSayWhetherTimingIsMet() {
        val met = timing("2.904", "0.090")
        assertNull(met.status)
        assertEquals("Timing requirements met (setup slack 2.904 ns, hold slack 0.09 ns)", met.timing)
        assertEquals("Timing requirements not met: setup slack -1.25 ns", timing("-1.250", "0.090").timing)
        assertNull(document.timing)
    }

    @Test
    fun usageAndVerdicts() {
        assertEquals(9993.0 / 49760.0, QuartusReportValues.usage("9,993 / 49,760 ( 20 % )")!!, 1e-9)
        assertNull(QuartusReportValues.usage("4938"))
        assertEquals(QuartusReportValues.Verdict.BAD, QuartusReportValues.verdict("-0.250", "Slack"))
        assertNull(QuartusReportValues.verdict("-0.250", "Offset"))
        assertEquals(QuartusReportValues.Verdict.GOOD, QuartusReportValues.verdict("Successful - Mon Nov 16", ""))
        assertEquals(QuartusReportValues.Verdict.BAD, QuartusReportValues.verdict("Fail", "IO_000001"))
        assertEquals(QuartusReportValues.Verdict.BAD, QuartusReportValues.verdict("-0.250", "Setup", "Multicorner Timing Analysis Summary"))
        assertEquals(QuartusReportValues.Verdict.BAD, QuartusReportValues.verdict("Timing requirements not met: setup slack -1.25 ns", ""))
    }

    @Test
    fun reportsInTheWindowsCodePageAreRead() {
        val bytes = "; Low Junction Temperature ; 0 \u00b0C ;\n".toByteArray(charset("windows-1252"))
        assertEquals("; Low Junction Temperature ; 0 \u00b0C ;\n", QuartusTclReportFileType.decode(bytes))
        assertEquals("plain", QuartusTclReportFileType.decode("plain".toByteArray()))
    }
}
