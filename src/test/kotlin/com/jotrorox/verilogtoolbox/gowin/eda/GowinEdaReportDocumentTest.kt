package com.jotrorox.verilogtoolbox.gowin.eda

import com.jotrorox.verilogtoolbox.quartus.QuartusReportDocument
import com.jotrorox.verilogtoolbox.quartus.QuartusSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GOWIN EDA's reports, as 1.9.12.04 wrote them for a blinky on a Tang Nano 9K, read into the sections of the viewer. */
class GowinEdaReportDocumentTest {
    private fun QuartusReportDocument.table(title: String) = sections.filterIsInstance<QuartusReportDocument.Table>().single { it.title == title }

    @Test
    fun knowsTheReportsByNameAndPlace() {
        assertTrue(GowinEdaReportDocument.isReport("C:\\p\\impl\\pnr\\blinky.tr"))
        assertTrue(GowinEdaReportDocument.isReport("/p/impl/pnr/blinky.rpt.txt"))
        assertTrue(GowinEdaReportDocument.isReport("/p/impl/gwsynthesis/blinky.log"))
        assertTrue(GowinEdaReportDocument.isReport("/p/impl/gwsynthesis/blinky_syn.rpt.html"))
        assertTrue(GowinEdaReportDocument.isReport("/p/impl/pnr/blinky.power.html"))
        assertFalse("the frames of the timing report", GowinEdaReportDocument.isReport("/p/impl/pnr/blinky.tr.html"))
        assertFalse("not where the flow writes", GowinEdaReportDocument.isReport("/p/docs/talk.tr"))
        assertFalse(GowinEdaReportDocument.isReport("/p/impl/pnr/cmd.do"))
    }

    private val pnrReport = """
        //Copyright (C)2014-2026 Gowin Semiconductor Corporation.
        //All rights reserved.


        1. PnR Messages

          <Report Title>: PnR Report
          <Design File>: C:\p\impl\gwsynthesis\blinky.vg
          <Part Number>: GW1NR-LV9QN88PC6/I5
          <Device>: GW1NR-9
          <Device Version>: C
          <Created Time>:Thu Nov 26 11:43:35 2026


        2. PnR Details

          Running placement:
            Placement Phase 0: CPU time = 0h 0m 0.011s, Elapsed time = 0h 0m 0.011s
         Generate output files:
            CPU time = 0h 0m 0.605s, Elapsed time = 0h 0m 0.605s


        3. Resource Usage Summary

          --------------------------------------------------------------------------------
          Resources                   | Usage                               | Utilization
          --------------------------------------------------------------------------------
          Logic                       | 45/8640                             |  <1%
            --LUT,ALU,ROM16           | 45(45 LUT, 0 ALU, 0 ROM16)          | -
          Register                    | 30/6693                             |  <1%
          ================================================================================


        8. All Package Pins

        ----------------------------------------------------------------------
        Loc./Bank| Signal  | Dir.  | Site     | IO Type  | Bank Vccio
        ----------------------------------------------------------------------
        3/3      | rst_n   | in    | IOT2[A]  | LVCMOS18 | 1.8
        ----------------------------------------------------------------------
        52/1     | clk     | in    | IOR17[A] | LVCMOS33 | 3.3
        ======================================================================
    """.trimIndent()

    @Test
    fun readsThePlaceAndRouteReport() {
        val report = GowinEdaReportDocument.parse(pnrReport, "blinky.rpt.txt")
        assertEquals("PnR Report of blinky", report.title)
        assertEquals(listOf("GW1NR-LV9QN88PC6/I5", "GW1NR-9 version C", "Thu Nov 26 11:43:35 2026"), report.details)
        assertEquals(listOf("PnR Messages", "PnR Details", "Resource Usage Summary", "All Package Pins"), report.sections.map { it.title })

        assertEquals("GW1NR-LV9QN88PC6/I5", report.table("PnR Messages").value("Part Number"))
        val resources = report.table("Resource Usage Summary")
        assertEquals(listOf("Resources", "Usage", "Utilization"), resources.columns)
        assertEquals(listOf("Logic", "LUT,ALU,ROM16", "Register"), resources.rows.map { it.cells[0].text })
        assertEquals("a part of Logic is set in", 1, resources.rows[1].cells[0].indent)
        assertEquals("45/8640", resources.rows[0].cells[1].text)
        assertEquals(pnrReport.lines().indexOfFirst { "Logic " in it }, resources.rows[0].line)
        val pins = report.table("All Package Pins")
        assertEquals(listOf("3/3", "52/1"), pins.rows.map { it.cells[0].text })
        assertTrue(report.sections[1] is QuartusReportDocument.Text)
    }

