package com.jotrorox.verilogtoolbox.gowin

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.jotrorox.verilogtoolbox.common.BuildPlan
import com.jotrorox.verilogtoolbox.common.BuildStep
import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.OssCadSuite
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.findInstalledExecutable
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What one built-in build makes: the bitstream of [top] for [board]. All paths are absolute. */
data class GowinBuildSpec(
    val board: GowinBoard,
    val top: String,
    val constraints: Path,
    val design: FileList,
    /** Where the intermediate files and logs go. */
    val directory: Path,
    val bitstream: Path,
) {
    val synthesized: Path get() = directory.resolve("$top-synth.json")
    val placed: Path get() = directory.resolve("$top-pnr.json")
}

/**
 * The open source Gowin flow that apicula's examples use, without a Makefile: Yosys's `synth_gowin`, then
 * nextpnr-himbaechel places and routes with the constraint file, then gowin_pack writes the bitstream.
 * A build whose inputs and settings are unchanged since the last one is skipped.
 */
object GowinBuild {
    const val SCRIPT_FILE = "synth.ys"
    const val COMMAND_FILE = "design.f"
    const val YOSYS_LOG = "yosys.log"
    const val NEXTPNR_LOG = "nextpnr.log"

    /** What the last successful build was made with; a different one means building again. */
    const val STAMP_FILE = "build.stamp"

    const val NEXTPNR = "nextpnr-himbaechel"
    const val PACK = "gowin_pack"

    data class Tools(val yosys: Path, val nextpnr: Path, val pack: Path, val slang: Boolean)

    /** The tools of the flow, or the names of those that were not found. */
    fun tools(): Pair<Tools?, List<String>> {
        val yosys = ToolchainSettings.getInstance().findYosys()
        val nextpnr = suiteTool(NEXTPNR, yosys)
        val pack = suiteTool(PACK, yosys)
        val missing = listOfNotNull("yosys".takeIf { yosys == null }, NEXTPNR.takeIf { nextpnr == null }, PACK.takeIf { pack == null })
        if (yosys == null || nextpnr == null || pack == null) return null to missing
        return Tools(yosys, nextpnr, pack, YosysSynthesis.hasSlangPlugin(yosys)) to emptyList()
    }

    /** [name] next to Yosys, as the OSS CAD Suite has them, else from the suite or the usual places. */
    private fun suiteTool(name: String, yosys: Path?): Path? {
        val binary = if (SystemInfo.isWindows) "$name.exe" else name
        return yosys?.resolveSibling(binary)?.takeIf { Files.isExecutable(it) }
            ?: OssCadSuite.tool(name)
            ?: findInstalledExecutable(binary)
    }

    /** The design of [base]: the sources of [fileList] when one is given, else the project's sources. */
    fun design(base: Path, fileList: Path?): FileList {
        if (fileList != null) return FileList.parse(fileList, FileList.baseDirectory(fileList, base))
        val includes = GowinProject.includeDirectories(base)
        return FileList(
            sources = GowinProject.sources(base),
            includeDirectories = includes,
            defines = emptyList(),
            libraryDirectories = emptyList(),
            libraryFiles = emptyList(),
            libraryExtensions = VerilogDesign.LIBRARY_EXTENSIONS,
            topModules = emptyList(),
            ignored = emptyList(),
            errors = emptyList(),
        )
    }

    /** The Yosys script that synthesizes the design for the board's family, and yosys-slang's command file. */
    fun yosysScript(spec: GowinBuildSpec, slang: Boolean): YosysSynthesis.Run {
        val read = YosysSynthesis.read(spec.design, spec.top, slang)
        val synth = buildList {
            add("synth_gowin")
            add("-top"); add(spec.top)
            spec.board.yosysFamily?.let { add("-family"); add(it) }
            addAll(spec.board.yosysOptions)
            add("-json"); add(quote(spec.synthesized.toString()))
        }.joinToString(" ")
        return YosysSynthesis.Run(read.script + synth + "\n", read.commandFile)
    }

