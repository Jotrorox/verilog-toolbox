package com.jotrorox.verilogtoolbox

import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class WaveformsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun dumpFile(line: String): String? = Waveforms.dumpFileRange(line)?.let { line.substring(it) }

    @Test
    fun dumpFileIsFoundForEveryFormat() {
        assertEquals("tb.vcd", dumpFile("VCD info: dumpfile tb.vcd opened for output.\n"))
        assertEquals("out/my wave.fst", dumpFile("FST info: dumpfile out/my wave.fst opened for output."))
        assertEquals("tb.lxt", dumpFile("LXT2 info: dumpfile tb.lxt opened for output.\r\n"))
    }

    @Test
    fun otherOutputIsNotADumpFile() {
        assertNull(dumpFile("VCD warning: \$dumpvars: Unsupported argument type"))
        assertNull(dumpFile("  \$display: VCD info: dumpfile tb.vcd opened for output."))
    }

    @Test
    fun listenerFollowsOutputSplitAcrossChunks() {
        val root = folder.root.toPath()
        val listener = WaveformDumpListener(root)
        val handler = NopProcessHandler()
        for (chunk in listOf("compiling\nVCD info: dump", "file waves/tb.vcd opened for output.\ntb.v:3: \$finish")) {
            listener.onTextAvailable(ProcessEvent(handler, chunk), ProcessOutputTypes.STDOUT)
        }
        assertEquals(root.resolve("waves/tb.vcd"), listener.dumpFile)
    }

    @Test
    fun layoutIsNamedAfterTheTestbenchModule() {
        val testbench = Path.of("project", "sim", "tests.sv")
        assertEquals(Path.of("project", "sim", "counter_tb.gtkw"), WaveformViewer.GTKWAVE.layoutFile(testbench, "counter_tb"))
        assertEquals(Path.of("project", "sim", "counter_tb.surf.ron"), WaveformViewer.SURFER.layoutFile(testbench, "counter_tb"))
        assertEquals(Path.of("project", "sim", "tests.gtkw"), WaveformViewer.GTKWAVE.layoutFile(testbench, null))
    }

    @Test
    fun gtkwaveGetsItsLayoutEvenBeforeItIsSaved() {
        val dump = folder.root.toPath().resolve("tb.vcd")
        val layout = folder.root.toPath().resolve("tb.gtkw")
        assertEquals(listOf(dump.toString(), layout.toString()), WaveformViewer.GTKWAVE.arguments(dump, layout))
    }

    @Test
    fun surferGetsItsStateOnlyOnceItIsSaved() {
        val dump = folder.root.toPath().resolve("tb.vcd")
        val layout = folder.root.toPath().resolve("tb.surf.ron")
        assertEquals(listOf(dump.toString()), WaveformViewer.SURFER.arguments(dump, layout))
        Files.writeString(layout, "()")
        assertEquals(
            listOf(dump.toString(), "--state-file", layout.toString()), WaveformViewer.SURFER.arguments(dump, layout),
        )
    }

    @Test
    fun viewerIsStartedWithItsArguments() {
        val commandLine = Waveforms.commandLine(Path.of("/usr/bin/gtkwave"), listOf("tb.vcd", "tb.gtkw"))
        assertEquals(Path.of("/usr/bin/gtkwave").toString(), commandLine.exePath)
        assertEquals(listOf("tb.vcd", "tb.gtkw"), commandLine.parametersList.list)
    }
}