    private val timingReport = """
        Note:Timing Report Directory

        1. Timing Message
        2. Timing Summaries
        	2.2 Clock Summary

        Note:Core Timing Report

        1. Timing Messages
        <Report Title>: Timing Analysis Report
        <Design File>: C:\p\impl\gwsynthesis\blinky.vg
        <Part Number>: GW1NR-LV9QN88PC6/I5
        <Device>: GW1NR-9

        2. Timing Summaries
        2.1 STA Tool Run Summary
        <Numbers of Setup Violated Endpoints>:0
        <Numbers of Hold Violated Endpoints>:0

        2.2 Clock Summary
          NO.   Clock Name   Type   Period   Frequency   Rise     Fall    Source   Master   Objects
         ===== ============ ====== ======== =========== ======= ======== ======== ======== =========
          1     clk          Base   37.037   27.000MHz   0.000   18.518                     clk

        3. Timing Details
        3.1 Path Slacks Table
        3.1.1 Setup Paths Table
        <Report Command>:report_timing -setup -max_paths 25 -max_common_paths 1
          Path Number   Path Slack       From Node             To Node         From Clock
         ============= ============ ==================== ==================== ============
          1             27.898       step/count_8_s0/Q    step/count_16_s0/D   clk:[R]
          2             28.507       step/count_8_s0/Q    step/count_19_s0/D   clk:[R]

        3.1.2 Hold Paths Table
        <Report Command>:report_timing -hold -max_paths 25 -max_common_paths 1
          Path Number   Path Slack       From Node             To Node         From Clock
         ============= ============ ==================== ==================== ============
          1             0.708        step/count_22_s0/Q   step/count_22_s0/D   clk:[R]

        3.3 Timing Report By Analysis Type
        3.3.1 Setup Analysis Report
        Report Command:report_timing -setup -max_paths 25 -max_common_paths 1
        						Path1
        Path Summary:
        Slack             : 27.898
        From              : step/count_8_s0
        To                : step/count_16_s0

        Data Arrival Path:
            AT     DELAY   TYPE   RF   FANOUT       LOC                 NODE
         ======== ======= ====== ==== ======== ============== ========================
          0.000    0.000                                       active clock edge time

        						Path2
        Path Summary:
        Slack             : 27.898
        From              : step/count_8_s0
        To                : step/count_17_s0

        3.8 Timing Constraints Report
          SDC Command Type    State                                   Detail Command
         ================== ========= ==============================================================================
          TC_CLOCK           Actived   create_clock -name clk -period 37.037 -waveform {0 18.518} [get_ports {clk}]
    """.trimIndent()