    fun yosysArguments(slang: Boolean): List<String> =
        listOf("-q", "-l", YOSYS_LOG) + (if (slang) listOf("-m", "slang") else emptyList()) + listOf("-s", SCRIPT_FILE)

    fun nextpnrArguments(spec: GowinBuildSpec): List<String> = buildList {
        add("--json"); add(spec.synthesized.toString())
        add("--write"); add(spec.placed.toString())
        add("--device"); add(spec.board.device)
        spec.board.nextpnrFamily?.let { add("--vopt"); add("family=$it") }
        add("--vopt"); add("cst=${spec.constraints}")
        for (option in spec.board.nextpnrOptions) { add("--vopt"); add(option) }
        add("--top"); add(spec.top)
        add("--freq"); add(spec.board.clockMhz.toString())
        // Only warnings and errors in the console; the whole report goes to the log.
        add("--quiet")
        add("-l"); add(NEXTPNR_LOG)
    }

    fun packArguments(spec: GowinBuildSpec): List<String> =
        spec.board.packOptions + listOf("-d", spec.board.packDevice, "-o", spec.bitstream.toString(), spec.placed.toString())

    /** Everything a build depends on besides the files' contents: settings, commands and the list of inputs. */
    fun stamp(spec: GowinBuildSpec, slang: Boolean): String = buildString {
        val script = yosysScript(spec, slang)
        append("yosys ").append(yosysArguments(slang).joinToString(" ")).append('\n').append(script.script)
        script.commandFile?.let { append(it) }
        append("nextpnr ").append(nextpnrArguments(spec).joinToString(" ")).append('\n')
        append("pack ").append(packArguments(spec).joinToString(" ")).append('\n')
        for (input in inputs(spec)) append("input ").append(input).append('\n')
    }

    /** The files the bitstream is made from: sources, headers in the include directories and the constraints. */
    fun inputs(spec: GowinBuildSpec): List<Path> {
        val headers = spec.design.includeDirectories.flatMap { directory ->
            if (!directory.isDirectory()) emptyList()
            else runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
                .filter { it.isRegularFile() && it.extension.lowercase() in setOf("vh", "svh", "v", "sv") }
        }
        // listOf: a Path is an Iterable of its names, which `+` would add one by one.
        return (spec.design.sources + spec.design.libraryFiles + headers + listOf(spec.constraints)).distinct().sorted()
    }

    /** Whether the bitstream was built from the files as they are now and with the same [stamp]. */
    fun isUpToDate(spec: GowinBuildSpec, stamp: String): Boolean {
        val built = runCatching { Files.getLastModifiedTime(spec.bitstream).toMillis() }.getOrNull() ?: return false
        val previous = runCatching { Files.readString(spec.directory.resolve(STAMP_FILE)) }.getOrNull() ?: return false
        if (previous != stamp) return false
        return inputs(spec).all { input -> runCatching { Files.getLastModifiedTime(input).toMillis() }.getOrDefault(Long.MAX_VALUE) <= built }
    }

    /** `Max frequency for clock 'clk': 123.45 MHz (PASS at 50.00 MHz)` lines of the routed design, from nextpnr's log. */
    fun frequencies(log: String): List<String> {
        val lines = log.lines().mapNotNull { line -> MAX_FREQUENCY.find(line)?.let { it.groupValues[1] to line.substringAfter("Max frequency for clock ").trim() } }
        // nextpnr reports them after placement and again after routing; the last report of each clock counts.
        return lines.associate { it }.values.toList()
    }

