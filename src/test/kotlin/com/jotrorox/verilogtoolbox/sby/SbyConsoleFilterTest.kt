package com.jotrorox.verilogtoolbox.sby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SbyConsoleFilterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun describe(line: String): List<String> =
        SbyConsoleFilter.links(line).map { link ->
            val text = line.substring(link.range)
            when (link) {
                is SbyConsoleFilter.Link.Source -> "source $text -> ${link.file}:${link.line}:${link.column}"
                is SbyConsoleFilter.Link.Trace -> "trace $text in ${link.workdir ?: "."}"
            }
        }

    @Test
    fun sourceLocations() {
        assertEquals(
            listOf("source counter.sv:14.46-14.68 -> counter.sv:14:46"),
            describe("SBY 19:11:04 [counter_bmc] summary:   failed assertion counter.x at counter.sv:14.46-14.68 step 7"),
        )
        assertEquals(
            listOf("source broken.sv:11 -> broken.sv:11:1"),
            describe("SBY 19:11:24 [broken_bmc] base: broken.sv:11: ERROR: syntax error, unexpected TOK_LOGIC"),
        )
        assertEquals(
            listOf("source rtl/fifo.v:3.1-5.2 -> rtl/fifo.v:3:1"),
            describe("Assert failed in fifo: rtl/fifo.v:3.1-5.2 (_witness_.check)"),
        )
    }

    @Test
    fun traces() {
        assertEquals(
            listOf("trace counter_bmc\\engine_0\\trace.vcd in ."),
            describe("SBY 19:11:04 [counter_bmc] summary: counterexample trace: counter_bmc\\engine_0\\trace.vcd"),
        )
        assertEquals(
            listOf("trace counter_prove\\engine_0\\trace_induct.vcd in ."),
            describe("counterexample trace [induction]: counter_prove\\engine_0\\trace_induct.vcd"),
        )
        assertEquals(
            listOf("trace engine_0/trace0.vcd in counter_cover"),
            describe("SBY 19:11:03 [counter_cover] engine_0: ##   0:00:04  Writing trace to VCD file: engine_0/trace0.vcd"),
        )
        // A Yosys witness is no waveform.
        assertEquals(emptyList<String>(), describe("SBY 19:11:03 [c] engine_0: ##   0:00:04  Writing trace to Yosys witness file: engine_0/trace0.yw"))
    }

    @Test
    fun sourcesAreTheFilesCopiedFrom() {
        val root = folder.root.toPath()
        val original = Files.createDirectories(root.resolve("rtl")).resolve("counter.sv").also { Files.writeString(it, "") }
        val inline = Files.createDirectories(root.resolve("counter_bmc/src")).resolve("defines.sv").also { Files.writeString(it, "") }
        val files = listOf(SbyConfig.FileEntry("counter.sv", "rtl/counter.sv"))
        assertEquals(original, SbyConsoleFilter.resolveSource("counter.sv", root, files) { emptyList() })
        assertEquals(inline, SbyConsoleFilter.resolveSource("defines.sv", root, files) { listOf("counter_bmc") })
        assertNull(SbyConsoleFilter.resolveSource("missing.sv", root, files) { listOf("counter_bmc") })
    }
}
