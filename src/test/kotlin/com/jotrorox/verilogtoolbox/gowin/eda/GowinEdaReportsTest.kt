package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.execution.process.ProcessOutputTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reports and output of GOWIN EDA 1.9.12.04, as it wrote them for a blinky on a Tang Nano 9K. */
class GowinEdaReportsTest {
    @Test
    fun readsMessagesInBothLayouts() {
        val synthesis = GowinEdaMessage.parse("""ERROR (EX3863) : Syntax error near 'wire'("C:\p\src\blinky.v":21)""")!!
        assertEquals(GowinEdaMessage(GowinEdaSeverity.ERROR, "EX3863", "Syntax error near 'wire'", "C:\\p\\src\\blinky.v", 21), synthesis)
        val constraint = GowinEdaMessage.parse("""ERROR  (CT1135) : "C:\p\src\blinky.cst":2 | Can't find object named 'clk'""")!!
        assertEquals(GowinEdaMessage(GowinEdaSeverity.ERROR, "CT1135", "Can't find object named 'clk'", "C:\\p\\src\\blinky.cst", 2), constraint)
        val warning = GowinEdaMessage.parse("""WARN  (EX3791) : Expression size 5 truncated to fit in target size 4("C:\p\src\divider.v":24)""")!!
        assertEquals(GowinEdaSeverity.WARNING, warning.severity)
        assertEquals(24, warning.line)
        val note = GowinEdaMessage.parse("""NOTE  (EX0101) : Current top module is "blinky"""")!!
        assertEquals(GowinEdaMessage(GowinEdaSeverity.NOTE, "EX0101", "Current top module is \"blinky\""), note)
        assertNull(GowinEdaMessage.parse("[95%] Generate netlist file completed"))
        assertNull(GowinEdaMessage.parse("Running placement......"))
    }

    private val pnrReport = """
        //Copyright (C)2014-2026 Gowin Semiconductor Corporation.

        1. PnR Messages

          <Report Title>: PnR Report
          <Part Number>: GW1NR-LV9QN88PC6/I5
          <Device>: GW1NR-9
          <Device Version>: C

        2. PnR Details

         Total Time and Memory Usage: CPU time = 0h 0m 1s, Elapsed time = 0h 0m 1s, Peak memory usage = 248MB


        3. Resource Usage Summary

          --------------------------------------------------------------------------------
          Resources                   | Usage                               | Utilization
          --------------------------------------------------------------------------------
          Logic                       | 45/8640                             |  <1%
            --LUT,ALU,ROM16           | 45(45 LUT, 0 ALU, 0 ROM16)          | -
          Register                    | 30/6693                             |  <1%
            --Logic Register as FF    | 30/6480                             |  <1%
          I/O Port                    | 8/71                                |  12%
          ================================================================================


        4. I/O Bank Usage Summary

          --------------------------------------
          I/O Bank  | Usage       | Utilization
          --------------------------------------
          bank 1   | 1/25        | 4%
          ======================================


        7. Pinout by Port Name

        ------------------------------------------------------------------------------------------------------------------
        Port Name  | Diff Pair | Loc./Bank     | Constraint | Dir.  | Site     | CFG             | IO Type    | Drive | Pull Mode | BankVccio
        ------------------------------------------------------------------------------------------------------------------
        clk        | -         | 52/1          | Y          | in    | IOR17[A] | GCLKT_3         | LVCMOS33   | NA    | UP        | 3.3
        led[0]     | -         | 10/3          | N          | out   | IOL15[A] | GCLKT_6         | LVCMOS18   | 8     | UP        | 1.8
        ==================================================================================================================


        8. All Package Pins

        ----------------------------------------------------------------------
        Loc./Bank| Signal  | Dir.  | Site     | CFG             | IO Type  | Bank Vccio
        ----------------------------------------------------------------------
        3/3      | rst_n   | in    | IOT2[A]  | -               | LVCMOS18 | 1.8
        ----------------------------------------------------------------------
        52/1     | clk     | in    | IOR17[A] | GCLKT_3         | LVCMOS33 | 3.3
        p1-14/3  | -       | in    | IOL2[A]  | -               | LVCMOS18 | -
        ======================================================================
    """.trimIndent()

    @Test
    fun readsThePlaceAndRouteReport() {
        val resources = GowinEdaReports.resources(pnrReport)
        assertEquals(listOf("Logic", "  LUT,ALU,ROM16", "Register", "  Logic Register as FF", "I/O Port"), resources.map { it.name })
        assertEquals(GowinEdaReports.Resource("Logic", "45/8640", "<1%"), resources[0])
        assertNull(resources[1].utilization)
        assertTrue(resources[1].isPart)
        assertEquals("GW1NR-LV9QN88PC6/I5", GowinEdaReports.header(pnrReport, "Part Number"))
        assertEquals("0h 0m 1s", GowinEdaReports.elapsed(pnrReport))

        val ports = GowinEdaReports.placedPorts(pnrReport)
        assertEquals(listOf("clk", "led[0]"), ports.map { it.port })
        assertEquals(GowinEdaReports.PlacedPort("led[0]", "10", "3", false, "out", "LVCMOS18", "UP", "8", "1.8"), ports[1])
        assertNull("NA is no drive", ports[0].drive)
        assertEquals(mapOf("3" to "1.8", "1" to "3.3"), GowinEdaReports.bankVoltages(pnrReport))
    }

    private val timingReport = """
        Note:Timing Report Directory

        1. Timing Message
        2. Timing Summaries
        	2.1 STA Tool Run Summary
        	2.3 Max Frequency Summary
        	2.4 Total Negative Slack Summary
        3. Timing Details
        	3.1 Path Slacks Table
        		3.1.1 Setup Paths Table
        		3.1.2 Hold Paths Table

        Note:Core Timing Report

        2. Timing Summaries
        2.1 STA Tool Run Summary
        <Setup Delay Model>:Slow 1.14V 85C C6/I5
        <Numbers of Setup Violated Endpoints>:0
        <Numbers of Hold Violated Endpoints>:0

        2.2 Clock Summary
          NO.   Clock Name   Type   Period   Frequency   Rise     Fall    Source   Master   Objects
         ===== ============ ====== ======== =========== ======= ======== ======== ======== =========
          1     clk          Base   37.037   27.000MHz   0.000   18.518                     clk

        2.3 Max Frequency Summary
          NO.   Clock Name   Constraint    Actual Fmax    Level   Entity
         ===== ============ ============= ============== ======= ========
          1     clk          27.000(MHz)   109.417(MHz)   5       TOP

        2.4 Total Negative Slack Summary
          Clock Name   Analysis Type   EndPoints TNS   Number of EndPoints
         ============ =============== =============== =====================
          clk          setup           0.000           0
          clk          hold            0.000           0


        3. Timing Details
        3.1 Path Slacks Table
        3.1.1 Setup Paths Table
        <Report Command>:report_timing -setup -max_paths 25 -max_common_paths 1
          Path Number   Path Slack       From Node             To Node         From Clock   To Clock   Relation   Clock Skew   Data Delay
         ============= ============ ==================== ==================== ============ ========== ========== ============ ============
          1             27.898       step/count_8_s0/Q    step/count_16_s0/D   clk:[R]      clk:[R]    37.037     0.000        8.739
          2             28.507       step/count_8_s0/Q    step/count_19_s0/D   clk:[R]      clk:[R]    37.037     0.000        8.130

        3.1.2 Hold Paths Table
        <Report Command>:report_timing -hold -max_paths 25 -max_common_paths 1
          Path Number   Path Slack       From Node             To Node         From Clock   To Clock   Relation   Clock Skew   Data Delay
         ============= ============ ==================== ==================== ============ ========== ========== ============ ============
          1             0.708        step/count_22_s0/Q   step/count_22_s0/D   clk:[R]      clk:[R]    0.000      0.000        0.708

        3.1.3 Recovery Paths Table
        Nothing to report!
    """.trimIndent()

    @Test
    fun readsTheTimingReport() {
        val timing = GowinEdaReports.timing(timingReport)
        assertEquals(listOf(GowinEdaReports.Fmax("clk", 27.0, 109.417)), timing.fmax)
        assertEquals(listOf("Setup" to 27.898, "Hold" to 0.708), timing.worst.map { it.check to it.slack })
        assertEquals("step/count_16_s0/D", timing.worst[0].to)
        assertEquals(listOf("setup", "hold"), timing.negative.map { it.check })
        assertEquals(0, timing.setupViolations)
        assertTrue(timing.isMet)

        val failing = GowinEdaReports.timing(
            timingReport.replace("<Numbers of Setup Violated Endpoints>:0", "<Numbers of Setup Violated Endpoints>:3")
                .replace("1             27.898", "1             -1.250")
                .replace("clk          setup           0.000           0", "clk          setup           -2.500          3"),
        )
        assertFalse(failing.isMet)
        assertEquals(-1.25, failing.worst.first().slack, 0.0)
        assertEquals(GowinEdaReports.NegativeSlack("clk", "setup", -2.5, 3), failing.negative.first())
    }

    @Test
    fun readsTheSynthesisEstimate() {
        val xml = """<Module name="blinky" Register="6" Lut="1" T_Register="30(6)" T_Lut="46(1)">"""
        assertEquals(listOf(GowinEdaReports.Resource("LUTs", "46", null), GowinEdaReports.Resource("Registers", "30", null)), GowinEdaReports.synthesisResources(xml))
    }

    @Test
    fun consoleLeavesOutTheBannerAndTheTclTrace() {
        assertTrue(GowinEdaConsole.isNoise("*** GOWIN Tcl Command Line Console  *** ", ProcessOutputTypes.STDOUT))
        assertTrue(GowinEdaConsole.isNoise("    while executing", ProcessOutputTypes.STDERR))
        assertTrue(GowinEdaConsole.isNoise("\"run all\"", ProcessOutputTypes.STDERR))
        assertTrue(GowinEdaConsole.isNoise("    (file \"C:/p/impl/verilog_toolbox_run.tcl\" line 3)", ProcessOutputTypes.STDERR))
        assertFalse(GowinEdaConsole.isNoise("ERROR (EX3863) : Syntax error", ProcessOutputTypes.STDOUT))
        assertEquals("Routing", GowinEdaConsole.stage("Running routing......"))
        assertEquals("Synthesis", GowinEdaConsole.stage("GowinSynthesis start"))
        assertNull(GowinEdaConsole.stage("[60%] Routing Phase 0 completed"))
        assertEquals("1:35 min", GowinEdaConsole.elapsed(95_000))
    }
}
