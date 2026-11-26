package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** What a GOWIN EDA run does: a step of the flow, all of it, or programming the device. */
enum class GowinEdaFlow(
    val title: String,
    /** Leads the name of a run configuration: `Build blinky`. */
    val verb: String,
    /** The argument of `gw_sh`'s `run`; null for a run that only programs. */
    val step: String?,
) {
    SYNTHESIS("Synthesize", "Synthesize", "syn"),
    PLACE_AND_ROUTE("Place & Route", "Place and Route", "pnr"),
    BUILD("Run All", "Build", "all"),
    BUILD_AND_PROGRAM("Run All and Program Device", "Build and Program", "all"),
    PROGRAM("Program Device", "Program", null);

    val programs: Boolean get() = this == PROGRAM || this == BUILD_AND_PROGRAM
    val builds: Boolean get() = step != null

    override fun toString(): String = title

    companion object {
        fun of(name: String?): GowinEdaFlow = entries.firstOrNull { it.name == name } ?: BUILD
    }
}

/** Where the bitstream goes: the FPGA's SRAM, until it loses power, or the flash it configures itself from. */
enum class GowinEdaTarget(val title: String) {
    SRAM("SRAM: configures the FPGA until it loses power"),
    FLASH("Flash: kept when the power is off"),
    OPERATION("Other operation of the programmer");

    override fun toString(): String = title

    companion object {
        fun of(name: String?): GowinEdaTarget = entries.firstOrNull { it.name == name } ?: SRAM
    }
}

/** How the device is programmed. Blank means "work it out". */
data class GowinEdaProgramming(
    val target: GowinEdaTarget = GowinEdaTarget.SRAM,
    /** `programmer_cli --run` for [GowinEdaTarget.OPERATION]. */
    val operation: String = "",
    val verify: Boolean = false,
    /** A cable as `programmer_cli --cable` names it; empty for its default, the FT2232 cable Sipeed's boards emulate. */
    val cable: String = "",
    val frequency: String = "",
    /** A bitstream other than the project's. */
    val file: String = "",
    val options: String = "",
)

/** GOWIN's command-line programmer, `programmer_cli`. */
object GowinEdaProgrammer {
    val CABLES = listOf(
        "Gowin USB Cable(FT2CH)", "Gowin USB Cable(GWU2X)", "Gowin USB Cable(WINUSB)", "Digilent USB Device", "USB Debugger A",
        "Parallel Port(LPT)",
    )
    val FREQUENCIES = listOf("2.5MHz", "15MHz", "10MHz", "2MHz", "1.5MHz", "1.1MHz", "0.9MHz", "0.75MHz", "0.5MHz")

    /**
     * The operation (`--run`) that writes a bitstream to [target] of [device]. The LittleBee devices (GW1N…) have
     * flash of their own; the Arora devices load from an external SPI flash, which Arora V reaches in its own way.
     */
    fun operation(device: String, target: GowinEdaTarget, verify: Boolean): Int = when (target) {
        GowinEdaTarget.SRAM -> if (verify) 4 else 2
        GowinEdaTarget.FLASH, GowinEdaTarget.OPERATION -> when {
            device.startsWith("GW5", ignoreCase = true) -> if (verify) 54 else 53
            device.startsWith("GW1N", ignoreCase = true) -> if (verify) 6 else 5
            else -> if (verify) 9 else 8
        }
    }

    /** What operation [index] does, as `programmer_cli --help` lists them. */
    fun describe(index: Int): String = OPERATIONS[index] ?: "operation $index"

    private val OPERATIONS = mapOf(
        2 to "SRAM Program", 4 to "SRAM Program and Verify", 5 to "embedded flash Erase, Program",
        6 to "embedded flash Erase, Program, Verify", 8 to "external flash Erase, Program", 9 to "external flash Erase, Program, Verify",
        53 to "external flash Erase, Program (Arora V)", 54 to "external flash Erase, Program, Verify (Arora V)",
        0 to "Read Device Codes", 1 to "Reprogram", 24 to "SRAM Erase", 7 to "embedded flash Erase",
    )

