package com.jotrorox.verilogtoolbox

import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

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
    fun surferGetsItsStateOnceThereIsOne() {
        val dump = folder.root.toPath().resolve("tb.vcd")
        val layout = folder.root.toPath().resolve("tb.surf.ron")
        assertEquals(listOf(dump.toString()), WaveformViewer.SURFER.arguments(dump, layout))
        Files.writeString(layout, "()")
        assertEquals(
            listOf(dump.toString(), "--state-file", layout.toString()), WaveformViewer.SURFER.arguments(dump, layout),
        )
    }

    @Test
    fun firstSurferLayoutShowsTheTestbenchAndIsSaved() {
        val layout = folder.root.toPath().resolve("my tb.surf.ron")
        assertEquals("scope_add counter_tb\nsave_state_as $layout\n", Waveforms.surferSeed(layout, "counter_tb"))
        // An escaped identifier is not a word of a command.
        assertEquals("save_state_as $layout\n", Waveforms.surferSeed(layout, "\\bus[0] "))
        assertEquals("save_state_as $layout\n", Waveforms.surferSeed(layout, null))
    }

    @Test
    fun surferLayoutIsNotSeededWhereACommandWouldEnd() {
        assertNull(Waveforms.surferSeed(folder.root.toPath().resolve("a;b").resolve("tb.surf.ron"), "tb"))
        assertNull(Waveforms.surferSeed(folder.root.toPath().resolve("issue #4").resolve("tb.surf.ron"), "tb"))
    }

    /** Reads one message of the waveform control protocol, which a zero byte ends. */
    private fun message(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte <= 0) return bytes.toString(Charsets.UTF_8)
            bytes.write(byte)
        }
    }

    @Test
    fun surferIsGreetedAndToldToReload() {
        SurferControl().use { control ->
            assertEquals(listOf("--wcp-initiate", control.port.toString()), control.arguments)
            Socket(InetAddress.getLoopbackAddress(), control.port).use { surfer ->
                surfer.soTimeout = 10_000
                assertEquals("""{"type":"greeting","version":"0","commands":[]}""", message(surfer.getInputStream()))
                assertTrue(control.reload(raise = false))
                assertEquals("""{"type":"command","command":"reload"}""", message(surfer.getInputStream()))
            }
        }
    }

    @Test
    fun surferThatDoesNotConnectIsNotReloaded() {
        SurferControl().use { assertFalse(it.reload(raise = true)) }
    }

    @Test
    fun gtkwaveReloadsWhenItsScriptSeesTheRequest() {
        val directory = folder.newFolder("control").toPath()
        val reload = directory.resolve("reload")
        val raise = directory.resolve("raise")
        GtkwaveControl(directory).use { control ->
            val script = directory.resolve("reload.tcl")
            assertEquals(listOf("--repscript", script.toString(), "--repperiod", "250"), control.arguments)
            assertEquals(
                "# Written by Verilog Toolbox: reloads the dump when the IDE asks for it.\n" +
                    "if {[file exists \"$raise\"]} { file delete \"$raise\"; gtkwave::presentWindow }\n" +
                    "if {[file exists \"$reload\"]} { file delete \"$reload\"; gtkwave::reLoadFile }\n",
                Files.readString(script),
            )
            // What the script does in GTKWave.
            val gtkwave = thread {
                while (!Files.exists(reload)) Thread.sleep(10)
                assertTrue(Files.exists(raise))
                Files.delete(raise)
                Files.delete(reload)
            }
            assertTrue(control.reload(raise = true))
            gtkwave.join()
        }
        assertFalse(Files.exists(directory))
    }

    @Test
    fun gtkwaveThatIgnoresTheScriptIsNotReloaded() {
        GtkwaveControl(folder.newFolder("control").toPath()).use { assertFalse(it.reload(raise = false)) }
    }

    @Test
    fun pathsAreQuotedForTcl() {
        assertEquals("\"/tmp/my waves/reload\"", GtkwaveControl.tclString(Path.of("/tmp/my waves/reload")))
        assertEquals("\"/tmp/\\\$a\\[1\\]\\{x\\}/r\"", GtkwaveControl.tclString(Path.of("/tmp/\$a[1]{x}/r")))
    }

    @Test
    fun applicationBundleIsStartedThroughOpen() {
        val bundle = folder.newFolder("gtkwave.app").toPath()
        val commandLine = Waveforms.commandLine(bundle, listOf("tb.vcd"))
        assertEquals("open", commandLine.exePath)
        // -W: open then runs for as long as the viewer does.
        assertEquals(listOf("-n", "-W", "-a", bundle.toString(), "--args", "tb.vcd"), commandLine.parametersList.list)
    }

    /**
     * Starts the real Surfer, which opens a window, so only when VERILOG_TOOLBOX_SURFER names its
     * executable: Surfer has to connect, take the reload and write the seeded layout.
     */
    @Test
    fun realSurferReloadsAndSavesItsFirstLayout() {
        val surfer = System.getenv("VERILOG_TOOLBOX_SURFER")
        assumeTrue(surfer != null)
        val root = folder.root.toPath()
        val dump = root.resolve("tb.vcd")
        Files.writeString(
            dump,
            "\$timescale 1ns \$end\n\$scope module tb \$end\n\$var reg 1 ! clk \$end\n\$upscope \$end\n" +
                "\$enddefinitions \$end\n#0\n0!\n#5\n1!\n#10\n0!\n",
        )
        val layout = root.resolve("tb.surf.ron")
        val seed = Files.writeString(root.resolve("seed.cmd"), Waveforms.surferSeed(layout, "tb")!!)
        val log = root.resolve("surfer.log").toFile()
        SurferControl().use { control ->
            val process = ProcessBuilder(
                listOf(surfer, "--wcp-initiate", control.port.toString()) + WaveformViewer.SURFER.arguments(dump, layout) +
                    listOf("--command-file", seed.toString())
            ).apply { environment().putAll(control.environment) }.redirectErrorStream(true).redirectOutput(log).start()
            try {
                // True once Surfer has connected; it handles the command when it draws its next frame.
                assertTrue(control.reload(raise = false))
                val deadline = System.nanoTime() + 20_000_000_000
                fun loads() = log.readText().split("Loading a waveform file").size - 1
                while ((loads() < 2 || !Files.isRegularFile(layout)) && System.nanoTime() < deadline) Thread.sleep(200)
                assertEquals(log.readText(), 2, loads())
                assertTrue(Files.readString(layout).contains("clk"))
            } finally {
                process.destroy()
                process.waitFor()
            }
        }
    }

    @Test
    fun viewerIsStartedWithItsArguments() {
        val commandLine = Waveforms.commandLine(Path.of("/usr/bin/gtkwave"), listOf("tb.vcd", "tb.gtkw"))
        assertEquals(Path.of("/usr/bin/gtkwave").toString(), commandLine.exePath)
        assertEquals(listOf("tb.vcd", "tb.gtkw"), commandLine.parametersList.list)
    }
}