    @Test
    fun readsTheTimingReport() {
        val report = GowinEdaReportDocument.parse(timingReport, "blinky.tr")
        assertEquals("Timing Analysis Report of blinky", report.title)
        assertEquals("Timing requirements met (setup slack 27.898 ns, hold slack 0.708 ns)", report.verdict)
        assertEquals(
            listOf(
                "Timing Messages", "Timing Summaries: STA Tool Run Summary", "Timing Summaries: Clock Summary",
                "Path Slacks Table: Setup Paths Table", "Path Slacks Table: Hold Paths Table",
                "Setup Analysis Report: Path 1, slack 27.898, step/count_8_s0 → step/count_16_s0",
                "Setup Analysis Report: Path 2, slack 27.898, step/count_8_s0 → step/count_17_s0",
                "Timing Constraints Report",
            ),
            report.sections.map { it.title },
        )
        val clocks = report.table("Timing Summaries: Clock Summary")
        assertEquals(listOf("NO.", "Clock Name", "Type", "Period", "Frequency", "Rise", "Fall", "Source", "Master", "Objects"), clocks.columns)
        assertEquals(listOf("1", "clk", "Base", "37.037", "27.000MHz", "0.000", "18.518", "", "", "clk"), clocks.rows.single().cells.map { it.text })
        val setup = report.table("Path Slacks Table: Setup Paths Table")
        assertEquals(listOf("Report Command: report_timing -setup -max_paths 25 -max_common_paths 1"), setup.preface)
        assertEquals("Path Slack", setup.columns[1])
        assertEquals(listOf("27.898", "28.507"), setup.rows.map { it.cells[1].text })
        val path = report.sections.first { it.title.startsWith("Setup Analysis Report: Path 1") } as QuartusReportDocument.Text
        assertTrue(path.text.startsWith("Path Summary:"))
        assertTrue(path.text.contains("active clock edge time"))
        assertEquals(timingReport.lines().indexOfFirst { it.trim() == "Path1" }, path.line)
        val constraints = report.table("Timing Constraints Report")
        assertEquals("create_clock -name clk -period 37.037 -waveform {0 18.518} [get_ports {clk}]", constraints.rows.single().cells[2].text)

        val failing = GowinEdaReportDocument.parse(timingReport.replace("1             27.898", "1             -1.250"), "blinky.tr")
        assertEquals("Timing requirements not met: setup slack -1.25 ns", failing.verdict)
    }

    @Test
    fun readsLogs() {
        val synthesis = """
            GowinSynthesis start
            Running parser ...
            ERROR (EX3863) : Syntax error near 'wire'("C:\p\src\blinky.v":21)
            WARN  (EX3791) : Expression size 5 truncated to fit in target size 4("C:\p\src\divider.v":24)
            NOTE  (EX0101) : Current top module is "blinky"
            GowinSynthesis finish
        """.trimIndent()
        val report = GowinEdaReportDocument.parse(synthesis, "blinky.log")
        assertEquals("GowinSynthesis Log", report.title)
        assertEquals("Failed with 1 error", report.verdict)
        val messages = report.sections.first() as QuartusReportDocument.Messages
        assertEquals(listOf(QuartusSeverity.ERROR, QuartusSeverity.WARNING, QuartusSeverity.INFO), messages.messages.map { it.first.severity })
        assertEquals("C:\\p\\src\\blinky.v", messages.messages[0].first.file)
        assertEquals(21, messages.messages[0].first.line)
        assertEquals(2, messages.messages[0].second)
        assertEquals("Log", report.sections.last().title)

        val pnr = GowinEdaReportDocument.parse("Running placement......\nPlacement and routing completed\nThu Nov 26 11:43:35 2026\n", "blinky.log")
        assertEquals("Place & Route Log", pnr.title)
        assertEquals("Successful", pnr.verdict)
        assertEquals(listOf("Thu Nov 26 11:43:35 2026"), pnr.details)
        assertEquals(listOf("Log"), pnr.sections.map { it.title })
    }