    fun arguments(device: String, operation: Int, file: Path, programming: GowinEdaProgramming): List<String> = buildList {
        add("--device"); add(device)
        add("--run"); add(operation.toString())
        add("--fsFile"); add(file.toString())
        programming.cable.takeIf { it.isNotBlank() }?.let { add("--cable"); add(it) }
        programming.frequency.takeIf { it.isNotBlank() }?.let { add("--frequency"); add(it) }
        addAll(ParametersListUtil.parse(programming.options))
    }
}

/** A step of a run: a tool to start once the steps before it have succeeded. */
internal class GowinEdaStep(
    val title: String,
    /** Builds the command line when the step is due. [report] prints a line about what it found out. */
    val commandLine: (report: (String) -> Unit) -> GeneralCommandLine,
)

internal object GowinEdaSteps {
    const val SCRIPT = "verilog_toolbox_run.tcl"

    /** The Tcl script that runs [step] of the flow of [project], with the top module the IDE worked out unless the options name one. */
    fun script(project: GowinEdaProject, step: String): String = buildString {
        append("# Written by Verilog Toolbox for gw_sh.\n")
        append("open_project ").append(tclQuote(project.file.toString().replace('\\', '/'))).append('\n')
        if (!project.isTopModuleSet) project.topModule?.let { append("set_option -top_module ").append(tclQuote(it)).append('\n') }
        append("run ").append(step).append('\n')
    }

    /** A Tcl word in braces, which take everything literally. */
    private fun tclQuote(text: String): String = if (text.all { it.isLetterOrDigit() || it in "_-./:" }) text else "{$text}"

    @Throws(ExecutionException::class)
    fun create(installation: GowinEdaInstallation, project: GowinEdaProject, flow: GowinEdaFlow, programming: GowinEdaProgramming): List<GowinEdaStep> {
        val steps = mutableListOf<GowinEdaStep>()
        flow.step?.let { step ->
            val shell = installation.tool(GowinEda.SHELL) ?: throw ExecutionException("${installation.displayName} has no ${GowinEda.binary(GowinEda.SHELL)}.")
            steps += GowinEdaStep(GowinEda.SHELL) { report ->
                if (project.device == null) throw ExecutionException("${project.file.fileName} names no device. Choose one with Tools | GOWIN EDA | Device.")
                if (project.verilogSources.isEmpty() && project.enabledFiles.none { it.kind == GowinEdaFileKind.VHDL || it.kind == GowinEdaFileKind.NETLIST }) {
                    throw ExecutionException("${project.file.fileName} lists no design files. Add them with Add to GOWIN EDA Project.")
                }
                if (!project.isTopModuleSet) project.topModule?.let { report("Top module: $it, worked out from the sources and constraints; set another with Set as GOWIN EDA Top Module.") }
                val script = project.implDirectory.resolve(SCRIPT)
                try {
                    Files.createDirectories(script.parent)
                    Files.writeString(script, script(project, step))
                } catch (e: IOException) {
                    throw ExecutionException("Cannot write ${script}: ${e.message}")
                }
                installation.commandLine(shell, project.directory).withParameters(script.toString())
            }
        }
        if (flow.programs) steps += program(installation, project, programming)
        return steps
    }

    private fun program(installation: GowinEdaInstallation, project: GowinEdaProject, programming: GowinEdaProgramming): GowinEdaStep {
        val programmer = installation.programmer
            ?: throw ExecutionException("${installation.displayName} has no programmer: ${GowinEda.binary(GowinEda.PROGRAMMER)} is not in ${installation.rootDirectory.resolve("Programmer")}.")
        return GowinEdaStep(GowinEda.PROGRAMMER) { report ->
            val device = project.device?.name?.takeIf { it.isNotBlank() }
                ?: throw ExecutionException("${project.file.fileName} names no device to program.")
            val file = bitstream(project, programming)
            val operation = if (programming.target == GowinEdaTarget.OPERATION) {
                programming.operation.trim().toIntOrNull() ?: throw ExecutionException("The programmer operation is not a number.")
            } else {
                GowinEdaProgrammer.operation(device, programming.target, programming.verify)
            }
            report("Programming ${file.fileName} into $device: ${GowinEdaProgrammer.describe(operation)}" +
                (programming.cable.takeIf { it.isNotBlank() }?.let { " over $it" } ?: ""))
            // The programmer finds its own files next to it.
            installation.commandLine(programmer, programmer.parent).withParameters(GowinEdaProgrammer.arguments(device, operation, file, programming))
        }
    }

