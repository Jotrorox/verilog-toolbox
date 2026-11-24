package com.jotrorox.verilogtoolbox.openfpgaloader

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.quartus.QuartusFilteringComboBox
import com.jotrorox.verilogtoolbox.quartus.QuartusOutput
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class OpenFpgaLoaderPlatformTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private var savedPath = ""

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("openfpgaloader test").toRealPath()
        savedPath = ToolchainSettings.getInstance().openFpgaLoaderPath
    }

    override fun tearDown() {
        try {
            ToolchainSettings.getInstance().openFpgaLoaderPath = savedPath
            NioFiles.deleteRecursively(directory)
            // CLion switches this on at startup, which the test framework reports as a setting the test damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A shell script standing in for a program: it prints its arguments and then runs [body]. */
    private fun script(name: String, body: String, help: String = "      --force-terminal-mode     force progress bar output"): Path {
        val file = directory.resolve(name)
        Files.writeString(
            file,
            "#!/bin/sh\n" +
                "case \"\$1\" in\n  --help) echo '$help'; exit 0;;\n  --Version) echo 'openFPGALoader v1.2.0'; exit 0;;\nesac\n" +
                "echo \"args: \$*\"\n$body\n",
        )
        file.toFile().setExecutable(true)
        return file
    }

    private class Run(val text: String, val exitCode: Int, val stderr: String, val warnings: String)

    private fun program(
        tool: Path, bitstream: Path, target: FpgaTarget = FpgaTarget(board = "tangprimer25k"), build: String = "",
    ): Run {
        val handler = OpenFpgaLoaderProcessHandler(tool, directory, bitstream, target, build, listOf(directory))
        val text = StringBuilder()
        val stderr = StringBuilder()
        val warnings = StringBuilder()
        var exitCode = -1
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                synchronized(text) {
                    text.append(event.text)
                    if (outputType == ProcessOutputTypes.STDERR) stderr.append(event.text)
                    if (outputType == QuartusOutput.WARNING) warnings.append(event.text)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCode = event.exitCode
            }
        })
        handler.startNotify()
        assertTrue("programming did not finish", handler.waitFor(60_000))
        return synchronized(text) { Run(text.toString(), exitCode, stderr.toString(), warnings.toString()) }
    }

    private fun bitstream(name: String = "top.fs"): Path =
        Files.writeString(Files.createDirectories(directory.resolve("build")).resolve(name), "bitstream")

    fun testBitstreamIsLoadedWithAProgressBar() {
        if (SystemInfo.isWindows) return
        val tool = script(
            "openFPGALoader",
            "echo 'No cable or board specified: using direct ft2232 interface'\n" +
                "printf 'Load SRAM \\rLoad SRAM: [=====     ] 50.00%%\\rLoad SRAM: [==========] 100.00%%\\nDone\\n'",
        )
        val file = bitstream()
        val run = program(tool, file)
        assertEquals(run.text, 0, run.exitCode)
        assertTrue(run.text, "args: --board tangprimer25k --force-terminal-mode $file" in run.text)
        assertTrue(run.text, "Loading top.fs into the FPGA on board tangprimer25k with openFPGALoader 1.2.0" in run.text)
        // The states of the progress bar end with a carriage return, so that the console overwrites them.
        assertTrue(run.text, "50.00%\rLoad SRAM: [==========] 100.00%\n" in run.text)
        assertTrue(run.warnings, "No cable or board specified" in run.warnings)
        assertTrue(run.text, Regex("""top\.fs is loaded into the FPGA on board tangprimer25k in \d+ s\.""").containsMatchIn(run.text))
    }

    fun testFlashIsWrittenAndVerified() {
        if (SystemInfo.isWindows) return
        val tool = script("openFPGALoader", "echo Done")
        val file = bitstream()
        val run = program(tool, file, FpgaTarget(board = "tangprimer25k", destination = FpgaDestination.FLASH, verify = true))
        assertEquals(run.text, 0, run.exitCode)
        assertTrue(run.text, "args: --board tangprimer25k --write-flash --verify --force-terminal-mode $file" in run.text)
        assertTrue(run.text, "Writing top.fs to the flash on board tangprimer25k" in run.text)
        assertTrue(run.text, "The FPGA loads it at every power-up." in run.text)
    }

    fun testOlderOpenFpgaLoaderGetsNoTerminalMode() {
        if (SystemInfo.isWindows) return
        val tool = script("openFPGALoader", "echo Done", help = "  -f, --write-flash   write bitstream in flash")
        val file = bitstream()
        val run = program(tool, file, FpgaTarget())
        assertTrue(run.text, "args: $file\n" in run.text)
    }

    fun testMissingCableIsExplained() {
        if (SystemInfo.isWindows) return
        val tool = script(
            "openFPGALoader",
            "echo 'unable to open ftdi device: -3 (device not found)'\necho 'JTAG init failed with: unable to open ftdi device' >&2\nexit 1",
        )
        val run = program(tool, bitstream(), FpgaTarget())
        assertEquals(1, run.exitCode)
        // openFPGALoader prints this error on standard output; it is shown as one all the same.
        assertTrue(run.stderr, "unable to open ftdi device: -3" in run.stderr)
        // Which of the two errors comes first depends on how the streams interleave.
        assertTrue(run.text, Regex("""Programming top\.fs failed: .*unable to open ftdi device""").containsMatchIn(run.text))
        assertTrue(run.text, "No programming cable was found." in run.text)
    }

    fun testBitstreamIsBuiltFirst() {
        if (SystemInfo.isWindows) return
        val tool = script("openFPGALoader", "echo Done")
        val make = script("make.sh", "mkdir -p build && echo built > \"\$1\"")
        val file = directory.resolve("build/top.fs")
        val run = program(tool, file, build = "./${make.fileName} build/top.fs")
        assertEquals(run.text, 0, run.exitCode)
        assertTrue(run.text, "Building: ./make.sh build/top.fs" in run.text)
        assertTrue(run.text, "args: --board tangprimer25k --force-terminal-mode $file" in run.text)
        assertEquals("built\n", Files.readString(file))
        assertTrue(run.text, "top.fs is loaded into the FPGA" in run.text)
    }

    fun testFailedBuildProgramsNothing() {
        if (SystemInfo.isWindows) return
        val tool = script("openFPGALoader", "echo SHOULD-NOT-RUN")
        val make = script("make.sh", "echo 'src/top.v:3: ERROR: syntax error' >&2; exit 2")
        val run = program(tool, bitstream(), build = "./${make.fileName}")
        assertEquals(2, run.exitCode)
        assertTrue(run.text, "The build failed (exit code 2), so the FPGA was not programmed." in run.text)
        assertFalse(run.text, "SHOULD-NOT-RUN" in run.text)
    }

    fun testBuildProgramIsLookedUpOnPath() {
        if (SystemInfo.isWindows) return
        val tool = script("openFPGALoader", "echo Done")
        val run = program(tool, bitstream(), build = "no-such-build-tool-here all")
        assertEquals(1, run.exitCode)
        assertTrue(run.text, "no-such-build-tool-here was not found, so the bitstream cannot be built." in run.text)
    }

    fun testMissingBitstreamAndStaleBitstreamAreReported() {
        if (SystemInfo.isWindows) return
        val tool = script("openFPGALoader", "echo Done")
        val missing = program(tool, directory.resolve("build/none.fs"))
        assertEquals(1, missing.exitCode)
        assertTrue(missing.text, "does not exist. Build it first" in missing.text)

        val file = bitstream()
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000))
        Files.writeString(Files.createDirectories(directory.resolve("src")).resolve("top.v"), "module top; endmodule\n")
        val stale = program(tool, file)
        assertEquals(stale.text, 0, stale.exitCode)
        assertTrue(stale.warnings, "src/top.v was changed after top.fs was built" in stale.warnings)
    }

    private fun configuration(): OpenFpgaLoaderRunConfiguration =
        RunManager.getInstance(project).createConfiguration("Load", OpenFpgaLoaderConfigurationType.getInstance())
            .configuration as OpenFpgaLoaderRunConfiguration

    private fun problem(configuration: OpenFpgaLoaderRunConfiguration): RuntimeConfigurationException? =
        try {
            configuration.checkConfiguration()
            null
        } catch (e: RuntimeConfigurationException) {
            e
        }

    fun testConfigurationIsChecked() {
        if (SystemInfo.isWindows) return
        val configuration = configuration()
        assertTrue(problem(configuration) is RuntimeConfigurationError)

        ToolchainSettings.getInstance().openFpgaLoaderPath = script("openFPGALoader", "echo Done").toString()
        val json = Files.writeString(directory.resolve("top-pnr.json"), "{}")
        configuration.settings.bitstream = json.toString()
        val notBitstream = problem(configuration)
        assertTrue(notBitstream is RuntimeConfigurationWarning)
        assertTrue(notBitstream!!.localizedMessage, "does not look like a bitstream" in notBitstream.localizedMessage)

        configuration.settings.bitstream = directory.resolve("build/top.fs").toString()
        assertTrue(problem(configuration)!!.localizedMessage.contains("does not exist yet"))
        configuration.settings.buildCommand = "make build/top.fs"
        assertNull(problem(configuration))
        assertEquals("Load top.fs", configuration.suggestedName())
        configuration.destination = FpgaDestination.FLASH
        assertEquals("Flash top.fs", configuration.suggestedName())
    }

    fun testRelativeBitstreamStartsAtTheProject() {
        val configuration = configuration()
        configuration.settings.bitstream = "build/top.fs"
        assertEquals(Path.of(project.basePath!!).resolve("build/top.fs").normalize(), configuration.bitstreamPath())
        configuration.apply(
            FpgaProgramming(
                Path.of(project.basePath!!).resolve("out/blinky.bit"),
                FpgaTarget(board = "arty_a7_35t", destination = FpgaDestination.FLASH, verify = true, options = "--freq 1000000"),
                "make out/blinky.bit", null,
            )
        )
        assertEquals("out/blinky.bit", configuration.settings.bitstream)
        assertEquals(
            FpgaTarget(board = "arty_a7_35t", destination = FpgaDestination.FLASH, verify = true, options = "--freq 1000000"),
            configuration.target,
        )
        assertEquals("make out/blinky.bit", configuration.settings.buildCommand)
    }

    /** The run configuration's form keeps to the width of its dialog, however long the board names are. */
    fun testEditorIsNotWidenedByLongBoardNames() {
        val editor = configuration().configurationEditor
        try {
            val component = editor.component
            fun lists(c: java.awt.Component): List<QuartusFilteringComboBox> =
                (if (c is QuartusFilteringComboBox) listOf(c) else emptyList()) +
                    ((c as? java.awt.Container)?.components?.flatMap { lists(it) } ?: emptyList())
            val boards = lists(component)
            assertEquals(2, boards.size)
            val before = component.preferredSize.width
            val listWidths = boards.map { it.preferredSize.width }
            // As the lists arrive in the dialog, which lays itself out again. A board's details make its row as long as this.
            boards.forEach {
                it.items = (1..170).map { index -> "alinx_axku042_$index   digilent_hs2, xcku040-ffva1156-and-some-more-text" }
                it.invalidate()
            }
            assertEquals(listWidths, boards.map { it.preferredSize.width })
            assertEquals(before, component.preferredSize.width)
            // The dialog that edits one configuration may have about 630 pixels for it; then the comments wrap.
            assertTrue("${component.minimumSize.width}", component.minimumSize.width < 560)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    /** openFPGALoader's own lists, when it is installed; no hardware needed. */
    fun testInstalledOpenFpgaLoaderListsBoardsAndCables() {
        val tool = System.getenv("VERILOG_TOOLBOX_OPENFPGALOADER")?.let { Path.of(it) }
            ?: ToolchainSettings.getInstance().findOpenFpgaLoader() ?: return
        val info = OpenFpgaLoader.info(tool)
        assertNotNull(info.version)
        assertTrue("--write-flash" in info.options)
        val boards = OpenFpgaLoader.boards(tool)
        assertTrue(boards.size > 50)
        assertTrue(boards.any { it.name == "arty_a7_35t" && it.cable == "digilent" && it.fpgaPart == "xc7a35tcsg324" })
        assertEquals(boards, OpenFpgaLoader.knownBoards(tool))
        assertTrue(OpenFpgaLoader.cables(tool).any { it.name == "ft2232" })
    }

    /**
     * Sets up programming for a real project as Load Bitstream into FPGA does, builds the bitstream with the
     * project's build command and loads it into the SRAM of a real board, where it lasts until the board is
     * switched off. Only runs when asked to: `VERILOG_TOOLBOX_FPGA_PROJECT=/path/to/project`, with
     * openFPGALoader in the settings or given as `VERILOG_TOOLBOX_OPENFPGALOADER`.
     */
    fun testRealBoardIsLoaded() {
        val projectDirectory = System.getenv("VERILOG_TOOLBOX_FPGA_PROJECT")?.let { Path.of(it) } ?: return
        val tool = System.getenv("VERILOG_TOOLBOX_OPENFPGALOADER")?.let { Path.of(it) }
            ?: ToolchainSettings.getInstance().findOpenFpgaLoader() ?: return fail("openFPGALoader was not found.")
        val programming = OpenFpgaLoaderProject.detect(projectDirectory, FpgaDestination.SRAM)
        println("Detected: $programming")
        val bitstream = programming.bitstream ?: return fail("No bitstream was found in $projectDirectory.")
        val detection = OpenFpgaLoader.detect(tool, programming.target)
        println(OpenFpgaLoader.describe(detection, programming.target))
        assertTrue(OpenFpgaLoader.describe(detection, programming.target), detection.devices.isNotEmpty())

        val handler = OpenFpgaLoaderProcessHandler(
            tool, projectDirectory, bitstream, programming.target, programming.buildCommand, listOf(tool.parent),
        )
        val text = StringBuilder()
        var exitCode = -1
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                synchronized(text) { text.append(event.text) }
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCode = event.exitCode
            }
        })
        handler.startNotify()
        assertTrue("programming did not finish", handler.waitFor(300_000))
        val output = synchronized(text) { text.toString() }
        println(output)
        assertEquals(output, 0, exitCode)
        assertTrue(output, "is loaded into the FPGA" in output)
    }
}