    /** The build of [spec] as steps for the programming run. */
    fun plan(spec: GowinBuildSpec): BuildPlan = object : BuildPlan {
        override fun steps(say: (String, Key<*>) -> Unit): List<BuildStep> {
            val (tools, missing) = tools()
            if (tools == null) {
                throw ExecutionException(
                    "${missing.joinToString(", ")} ${if (missing.size == 1) "was" else "were"} not found, so the bitstream cannot be built. " +
                        "The OSS CAD Suite has all of them; download it in Settings | Tools | Verilog Toolbox."
                )
            }
            if (spec.design.errors.isNotEmpty()) throw ExecutionException(spec.design.errors.joinToString("\n"))
            if (spec.design.sources.isEmpty()) throw ExecutionException("The project has no Verilog sources to build.")
            if (!spec.constraints.isRegularFile()) throw ExecutionException("The constraint file ${spec.constraints} does not exist.")
            val duplicates = GowinProject.duplicateModules(spec.design.sources)
            if (duplicates.isNotEmpty()) {
                val base = spec.directory.parent?.parent
                fun shown(file: Path) = if (base != null && file.startsWith(base)) base.relativize(file).toString() else file.toString()
                throw ExecutionException(
                    "Some modules are defined in more than one file, so the build would not know which to take:\n" +
                        duplicates.entries.joinToString("\n") { (module, files) -> "  $module: ${files.joinToString(", ") { shown(it) }}" } +
                        "\nSet a file list with the files to build in the run configuration, or remove the copies you do not use."
                )
            }
            val stamp = stamp(spec, tools.slang)
            if (isUpToDate(spec, stamp)) {
                say("${spec.bitstream.name} is up to date: nothing changed since it was built.\n", ProcessOutputTypes.SYSTEM)
                return emptyList()
            }
            try {
                Files.createDirectories(spec.directory)
                spec.bitstream.parent?.let { Files.createDirectories(it) }
                // A build that fails halfway must not count as done.
                Files.deleteIfExists(spec.directory.resolve(STAMP_FILE))
                val script = yosysScript(spec, tools.slang)
                Files.writeString(spec.directory.resolve(SCRIPT_FILE), script.script)
                script.commandFile?.let { Files.writeString(spec.directory.resolve(COMMAND_FILE), it) }
            } catch (e: IOException) {
                throw ExecutionException("Cannot write to ${spec.directory}: ${e.message}")
            }
            say("Building ${spec.top} for the ${spec.board.displayName} (${spec.board.device}) with ${spec.design.sources.size} " +
                (if (spec.design.sources.size == 1) "source" else "sources") + "\n", ProcessOutputTypes.SYSTEM)
            fun commandLine(tool: Path, arguments: List<String>) =
                ToolchainSettings.toolCommandLine(tool).withWorkingDirectory(spec.directory).withParameters(arguments)
            return listOf(
                BuildStep(
                    "Synthesizing with Yosys" + (if (tools.slang) " (SystemVerilog read by yosys-slang)" else ""),
                    commandLine(tools.yosys, yosysArguments(tools.slang)),
                ),
                BuildStep("Placing and routing with nextpnr", commandLine(tools.nextpnr, nextpnrArguments(spec))) {
                    val log = runCatching { Files.readString(spec.directory.resolve(NEXTPNR_LOG)) }.getOrDefault("")
                    frequencies(log).takeIf { it.isNotEmpty() }?.joinToString("\n") { "Max frequency for clock $it" }
                },
                BuildStep(
                    "Writing the bitstream with gowin_pack",
                    // gowin_pack warns that it runs slower without numpy, which says nothing about the bitstream.
                    commandLine(tools.pack, packArguments(spec)).withEnvironment("PYTHONWARNINGS", "ignore"),
                ) {
                    runCatching { Files.writeString(spec.directory.resolve(STAMP_FILE), stamp) }
                    null
                },
            )
        }
    }

    /** A Yosys argument in double quotes, which Yosys removes. */
    private fun quote(argument: String): String = "\"$argument\""

    private val MAX_FREQUENCY = Regex("""Max frequency for clock\s+'([^']+)'""")
}
