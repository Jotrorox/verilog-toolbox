package com.jotrorox.verilogtoolbox

import com.google.gson.JsonParser
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/** What Check Synthesis maps a design to: generic gates, or the cells of an FPGA family. */
enum class SynthesisTarget(val displayName: String, val command: String) {
    GENERIC("Generic gates", "synth"),
    ICE40("Lattice iCE40", "synth_ice40"),
    ECP5("Lattice ECP5", "synth_ecp5"),
    GOWIN("Gowin", "synth_gowin"),
    XILINX("AMD Xilinx 7-series", "synth_xilinx");

    override fun toString(): String = displayName
}

/**
 * Check Synthesis: runs a module and what it instantiates through Yosys, which reports what it cannot
 * synthesize or finds suspicious (multiple drivers, logic loops, latches), and sums up the resources the
 * design takes.
 *
 * SystemVerilog is read with the yosys-slang front end when Yosys has it, as the OSS CAD Suite's does, so
 * that Yosys sees the design as slang-server does; otherwise with Yosys's own Verilog front end.
 */
object YosysSynthesis {
    const val SCRIPT_FILE = "synth.ys"
    const val COMMAND_FILE = "design.f"
    const val LOG_FILE = "yosys.log"
    const val STAT_FILE = "stat.txt"
    const val STAT_JSON_FILE = "stat.json"

    private val WARNING_COUNT = Regex("""^Warnings: \d+ unique messages?, (\d+) total""", RegexOption.MULTILINE)

    /** The script and, for yosys-slang, the command file it reads, both meant for the run directory. */
    data class Run(val script: String, val commandFile: String?)

    /**
     * The run that synthesizes [top] of [design] for [target]. Paths only appear in the command file or in
     * quotes, and the files the run writes are named relative to the directory it runs in.
     */
    internal fun run(design: FileList, top: String, target: SynthesisTarget, slang: Boolean): Run {
        val read = if (slang) {
            "read_slang -f $COMMAND_FILE"
        } else {
            buildString {
                append("read_verilog -sv")
                for (define in design.defines) append(" ").append(quote("-D$define"))
                for (directory in design.includeDirectories) append(" ").append(quote("-I$directory"))
                for (file in design.sources + design.libraryFiles) append(" ").append(quote(file.toString()))
            }
        }
        val hierarchy = buildString {
            append("hierarchy -check -top ").append(top)
            // Yosys's front end has no library directories; hierarchy looks modules up as <module>.v there.
            if (!slang) for (directory in design.libraryDirectories) append(" -libdir ").append(quote(directory.toString()))
        }
        val script = listOf(
            read,
            hierarchy,
            "${target.command} -top $top",
            "tee -q -o $STAT_FILE stat",
            "tee -q -o $STAT_JSON_FILE stat -json",
        ).joinToString("\n", postfix = "\n")
        return Run(script, if (slang) commandFile(design, top) else null)
    }

    /** A slang command file with the design: the same syntax as a project's `.f`, every path quoted. */
    internal fun commandFile(design: FileList, top: String): String = buildString {
        for (directory in design.includeDirectories) append("-I ").append(slangQuote(directory.toString())).append('\n')
        for (define in design.defines) append("-D ").append(slangQuote(define)).append('\n')
        for (directory in design.libraryDirectories) append("-y ").append(slangQuote(directory.toString())).append('\n')
        val extensions = (VerilogDesign.LIBRARY_EXTENSIONS + design.libraryExtensions).distinct()
        if (design.libraryDirectories.isNotEmpty()) append("--libext ").append(extensions.joinToString(",")).append('\n')
        for (file in design.libraryFiles) append("-v ").append(slangQuote(file.toString())).append('\n')
        for (source in design.sources) append(slangQuote(source.toString())).append('\n')
        append("--top ").append(top).append('\n')
    }

    /** A Yosys argument in double quotes, which Yosys removes. */
    private fun quote(argument: String): String = "\"$argument\""

    /**
     * An argument quoted for a slang command file: in single quotes, which take everything literally. A
     * single quote in it is closed, given in double quotes and reopened, as in a shell: `'it'"'"'s'`.
     */
    internal fun slangQuote(argument: String): String = "'" + argument.replace("'", "'\"'\"'") + "'"