    /** The bitstream to program: the project's, or the one named. */
    @Throws(ExecutionException::class)
    fun bitstream(project: GowinEdaProject, programming: GowinEdaProgramming): Path {
        val file = programming.file.takeIf { it.isNotBlank() }?.let { project.resolve(it) } ?: project.bitstream
        if (!Files.isRegularFile(file)) throw ExecutionException("The bitstream $file does not exist. Build the project first.")
        return file
    }
}

/** The console colours of the messages: errors are standard error, the rest by severity. */
object GowinEdaOutput {
    val WARNING: Key<Any> = Key.create("verilogToolbox.gowinEda.warning")
    val NOTE: Key<Any> = Key.create("verilogToolbox.gowinEda.note")

    init {
        ConsoleViewContentType.registerNewConsoleViewType(WARNING, ConsoleViewContentType.LOG_WARNING_OUTPUT)
        ConsoleViewContentType.registerNewConsoleViewType(NOTE, ConsoleViewContentType.LOG_DEBUG_OUTPUT)
    }

    fun of(severity: GowinEdaSeverity): Key<*> = when (severity) {
        GowinEdaSeverity.ERROR -> ProcessOutputTypes.STDERR
        GowinEdaSeverity.WARNING -> WARNING
        GowinEdaSeverity.NOTE -> NOTE
        GowinEdaSeverity.INFO -> ProcessOutputTypes.STDOUT
    }
}

/**
 * Runs the steps of a flow one after the other, colours the messages by severity, follows the stages the tools
 * announce, collects the messages and ends with what the reports say: resources, timing and the errors there were.
 */
