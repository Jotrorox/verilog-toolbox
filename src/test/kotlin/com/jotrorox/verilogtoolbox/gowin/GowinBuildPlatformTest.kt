package com.jotrorox.verilogtoolbox.gowin

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.openfpgaloader.FpgaDestination
import com.jotrorox.verilogtoolbox.openfpgaloader.OpenFpgaLoaderConfigurationType
import com.jotrorox.verilogtoolbox.openfpgaloader.OpenFpgaLoaderProject
import com.jotrorox.verilogtoolbox.openfpgaloader.OpenFpgaLoaderRunConfiguration
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds a design with the real tools, for every board preset, so that the presets are known to be what
 * Yosys, nextpnr and gowin_pack accept. Only runs where the tools are: Yosys of the OSS CAD Suite in the
 * settings, on PATH, or given as `VERILOG_TOOLBOX_YOSYS`.
 */
class GowinBuildPlatformTest : BasePlatformTestCase() {
    private var previousYosys = ""

    override fun setUp() {
        super.setUp()
        previousYosys = ToolchainSettings.getInstance().yosysPath
        System.getenv("VERILOG_TOOLBOX_YOSYS")?.let { ToolchainSettings.getInstance().yosysPath = it }
    }

    override fun tearDown() {
        try {
            ToolchainSettings.getInstance().yosysPath = previousYosys
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Whether the tools are here; asked for with `VERILOG_TOOLBOX_YOSYS`, they must be. */
    private fun toolsInstalled(): Boolean {
        val (tools, missing) = GowinBuild.tools()
        if (tools == null && System.getenv("VERILOG_TOOLBOX_YOSYS") != null) fail("Not found: $missing; yosysPath='${ToolchainSettings.getInstance().yosysPath}', executable=${Files.isExecutable(Path.of(System.getenv("VERILOG_TOOLBOX_YOSYS")))}")
        return tools != null
    }

    /** A counter on the board's clock, its top bit on an LED pin of the board. */
    private fun blinky(directory: Path, board: GowinBoard): GowinBuildSpec {
        val source = Files.writeString(
            Files.createDirectories(directory.resolve("src")).resolve("top.v"),
            """
            module top(input clk, output led);
                reg [23:0] count = 0;
                always @(posedge clk) count <= count + 1;
                assign led = count[23];
            endmodule
            """.trimIndent(),
        )
        val (clock, led) = PINS.getValue(board.id)
        val constraints = Files.writeString(
            directory.resolve("${board.id}.cst"),
            "IO_LOC \"clk\" $clock;\nIO_PORT \"clk\" IO_TYPE=LVCMOS33;\nIO_LOC \"led\" $led;\nIO_PORT \"led\" IO_TYPE=LVCMOS33;\n",
        )
        return GowinBuildSpec(
            board, "top", constraints, GowinBuild.design(directory, null), directory.resolve("build/gowin"),
            directory.resolve("build/gowin/top.fs"),
        ).also { assertEquals(listOf(source), it.design.sources) }
    }

    /** Runs the plan's steps and returns their output; fails on the first that fails. */
    private fun build(spec: GowinBuildSpec): List<String> {
        val said = StringBuilder()
        val steps = GowinBuild.plan(spec).steps { text, _ -> said.append(text) }
        for (step in steps) {
            val output = CapturingProcessHandler(step.commandLine).runProcess(BUILD_TIMEOUT_MS)
            assertEquals("${step.description} for ${spec.board.id}:\n${output.stdout}\n${output.stderr}", 0, output.exitCode)
            step.afterwards?.invoke()?.let { said.append(it).append('\n') }
        }
        return listOf(said.toString()) + steps.map { it.description }
    }

    fun testEveryBoardPresetBuilds() {
        if (!toolsInstalled()) return
        for (board in GowinBoards.ALL) {
            val directory = Files.createTempDirectory("gowin ${board.id}")
            val spec = blinky(directory, board)
            val steps = build(spec)
            assertTrue("${board.id}: ${steps.first()}", Files.size(spec.bitstream) > 0)
            assertTrue(steps.first(), steps.first().contains("Max frequency for clock "))
            // Nothing changed: the next run builds nothing.
            assertEquals(board.id, 1, build(spec).size)
        }
    }

    fun testSecondBuildIsSkippedUntilASourceChanges() {
        if (!toolsInstalled()) return
        val directory = Files.createTempDirectory("gowin incremental")
        val spec = blinky(directory, GowinBoards.byId("tangnano9k")!!)
        assertEquals(4, build(spec).size)
        val again = build(spec)
        assertEquals(again.toString(), 1, again.size)
        assertTrue(again.first(), again.first().contains("is up to date"))
        val source = spec.design.sources.single()
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        assertEquals(4, build(spec).size)
    }

    /**
     * Copies a real project without its Makefile, sets up Load Bitstream into FPGA as the action does and
     * builds the bitstream. Only runs when asked to: `VERILOG_TOOLBOX_GOWIN_PROJECT=/path/to/project`.
     */
    fun testRealProjectBuildsWithoutMakefile() {
        val original = System.getenv("VERILOG_TOOLBOX_GOWIN_PROJECT")?.let { Path.of(it) } ?: return
        if (!toolsInstalled()) return
        val directory = Files.createTempDirectory("gowin project")
        Files.walk(original).use { paths ->
            paths.filter { path ->
                val relative = original.relativize(path)
                relative.none { it.toString().startsWith(".") || it.toString() == "build" } && relative.toString() != "Makefile"
            }.forEach { path ->
                val target = directory.resolve(original.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
        val programming = OpenFpgaLoaderProject.detect(directory, FpgaDestination.SRAM)
        println("Detected: $programming")
        val gowin = programming.gowin ?: return fail("No built-in build was detected: $programming")
        assertNotNull("board of ${gowin.constraints}", gowin.board)
        val configuration = OpenFpgaLoaderConfigurationType.getInstance().createTemplateConfiguration(project) as OpenFpgaLoaderRunConfiguration
        configuration.apply(programming)
        val plan = configuration.buildPlan(directory, emptyList())!!
        val said = StringBuilder()
        for (step in plan.steps { text, _ -> said.append(text) }) {
            val output = CapturingProcessHandler(step.commandLine).runProcess(BUILD_TIMEOUT_MS)
            println("${step.description}\n${output.stdout}${output.stderr}")
            assertEquals(step.description, 0, output.exitCode)
            step.afterwards?.invoke()?.let { said.append(it).append('\n') }
        }
        println(said)
        assertTrue(Files.size(programming.bitstream!!) > 0)
        assertEquals(programming.target.board, configuration.target.board)
    }

    private companion object {
        const val BUILD_TIMEOUT_MS = 300_000

        /** The clock and an LED pin of each board, from apicula's example constraint files. */
        val PINS = mapOf(
            "tangnano" to ("35" to "16"),
            "tangnano1k" to ("47" to "10"),
            "tangnano4k" to ("45" to "10"),
            "tangnano9k" to ("52" to "10"),
            "tangnano20k" to ("4" to "15"),
            "tangprimer20k" to ("H11" to "C13"),
            "tangprimer25k" to ("E2" to "G5"),
            "tangmega138k" to ("P16" to "J14"),
        )
    }
}