    /** Whether [yosys] can load the yosys-slang plugin, which lives in `share/yosys/plugins` of its installation. */
    fun hasSlangPlugin(yosys: Path): Boolean {
        val candidates = listOfNotNull(yosys, runCatching { yosys.toRealPath() }.getOrNull()).distinct()
        return candidates.any { binary ->
            val plugins = binary.parent?.parent?.resolve("share")?.resolve("yosys")?.resolve("plugins")
            plugins != null && listOf("slang.so", "slang.dll").any { Files.isRegularFile(plugins.resolve(it)) }
        }
    }

    /** The resources a synthesized design takes, from `stat -json`. */
    data class Summary(
        val cells: Int,
        val luts: Int,
        val flipFlops: Int,
        val latches: Int,
        val memories: Int,
        val dsps: Int,
    ) {
        val other: Int get() = cells - luts - flipFlops - latches - memories - dsps

        /** Such as `120 cells: 64 LUTs, 32 flip-flops, 24 other`. */
        fun describe(): String {
            val parts = listOf(
                luts to "LUT", flipFlops to "flip-flop", latches to "latch", memories to "block RAM", dsps to "DSP",
            ).filter { it.first > 0 }.map { (count, name) -> plural(count, name) }
            val all = if (other > 0 && parts.isNotEmpty()) parts + "$other other" else parts
            return plural(cells, "cell") + if (all.isEmpty()) "" else ": " + all.joinToString(", ")
        }
    }

    internal fun plural(count: Int, noun: String): String = when {
        count == 1 -> "1 $noun"
        noun.endsWith("ch") -> "$count ${noun}es"
        else -> "$count ${noun}s"
    }