internal class GowinEdaProcessHandler(
    private val project: Project?,
    private val gowinProject: GowinEdaProject,
    private val flow: GowinEdaFlow,
    private val steps: List<GowinEdaStep>,
    private val header: String,
) : ProcessHandler() {
    @Volatile
    private var process: OSProcessHandler? = null

    @Volatile
    private var stopped = false

    private val collected = Collections.synchronizedList(mutableListOf<GowinEdaMessage>())

    val messages: List<GowinEdaMessage> get() = synchronized(collected) { collected.toList() }

    private val pending = HashMap<Key<*>, StringBuilder>()
    private val started = System.currentTimeMillis()
    private val finished = AtomicBoolean()

    override fun startNotify() {
        super.startNotify()
        notifyTextAvailable(header + "\n", ProcessOutputTypes.SYSTEM)
        project?.let { GowinEdaRuns.getInstance(it).started(flow) }
        ApplicationManager.getApplication().executeOnPooledThread { next(0) }
    }

    private fun next(index: Int) {
        if (stopped) return finish(1)
        val step = steps.getOrNull(index) ?: return finish(0)
        if (step.title == GowinEda.PROGRAMMER) project?.let { GowinEdaRuns.getInstance(it).stage("Programming") }
        val handler = try {
            OSProcessHandler(step.commandLine { notifyTextAvailable(it + "\n", ProcessOutputTypes.SYSTEM) })
        } catch (e: Exception) {
            if (e !is IOException && e !is ExecutionException) throw e
            val message = GowinEdaMessage(GowinEdaSeverity.ERROR, null, e.message ?: e.toString())
            collected += message
            notifyTextAvailable(message.text + "\n", ProcessOutputTypes.STDERR)
            return finish(1)
        }
        process = handler
        val errorsBefore = errorCount()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = text(event.text, outputType)

            override fun processTerminated(event: ProcessEvent) {
                flush()
                // The programmer can say it found no device and still exit with 0; an error is a failure either way.
                val exitCode = if (event.exitCode == 0 && errorCount() > errorsBefore) 1 else event.exitCode
                // Each tool's process ends on a thread of its own, so the next one may be started from here.
                if (exitCode == 0 && !stopped) next(index + 1) else finish(exitCode)
            }
        })
        if (stopped) handler.destroyProcess()
        handler.startNotify()
    }

    private fun errorCount(): Int = synchronized(collected) { collected.count { it.severity == GowinEdaSeverity.ERROR } }

    @Synchronized
    private fun text(text: String, outputType: Key<*>) {
        if (outputType === ProcessOutputTypes.SYSTEM) return
        val buffer = pending.getOrPut(outputType) { StringBuilder() }
        buffer.append(text)
        while (true) {
            val end = buffer.indexOf("\n")
            if (end < 0) break
            line(buffer.substring(0, end).trimEnd('\r'), outputType)
            buffer.delete(0, end + 1)
        }
    }

    @Synchronized
    private fun flush() {
        for ((outputType, buffer) in pending) if (buffer.isNotEmpty()) line(buffer.toString(), outputType)
        pending.clear()
    }

    private fun line(line: String, outputType: Key<*>) {
        if (GowinEdaConsole.isNoise(line, outputType)) return
        GowinEdaConsole.stage(line)?.let { stage -> project?.let { GowinEdaRuns.getInstance(it).stage(stage) } }
        val message = GowinEdaMessage.parse(line)
        if (message != null) collected += message
        // The programmer says what failed in plain words.
        val type = message?.let { GowinEdaOutput.of(it.severity) }
            ?: if (line.trimStart().startsWith("Error", ignoreCase = true)) ProcessOutputTypes.STDERR else outputType
        if (message == null && type === ProcessOutputTypes.STDERR && line.isNotBlank()) {
            collected += GowinEdaMessage(GowinEdaSeverity.ERROR, null, line.trim().removePrefix("Error:").trim())
        }
        notifyTextAvailable(line + "\n", type)
    }

    private fun finish(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        if (!stopped) {
            try {
                GowinEdaConsole.conclusion(gowinProject, flow, exitCode, messages, System.currentTimeMillis() - started)
                    .forEach { (text, type) -> notifyTextAvailable(text + "\n", type) }
            } catch (e: Exception) {
                // A report that cannot be read must not keep the run from ending.
                notifyTextAvailable("The reports could not be read: ${e.message}\n", ProcessOutputTypes.SYSTEM)
            }
        }
        project?.let { GowinEdaRuns.getInstance(it).finished(gowinProject, flow, messages) }
        notifyProcessTerminated(exitCode)
    }

    override fun destroyProcessImpl() {
        stopped = true
        val handler = process
        if (handler != null && !handler.isProcessTerminated) handler.destroyProcess() else if (handler == null) finish(1)
    }

    override fun detachProcessImpl() = destroyProcessImpl()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = process?.processInput
}

/** What the console shows around the tools' own output. */
internal object GowinEdaConsole {
    private const val MAX_LISTED = 20

    private val TCL_TRACE = listOf(Regex("""^\s*while executing\s*$"""), Regex("""^"run \w+"\s*$"""), Regex("""^\s*\(file ".*" line \d+\)\s*$"""))

    /** Lines that say nothing about the design: the shell's banner and the Tcl trace of a failed `run`, whose reason is printed already. */
    fun isNoise(line: String, outputType: Key<*>): Boolean =
        line.startsWith("*** GOWIN Tcl Command Line Console") ||
            outputType === ProcessOutputTypes.STDERR && (line.isBlank() || TCL_TRACE.any { it.matches(line) })

    private val STAGES = listOf(
        "GowinSynthesis start" to "Synthesis",
        "Running placement" to "Placement",
        "Running routing" to "Routing",
        "Running timing analysis" to "Timing Analysis",
        "Bitstream generation in progress" to "Bitstream",
        "Running power analysis" to "Power Analysis",
    )