    private val synthesisHtml = """
        <html><head><title>GowinSynthesis Report</title><style>table, th, td { border: 1px solid #aaa; }</style></head>
        <body><div id="main_wrapper"><div id="catalog_wrapper"><div id="catalog"><ul>
        <li><a href="#about" style=" font-size: 16px;">Synthesis Messages</a></li>
        </ul></div><!-- catalog --></div><!-- catalog_wrapper --><div id="content">
        <h1><a name="about">Synthesis Messages</a></h1>
        <table class="summary_table">
        <tr>
        <td class="label">Report Title</td>
        <td>GowinSynthesis Report</td>
        </tr>
        <tr>
        <td class="label">Design File</td>
        <td>C:\p\src\blinky.v<br>
        C:\p\src\divider.v<br>
        </td>
        </tr>
        <tr>
        <td class="label">Part Number</td>
        <td>GW1NR-LV9QN88PC6/I5</td>
        </tr>
        </table>
        <h1><a name="resource">Resource</a></h1>
        <h2><a name="usage">Resource Usage Summary</a></h2>
        <table class="summary_table">
        <tr>
        <td class="label"><b>Resource</b></td>
        <td><b>Usage</b></td>
        </tr>
        <tr>
        <td class="label"><b>Register </b></td>
        <td>30</td>
        </tr>
        <tr>
        <td class="label">&nbsp&nbsp&nbsp&nbspDFFC</td>
        <td>24</td>
        </tr>
        </table>
        <h2><a name="utilization">Resource Utilization Summary</a></h2>
        <table class="summary_table">
        <tr><td class="label"><b>Resource</b></td><td><b>Usage</b></td><td><b>Utilization</b></td></tr>
        <tr><td class="label">Register</td><td>30 / 6693</td><td><1%</td></tr>
        <tr><td class="label">&nbsp&nbsp--Register as FF</td><td>30 / 6693</td><td><1%</td></tr>
        </table>
        <h1><a name="timing">Timing</a></h1>
        <h2><a name="clock">Clock Summary:</a></h2>
        <table class="summary_table">
        <tr><th>NO.</th><th>Clock Name</th><th>Period</th></tr>
        <tr><td>1</td><td>clk</td><td>20.000</td></tr>
        </table>
        <h2><a name="detail timing">Detail Timing Paths Information</a></h2>
        <h3>Path&nbsp1</h3>
        <b>Path Summary:</b></br>
        <table class="summary_table">
        <tr><td class="label">Slack</td><td>10.489</td></tr>
        </table>
        <b>Data Arrival Path:</b>
        <table class="summary_table">
        <tr><th>AT</th><th>NODE</th></tr>
        <tr><td>0.000</td><td>clk</td></tr>
        </table>
        </div></div></body></html>
    """.trimIndent()

    @Test
    fun readsHtmlReports() {
        val report = GowinEdaReportDocument.parse(synthesisHtml, "blinky_syn.rpt.html")
        assertEquals("GowinSynthesis Report of blinky", report.title)
        assertEquals(listOf("GW1NR-LV9QN88PC6/I5"), report.details)
        assertNull(report.verdict)
        assertEquals(
            listOf(
                "Synthesis Messages", "Resource: Resource Usage Summary", "Resource: Resource Utilization Summary",
                "Clock Summary", "Path 1: Path Summary", "Path 1: Data Arrival Path",
            ),
            report.sections.map { it.title },
        )
        assertEquals("C:\\p\\src\\blinky.v; C:\\p\\src\\divider.v", report.table("Synthesis Messages").value("Design File"))
        val usage = report.table("Resource: Resource Usage Summary")
        assertEquals(listOf("Resource", "Usage"), usage.columns)
        assertEquals(listOf("Register" to 0, "DFFC" to 1), usage.rows.map { it.cells[0].text to it.cells[0].indent })
        val utilization = report.table("Resource: Resource Utilization Summary")
        assertEquals(listOf("<1%", "<1%"), utilization.rows.map { it.cells[2].text })
        assertEquals(1, utilization.rows[1].cells[0].indent)
        assertEquals("Register as FF", utilization.rows[1].cells[0].text)
        assertEquals(listOf("NO.", "Clock Name", "Period"), report.table("Clock Summary").columns)
        assertEquals(listOf("AT", "NODE"), report.table("Path 1: Data Arrival Path").columns)
        assertEquals(synthesisHtml.lines().indexOfFirst { "Detail Timing" in it } + 3, report.table("Path 1: Path Summary").line)

        val power = GowinEdaReportDocument.parse(
            """<h1><a name="Summary">Power Summary</a></h1><h2><a name="Power_Info">Power Information:</a></h2>
               <table><tr><td class="label">Total Power (mW)</td><td>24.904</td></tr></table>""",
            "blinky.power.html",
        )
        assertEquals("Total Power (mW): 24.904", power.verdict)
        assertEquals(listOf("Power Information"), power.sections.map { it.title })
    }
}