    /** Reads the design's totals from `stat -json` output; null if it holds none. */
    internal fun summary(statJson: String): Summary? {
        val design = runCatching { JsonParser.parseString(statJson).asJsonObject["design"]?.asJsonObject }.getOrNull()
            ?: return null
        val byType = design["num_cells_by_type"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
            ?.associate { (type, count) -> type to count.asInt }.orEmpty()
        var luts = 0
        var flipFlops = 0
        var latches = 0
        var memories = 0
        var dsps = 0
        for ((type, count) in byType) {
            when (category(type)) {
                Category.LUT -> luts += count
                Category.FLIP_FLOP -> flipFlops += count
                Category.LATCH -> latches += count
                Category.MEMORY -> memories += count
                Category.DSP -> dsps += count
                null -> {}
            }
        }
        val cells = design["num_cells"]?.asInt ?: byType.values.sum()
        return Summary(cells, luts, flipFlops, latches, memories, dsps)
    }

    private enum class Category { LUT, FLIP_FLOP, LATCH, MEMORY, DSP }

    /**
     * Sorts a cell type of Yosys's gate library or of an FPGA family's: `$_DFF_P_`, `SB_DFFE`,
     * `TRELLIS_FF`, `FDRE`, `$_DLATCH_P_`, `LDCE`, `SB_LUT4`, `LUT6`, `SB_RAM40_4K`, `DP16KD`, `RAMB36E1`,
     * `$mem_v2`, `SB_MAC16`, `MULT18X18D`, `DSP48E1`.
     */
    private fun category(type: String): Category? {
        val name = type.uppercase()
        return when {
            "LATCH" in name || Regex("^LD[CP]E?$").matches(name) -> Category.LATCH
            "DFF" in name || name.endsWith("_FF") || Regex("^FD[RSCP]E?(_1)?$").matches(name) -> Category.FLIP_FLOP
            "LUT" in name -> Category.LUT
            "RAM" in name || name.startsWith("\$MEM") || Regex("^(DP16KD|PDPW16KD|SP|SDPB?|DPB?|ROM)\\d*").matches(name) ->
                Category.MEMORY
            "DSP" in name || "MAC" in name || "MULT" in name -> Category.DSP
            else -> null
        }
    }

    /** The number of warnings Yosys counted in its [log]. */
    internal fun warningCount(log: String): Int = WARNING_COUNT.findAll(log).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** `<project>/build/synth/<top>`, next to where simulations run. */
    fun runDirectory(project: Project, top: String): Path {
        val base = project.basePath?.let { Path.of(it).resolve("build") }
            ?: Path.of(FileUtil.getTempDirectory(), "verilog-toolbox", project.locationHash)
        return base.resolve("synth").resolve(FileUtil.sanitizeFileName(top))
    }

    /** Synthesizes [top], the module of [file], showing the run in the Run tool window. Call on the UI thread. */
    fun start(project: Project, file: Path, top: String) {
        val toolchain = ToolchainSettings.getInstance()
        val yosys = toolchain.findYosys()
            ?: return OssCadSuite.notifyMissing(project, "Yosys", "Check Synthesis runs the design through Yosys.")
        // Yosys reads the sources from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        val target = toolchain.synthesisTarget
        object : Task.Backgroundable(project, "Preparing synthesis of $top", true) {
            override fun run(indicator: ProgressIndicator) {
                val directories = ReadAction.nonBlocking(Callable { VerilogDesign.libraryDirectories(project) })
                    .inSmartMode(project).executeSynchronously()
                val design = VerilogDesign.forFile(project, file, directories)
                val directory = runDirectory(project, top)
                val handler = SynthesisProcessHandler(yosys, design, top, target, directory)
                ApplicationManager.getApplication().invokeLater({ show(project, handler, file, top, directory) }, project.disposed)
            }
        }.queue()
    }

    private fun show(project: Project, handler: SynthesisProcessHandler, file: Path, top: String, directory: Path) {
        RunContentExecutor(project, handler)
            .withTitle("Synthesis: $top")
            .withRerun { start(project, file, top) }
            .withFilter(IcarusConsoleFilter(project) { listOfNotNull(directory, project.basePath?.let { Path.of(it) }) })
            .withFilter(LogFileFilter(project, directory.resolve(LOG_FILE)))
            .withActivateToolWindow(true)
            .run()
    }

    /** Links the full log that the run names at its end. */
    private class LogFileFilter(private val project: Project, private val log: Path) : Filter {
        private val text = log.toString()

        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            val index = line.indexOf(text).takeIf { it >= 0 } ?: return null
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(log) ?: return null
            val start = entireLength - line.length + index
            return Filter.Result(start, start + text.length, OpenFileHyperlinkInfo(project, file, 0))
        }
    }
}

/**
 * Runs Yosys quietly, so that the console shows its warnings and errors, and then prints the resource
 * statistics and a summary of them.
 */