    /** The stage a line of output starts, if it starts one. */
    fun stage(line: String): String? = STAGES.firstOrNull { line.startsWith(it.first) }?.second

    /**
     * The lines a run ends with, each with the stream it is printed on: after a failure the errors once more,
     * after a build the resources and timing from the reports, and where the bitstream is.
     */
    fun conclusion(
        project: GowinEdaProject, flow: GowinEdaFlow, exitCode: Int, messages: List<GowinEdaMessage>, elapsedMs: Long,
    ): List<Pair<String, Key<*>>> = buildList {
        val out = ProcessOutputTypes.STDOUT
        val errors = messages.filter { it.severity == GowinEdaSeverity.ERROR }
        val warnings = messages.count { it.severity == GowinEdaSeverity.WARNING }
        add("" to out)
        if (exitCode != 0) {
            add("${flow.title} of ${project.name} failed after ${elapsed(elapsedMs)}" +
                (if (errors.isEmpty()) " (exit code $exitCode)." else " with ${YosysSynthesis.plural(errors.size, "error")}:") to ProcessOutputTypes.STDERR)
            for (error in errors.take(MAX_LISTED)) {
                val location = error.file?.let { " (\"$it\":${error.line ?: 1})" }.orEmpty()
                add("  " + listOfNotNull(error.code?.let { "($it)" }, error.text).joinToString(" ") + location to ProcessOutputTypes.STDERR)
            }
            if (errors.size > MAX_LISTED) add("  … and ${errors.size - MAX_LISTED} more, see above." to ProcessOutputTypes.STDERR)
            return@buildList
        }
        if (flow == GowinEdaFlow.PROGRAM) {
            add("${project.name} is programmed." to out)
            return@buildList
        }
        add("${flow.title} of ${project.name}" + (project.device?.let { " for ${it.partNumber}" } ?: "") + " finished in ${elapsed(elapsedMs)}" +
            (if (warnings == 0) "." else ", with ${YosysSynthesis.plural(warnings, "warning")}.") to out)
        val compilation = GowinEdaCompilation(project)
        val usage = compilation.resources.filter { !it.isPart && it.usage.firstOrNull()?.isDigit() == true && !it.usage.startsWith("0") }
        if (usage.isNotEmpty()) add("  " + usage.joinToString("   ") { "${it.name}: ${it.usage}" + (it.utilization?.let { u -> " ($u)" } ?: "") } to out)
        compilation.timing?.let { timing ->
            if (timing.isMet) {
                val fmax = timing.fmax.joinToString(", ") { "${it.clock} %.2f MHz".format(java.util.Locale.ROOT, it.fmax) }
                val slack = timing.worst.filter { it.check == "Setup" || it.check == "Hold" }.joinToString(", ") { "${it.check.lowercase()} slack ${it.slack} ns" }
                add("  Timing is met" + (if (slack.isEmpty()) "." else ": $slack.") + (if (fmax.isEmpty()) "" else " Fmax: $fmax.") to out)
            } else {
                add("  Timing is NOT met:" to ProcessOutputTypes.STDERR)
                for (negative in timing.negative.filter { it.endpoints > 0 }) {
                    add("    ${negative.check} of clock '${negative.clock}': ${YosysSynthesis.plural(negative.endpoints, "failing endpoint")}, total negative slack ${negative.total} ns" to ProcessOutputTypes.STDERR)
                }
                for (fmax in timing.fmax.filter { it.constraint != null && it.fmax < it.constraint }) {
                    add("    Clock '${fmax.clock}' reaches %.2f MHz of the %.2f MHz asked for".format(java.util.Locale.ROOT, fmax.fmax, fmax.constraint) to ProcessOutputTypes.STDERR)
                }
            }
        }
        compilation.bitstream?.takeIf { flow == GowinEdaFlow.BUILD || flow == GowinEdaFlow.BUILD_AND_PROGRAM || flow == GowinEdaFlow.PLACE_AND_ROUTE }
            ?.let { add("  Bitstream: $it" to out) }
        compilation.reports.firstOrNull()?.let { (_, file) -> add("  Report: $file" to out) }
        if (flow == GowinEdaFlow.BUILD_AND_PROGRAM) add("${project.name} is programmed." to out)
    }

    /** `95000` as `1:35 min`, `34000` as `34 s`. */
    fun elapsed(milliseconds: Long): String {
        val seconds = (milliseconds + 500) / 1000
        return if (seconds < 60) "$seconds s" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} min"
    }
}

/**
 * Links what the tools print to the files it is about: `("C:\p\top.v":21)` at the end of a message,
 * `"C:\p\top.cst":2 |` at its start, the files they say they generate, and the conclusion's bitstream and report.
 */
class GowinEdaConsoleFilter(private val project: Project, private val directory: Path) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (line.length > MAX_LINE_LENGTH) return null
        val lineStart = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()
        for (match in QUOTED.findAll(line)) {
            val file = find(match.groupValues[1]) ?: continue
            val lineNumber = (match.groupValues[2].toIntOrNull() ?: 1) - 1
            items += Filter.ResultItem(lineStart + match.range.first, lineStart + match.range.last + 1, OpenFileHyperlinkInfo(project, file, maxOf(lineNumber, 0)))
        }
        if (items.isEmpty()) {
            OUTPUT.find(line)?.let { match ->
                val path = match.groupValues[2].trim()
                val file = find(path) ?: return@let
                val start = lineStart + match.groups[2]!!.range.first
                items += Filter.ResultItem(start, start + path.length, OpenFileHyperlinkInfo(project, file, 0))
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun find(path: String) =
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve(path).normalize())?.takeIf { !it.isDirectory }
        } catch (_: InvalidPathException) {
            null
        }

    private companion object {
        const val MAX_LINE_LENGTH = 4000

        /** A quoted path, maybe with a line: `"C:\p\top.v":21`, `"C:\p\impl\pnr\top.rpt.txt"`. */
        val QUOTED = Regex(""""((?:[A-Za-z]:[\\/]|/)[^"]+)"(?::(\d+))?""")
        val OUTPUT = Regex("""^\s*(Bitstream|Report): (.+)$""")
    }
}

/** The GOWIN EDA runs of an IDE project: whether one is under way, and what the last one said. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class GowinEdaRuns(private val project: Project) {
    @Volatile
    var running: GowinEdaFlow? = null
        private set

    /** The stage of the flow that is running, such as `Routing`, as far as the tools have said. */
    @Volatile
    var stage: String? = null
        private set

    private class Last(val projectFile: Path, val flow: GowinEdaFlow, val messages: List<GowinEdaMessage>)

    @Volatile
    private var last: Last? = null

    internal fun stage(name: String) {
        if (stage == name) return
        stage = name
        GowinEdaProjectService.getInstance(project).changed()
    }

    internal fun started(flow: GowinEdaFlow) {
        stage = null
        running = flow
        GowinEdaProjectService.getInstance(project).changed()
    }

    internal fun finished(gowinProject: GowinEdaProject, flow: GowinEdaFlow, messages: List<GowinEdaMessage>) {
        running = null
        stage = null
        // Programming says little; what it says replaces the build's messages only when it failed.
        if (flow.builds || messages.any { it.severity == GowinEdaSeverity.ERROR }) last = Last(gowinProject.file, flow, messages)
        GowinEdaProjectService.getInstance(project).outputChanged()
    }

    /** The last run of [gowinProject] in this session: its flow and messages; nulls before one, when the logs are all there is. */
    fun lastRun(gowinProject: GowinEdaProject): Pair<GowinEdaFlow?, List<GowinEdaMessage>?> =
        last?.takeIf { it.projectFile == gowinProject.file }?.let { it.flow to it.messages } ?: (null to null)

    companion object {
        fun getInstance(project: Project): GowinEdaRuns = project.getService(GowinEdaRuns::class.java)
    }
}