internal class SynthesisProcessHandler(
    private val yosys: Path,
    private val design: FileList,
    private val top: String,
    private val target: SynthesisTarget,
    private val directory: Path,
) : ProcessHandler() {
    @Volatile
    private var process: OSProcessHandler? = null

    @Volatile
    private var stopped = false

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            val handler = try {
                OSProcessHandler(prepare())
            } catch (e: Exception) {
                if (e !is IOException && e !is ExecutionException) throw e
                notifyTextAvailable((e.message ?: e.toString()) + "\n", ProcessOutputTypes.STDERR)
                notifyProcessTerminated(1)
                return@executeOnPooledThread
            }
            process = handler
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = notifyTextAvailable(event.text, outputType)

                override fun processTerminated(event: ProcessEvent) {
                    if (!stopped) report(event.exitCode)
                    notifyProcessTerminated(event.exitCode)
                }
            })
            if (stopped) handler.destroyProcess()
            handler.startNotify()
        }
    }

    @Throws(IOException::class, ExecutionException::class)
    private fun prepare(): GeneralCommandLine {
        if (design.errors.isNotEmpty()) throw ExecutionException(design.errors.joinToString("\n"))
        if (design.ignored.isNotEmpty()) {
            notifyTextAvailable(
                "Options of the file list that are not passed to Yosys: ${design.ignored.joinToString(" ")}\n",
                ProcessOutputTypes.SYSTEM,
            )
        }
        Files.createDirectories(directory)
        for (name in listOf(YosysSynthesis.LOG_FILE, YosysSynthesis.STAT_FILE, YosysSynthesis.STAT_JSON_FILE)) {
            Files.deleteIfExists(directory.resolve(name))
        }
        val slang = YosysSynthesis.hasSlangPlugin(yosys)
        val run = YosysSynthesis.run(design, top, target, slang)
        Files.writeString(directory.resolve(YosysSynthesis.SCRIPT_FILE), run.script)
        run.commandFile?.let { Files.writeString(directory.resolve(YosysSynthesis.COMMAND_FILE), it) }
        notifyTextAvailable(
            "Synthesizing $top for ${target.displayName}" + (if (slang) " (SystemVerilog read by yosys-slang)" else "") + "\n",
            ProcessOutputTypes.SYSTEM,
        )
        return ToolchainSettings.toolCommandLine(yosys)
            .withWorkingDirectory(directory)
            .withParameters(listOf("-q", "-l", YosysSynthesis.LOG_FILE) + (if (slang) listOf("-m", "slang") else emptyList()))
            .withParameters("-s", YosysSynthesis.SCRIPT_FILE)
    }

    private fun report(exitCode: Int) {
        val log = directory.resolve(YosysSynthesis.LOG_FILE)
        val warnings = read(log)?.let { YosysSynthesis.warningCount(it) } ?: 0
        val statistics = read(directory.resolve(YosysSynthesis.STAT_FILE))
        val summary = read(directory.resolve(YosysSynthesis.STAT_JSON_FILE))?.let { YosysSynthesis.summary(it) }
        if (exitCode == 0 && statistics != null) notifyTextAvailable(statistics.trimEnd() + "\n\n", ProcessOutputTypes.STDOUT)
        val result = when {
            exitCode != 0 -> "$top does not synthesize for ${target.displayName}."
            summary != null -> "$top synthesizes for ${target.displayName} to ${summary.describe()}."
            else -> "$top synthesizes for ${target.displayName}."
        }
        val remarks = buildList {
            if (warnings > 0) add(YosysSynthesis.plural(warnings, "warning") + ", see above.")
            if (summary != null && summary.latches > 0) {
                add("Latches are inferred where combinational logic does not assign a signal on every path.")
            }
        }
        val outputType = if (exitCode != 0) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT
        notifyTextAvailable((listOf(result) + remarks).joinToString(" ") + "\n", outputType)
        if (Files.isRegularFile(log)) notifyTextAvailable("Full log: $log\n", ProcessOutputTypes.SYSTEM)
    }

    private fun read(file: Path): String? = runCatching { Files.readString(file) }.getOrNull()

    override fun destroyProcessImpl() {
        stopped = true
        val handler = process
        if (handler != null) handler.destroyProcess()
    }

    override fun detachProcessImpl() = destroyProcessImpl()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = process?.processInput
}

/** Check Synthesis on the module at the caret, the file's only module, or one the user picks. */
class CheckSynthesisAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val path = file?.takeIf { !it.isDirectory && it.isVerilog() }?.let { it.fileSystem.getNioPath(it) }
        // Yosys reads the design from disk; a header is only synthesized where it is included.
        e.presentation.isEnabledAndVisible = e.project != null && path != null && VerilatorLint.isLinted(path)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val document = editor?.document ?: FileDocumentManager.getInstance().getDocument(file) ?: return
        val all = VerilogModuleHeaders.modules(document.immutableCharSequence)
        // A module without ports is a testbench, which synthesizes to nothing.
        val modules = all.filter { it.ports.isNotEmpty() }.ifEmpty { all }
        val offset = editor?.caretModel?.offset
        val module = all.firstOrNull { offset != null && offset in it.range } ?: modules.singleOrNull()
        val path = file.fileSystem.getNioPath(file) ?: return
        when {
            module != null -> YosysSynthesis.start(project, path, module.name)
            modules.isEmpty() -> NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                .createNotification("${file.name} has no module to synthesize.", NotificationType.WARNING)
                .notify(project)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(modules.map { it.name })
                .setTitle("Check Synthesis Of")
                .setItemChosenCallback { name -> YosysSynthesis.start(project, path, name) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }
}
