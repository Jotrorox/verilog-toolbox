package com.jotrorox.verilogtoolbox.quartus

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.yosys.YosysSynthesis
import java.awt.Font
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** What a Quartus run does: one step of the compilation, all of it, or programming the device. */
enum class QuartusFlow(val title: String, /** Leads the name of a run configuration: `Compile blinky`. */ val verb: String) {
    ANALYSIS("Analysis & Elaboration", "Analyze"),
    SYNTHESIS("Analysis & Synthesis", "Synthesize"),
    FITTER("Fitter (Place & Route)", "Fit"),
    ASSEMBLER("Assembler (Generate Programming Files)", "Assemble"),
    TIMING("Timing Analysis", "Analyze Timing of"),
    COMPILE("Compile Design", "Compile"),
    COMPILE_AND_PROGRAM("Compile Design and Program Device", "Compile and Program"),
    PROGRAM("Program Device", "Program");

    val programs: Boolean get() = this == PROGRAM || this == COMPILE_AND_PROGRAM
    val compiles: Boolean get() = this != PROGRAM

    override fun toString(): String = title

    companion object {
        fun of(name: String?): QuartusFlow = entries.firstOrNull { it.name == name } ?: COMPILE
    }
}

/** Which file is programmed. */
enum class QuartusImage(val title: String, val extension: String?) {
    SOF("SRAM Object File (.sof): configures the FPGA until it loses power", "sof"),
    POF("Programmer Object File (.pof): written to the device's flash", "pof"),
    FILE("Other file", null);

    override fun toString(): String = title

    companion object {
        fun of(name: String?): QuartusImage = entries.firstOrNull { it.name == name } ?: SOF
    }
}

/** How the device is programmed: with which cable, which file, and how. Blank means "work it out". */
data class QuartusProgramming(
    val cable: String = "",
    val image: QuartusImage = QuartusImage.SOF,
    val file: String = "",
    val mode: String = QuartusProgrammer.DEFAULT_MODE,
    val operation: String = "",
    val deviceIndex: String = "",
    val options: String = "",
)

/** The Quartus Programmer, `quartus_pgm`: finds cables and the devices on them, and builds its command line. */
object QuartusProgrammer {
    const val TOOL = "quartus_pgm"
    const val DEFAULT_MODE = "JTAG"
    val MODES = listOf("JTAG", "AS", "PS", "SD")

    data class Cable(val index: Int, val name: String)

    /** A device in the JTAG chain of a cable, by its position. [name] lists the parts that share the [id]. */
    data class Device(val index: Int, val id: String, val name: String)

    private val CABLE = Regex("""^(\d+)\)\s+(\S.*?)\s*$""")
    private val DEVICE = Regex("""^\s+([0-9A-Fa-f]{8})\s+(\S.*?)\s*$""")

    /** The cables in the output of `quartus_pgm -l`, such as `1) USB-Blaster [USB-0]`. */
    fun parseCables(output: String): List<Cable> =
        output.lineSequence().mapNotNull { CABLE.matchEntire(it) }.map { Cable(it.groupValues[1].toInt(), it.groupValues[2]) }.toList()

    /** The devices in the output of `quartus_pgm -c <cable> -a`, such as `  031050DD   10M50DA(.|ES)/10M50DC`. */
    fun parseDevices(output: String): List<Device> =
        output.lineSequence().mapNotNull { DEVICE.matchEntire(it) }
            .mapIndexed { index, match -> Device(index + 1, match.groupValues[1], match.groupValues[2]) }.toList()

    /** The cables that are connected. Runs the programmer, which takes a second or two. */
    @Throws(ExecutionException::class)
    fun cables(installation: QuartusInstallation, directory: Path): List<Cable> = parseCables(run(installation, directory, "-l"))

    @Throws(ExecutionException::class)
    fun devices(installation: QuartusInstallation, directory: Path, cable: String): List<Device> =
        parseDevices(run(installation, directory, "-c", cable, "-a"))

    private fun run(installation: QuartusInstallation, directory: Path, vararg arguments: String): String {
        val tool = installation.tool(TOOL) ?: throw ExecutionException("${installation.displayName} has no $TOOL.")
        val output = CapturingProcessHandler(installation.commandLine(tool, directory).withParameters(*arguments)).runProcess(60_000)
        if (output.isTimeout) throw ExecutionException("$TOOL ${arguments.joinToString(" ")} did not answer.")
        return output.stdout
    }

    /**
     * The position in the chain of the device to program, when the chain has more than one: the device
     * whose name fits [part], the project's device, or else the only one that is not the processor of an
     * SoC. Null for a chain of one, which needs no position, and when the choice is not clear.
     */
    fun deviceIndex(devices: List<Device>, part: String?): Int? {
        if (devices.size < 2) return null
        val wanted = part.orEmpty().uppercase()
        val fitting = devices.filter { device ->
            wanted.isNotEmpty() && device.name.uppercase().split('/').any { alternative ->
                // `10M50DA(.|ES)` names 10M50DA with any suffix.
                val prefix = alternative.substringBefore('(').trim()
                prefix.length >= 4 && wanted.startsWith(prefix)
            }
        }
        fitting.singleOrNull()?.let { return it.index }
        return devices.filter { !it.name.uppercase().startsWith("SOC") }.singleOrNull()?.index
    }

    /** The operation for [file] when none is given: program, and for what stays in flash also verify. */
    fun defaultOperation(file: Path): String = if (file.extension.lowercase() == "sof") "p" else "pv"

    /** The arguments that program [file] over [cable]. */
    fun arguments(cable: String, mode: String, operation: String, file: Path, deviceIndex: Int?, options: String): List<String> =
        listOf("-c", cable, "-m", mode.ifBlank { DEFAULT_MODE }) + ParametersListUtil.parse(options) +
            listOf("-o", "$operation;$file" + (deviceIndex?.let { "@$it" } ?: ""))
}

/** A step of a run: a tool to start once the steps before it have succeeded. */
internal class QuartusStep(
    val title: String,
    /** Builds the command line when the step is due. [report] prints a line about what it found out. */
    val commandLine: (report: (String) -> Unit) -> GeneralCommandLine,
)

/** Builds the steps of a flow. */
internal object QuartusSteps {
    @Throws(ExecutionException::class)
    fun create(
        installation: QuartusInstallation, revision: QuartusRevision, flow: QuartusFlow, programming: QuartusProgramming,
    ): List<QuartusStep> {
        val project = revision.project
        val design = listOf(project.name, "-c", revision.name)

        fun tool(name: String, vararg arguments: String): QuartusStep {
            val executable = installation.tool(name) ?: throw ExecutionException("${installation.displayName} has no $name.")
            return QuartusStep(name) { installation.commandLine(executable, project.directory).withParameters(*arguments).withParameters(design) }
        }

        // The Pro Edition synthesizes with quartus_syn, the others with quartus_map.
        val synthesis = if (installation.isPro) "quartus_syn" else "quartus_map"
        val compile = when (flow) {
            QuartusFlow.ANALYSIS -> listOf(tool(synthesis, "--analysis_and_elaboration"))
            QuartusFlow.SYNTHESIS -> listOf(tool(synthesis))
            QuartusFlow.FITTER -> listOf(tool("quartus_fit"))
            QuartusFlow.ASSEMBLER -> listOf(tool("quartus_asm"))
            QuartusFlow.TIMING -> listOf(tool("quartus_sta"))
            // The shell's flow also generates IP, skips what is up to date and writes the flow report.
            QuartusFlow.COMPILE, QuartusFlow.COMPILE_AND_PROGRAM -> listOf(tool(Quartus.SHELL, "--flow", "compile"))
            QuartusFlow.PROGRAM -> emptyList()
        }
        return if (flow.programs) compile + program(installation, revision, programming) else compile
    }

    private fun program(installation: QuartusInstallation, revision: QuartusRevision, programming: QuartusProgramming): QuartusStep {
        val executable = installation.tool(QuartusProgrammer.TOOL)
            ?: throw ExecutionException("${installation.displayName} has no ${QuartusProgrammer.TOOL}.")
        val directory = revision.project.directory
        return QuartusStep(QuartusProgrammer.TOOL) { report ->
            val file = programmingFile(revision, programming)
            val cable = programming.cable.ifBlank {
                val cables = QuartusProgrammer.cables(installation, directory)
                val first = cables.firstOrNull() ?: throw ExecutionException(
                    "No programming cable was found. Connect the board, switch it on and check that the driver of its " +
                        "USB-Blaster is installed; Quartus has it in ${installation.quartusDirectory?.resolve("drivers")}."
                )
                if (cables.size > 1) {
                    report("Several cables are connected: ${cables.joinToString { it.name }}. Using ${first.name}; choose one in the run configuration.")
                }
                first.name
            }
            val index = programming.deviceIndex.trim().toIntOrNull() ?: run {
                val devices = runCatching { QuartusProgrammer.devices(installation, directory, cable) }.getOrDefault(emptyList())
                val chosen = QuartusProgrammer.deviceIndex(devices, revision.device)
                if (devices.size > 1 && chosen == null) {
                    throw ExecutionException(
                        "The cable $cable has ${devices.size} devices: ${devices.joinToString { "${it.index}) ${it.name}" }}. " +
                            "Set the one to program as the device index in the run configuration."
                    )
                }
                chosen
            }
            val operation = programming.operation.ifBlank { QuartusProgrammer.defaultOperation(file) }
            report("Programming ${file.name} over $cable" + (index?.let { ", device $it of the chain" } ?: ""))
            installation.commandLine(executable, directory)
                .withParameters(QuartusProgrammer.arguments(cable, programming.mode, operation, file, index, programming.options))
        }
    }

    /** The file to program: the one of the chosen kind in the output directory, or the one named. */
    @Throws(ExecutionException::class)
    fun programmingFile(revision: QuartusRevision, programming: QuartusProgramming): Path {
        val file = when (programming.image) {
            QuartusImage.FILE -> programming.file.takeIf { it.isNotBlank() }
                ?.let { runCatching { revision.project.directory.resolve(it).normalize() }.getOrNull() }
                ?: throw ExecutionException("No programming file is set in the run configuration.")
            else -> revision.output(programming.image.extension!!)
        }
        if (!Files.isRegularFile(file)) {
            throw ExecutionException("The programming file $file does not exist. Compile the design first.")
        }
        return file
    }
}

/** The console colours of Quartus's messages. Errors are standard error, information standard output. */
object QuartusOutput {
    val WARNING: Key<Any> = Key.create("verilogToolbox.quartus.warning")
    val CRITICAL_WARNING: Key<Any> = Key.create("verilogToolbox.quartus.criticalWarning")
    val EXTRA_INFO: Key<Any> = Key.create("verilogToolbox.quartus.extraInfo")

    init {
        ConsoleViewContentType.registerNewConsoleViewType(WARNING, ConsoleViewContentType.LOG_WARNING_OUTPUT)
        // A critical warning usually has to be fixed; it shows as a warning in bold.
        ConsoleViewContentType.registerNewConsoleViewType(
            CRITICAL_WARNING,
            object : ConsoleViewContentType("QUARTUS_CRITICAL_WARNING", ConsoleViewContentType.LOG_WARNING_OUTPUT_KEY) {
                override fun getAttributes(): TextAttributes =
                    ConsoleViewContentType.LOG_WARNING_OUTPUT.attributes.clone().apply { fontType = Font.BOLD }
            },
        )
        ConsoleViewContentType.registerNewConsoleViewType(EXTRA_INFO, ConsoleViewContentType.LOG_DEBUG_OUTPUT)
    }

    fun of(severity: QuartusSeverity): Key<*> = when (severity) {
        QuartusSeverity.ERROR -> ProcessOutputTypes.STDERR
        QuartusSeverity.CRITICAL_WARNING -> CRITICAL_WARNING
        QuartusSeverity.WARNING -> WARNING
        QuartusSeverity.INFO -> ProcessOutputTypes.STDOUT
        QuartusSeverity.EXTRA_INFO -> EXTRA_INFO
    }
}

/**
 * Runs the steps of a flow one after the other, colours the messages by their severity, leaves out the
 * legal notice every tool starts with, collects the messages, and ends with what the reports say: the
 * resources, whether timing is met, and the errors there were.
 */
internal class QuartusProcessHandler(
    private val project: Project?,
    private val revision: QuartusRevision,
    private val flow: QuartusFlow,
    private val steps: List<QuartusStep>,
    private val header: String,
) : ProcessHandler() {
    @Volatile
    private var process: OSProcessHandler? = null

    @Volatile
    private var stopped = false

    private val collected = java.util.Collections.synchronizedList(mutableListOf<QuartusMessage>())

    /** The messages of the run so far, in the order they were printed. */
    val messages: List<QuartusMessage> get() = synchronized(collected) { collected.toList() }

    /** Text of a line that has not ended yet, per stream. */
    private val pending = HashMap<Key<*>, StringBuilder>()
    private var inLegalNotice = false
    private val started = System.currentTimeMillis()
    private val finished = java.util.concurrent.atomic.AtomicBoolean()

    override fun startNotify() {
        super.startNotify()
        notifyTextAvailable(header + "\n", ProcessOutputTypes.SYSTEM)
        project?.let { QuartusRuns.getInstance(it).started(flow) }
        ApplicationManager.getApplication().executeOnPooledThread { next(0) }
    }

    private fun next(index: Int) {
        if (stopped) return finish(1)
        val step = steps.getOrNull(index) ?: return finish(0)
        val handler = try {
            OSProcessHandler(step.commandLine { notifyTextAvailable(it + "\n", ProcessOutputTypes.SYSTEM) })
        } catch (e: Exception) {
            if (e !is IOException && e !is ExecutionException) throw e
            val message = QuartusMessage(QuartusSeverity.ERROR, null, e.message ?: e.toString())
            collected += message
            notifyTextAvailable(message.text + "\n", ProcessOutputTypes.STDERR)
            return finish(1)
        }
        process = handler
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = text(event.text, outputType)

            override fun processTerminated(event: ProcessEvent) {
                flush()
                // Each tool's process ends on a thread of its own, so the next one may be started from here.
                if (event.exitCode == 0 && !stopped) next(index + 1) else finish(event.exitCode)
            }
        })
        if (stopped) handler.destroyProcess()
        handler.startNotify()
    }

    /** Splits the output into lines, which is what a message is, and passes each on. */
    @Synchronized
    private fun text(text: String, outputType: Key<*>) {
        if (outputType === ProcessOutputTypes.SYSTEM) return // The command line, which the tool prints itself.
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
        if (QuartusConsole.isNoise(line)) return
        val message = QuartusMessage.parse(line)
        if (message != null && message.code == null) {
            if (message.depth == 0 && message.text.startsWith(RUNNING)) {
                val tool = message.text.removePrefix(RUNNING)
                if (tool != "Shell") project?.let { QuartusRuns.getInstance(it).stage(tool) }
            }
            // The notice runs from the copyright line to the line that says when processing started.
            if (message.text.startsWith("Copyright (C)")) inLegalNotice = true
            else if (message.text.startsWith("Processing started")) inLegalNotice = false
            if (inLegalNotice) return
        }
        if (message != null && QuartusReports.isReported(message)) collected += message
        notifyTextAvailable(line + "\n", message?.let { QuartusOutput.of(it.severity) } ?: outputType)
    }

    /** Ends the run. Stopping it and the end of a tool may both get here, of which the first counts. */
    private fun finish(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        if (!stopped) {
            try {
                QuartusConsole.conclusion(revision, flow, exitCode, messages, System.currentTimeMillis() - started)
                    .forEach { (text, type) -> notifyTextAvailable(text + "\n", type) }
            } catch (e: Exception) {
                // A report that cannot be read must not keep the run from ending.
                notifyTextAvailable("The reports could not be read: ${e.message}\n", ProcessOutputTypes.SYSTEM)
            }
        }
        project?.let { QuartusRuns.getInstance(it).finished(revision, flow, messages) }
        notifyProcessTerminated(exitCode)
    }

    private companion object {
        /** How each tool announces itself, as in `Info: Running Quartus Prime Fitter`. */
        const val RUNNING = "Running Quartus Prime "
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
internal object QuartusConsole {
    private const val MAX_LISTED = 20

    /** A line that says nothing about the design: the note of an allocator that every tool starts with on Windows. */
    fun isNoise(line: String): Boolean = line.startsWith("TBBmalloc:")

    /**
     * The lines a run ends with, each with the stream it is printed on: after a failure the errors once
     * more, after a compilation the resources and timing from the reports, and where the output is.
     */
    fun conclusion(
        revision: QuartusRevision, flow: QuartusFlow, exitCode: Int, messages: List<QuartusMessage>, elapsedMs: Long,
    ): List<Pair<String, Key<*>>> = buildList {
        val out = ProcessOutputTypes.STDOUT
        val errors = messages.filter { it.severity == QuartusSeverity.ERROR && it.depth == 0 }
        val critical = messages.count { it.severity == QuartusSeverity.CRITICAL_WARNING && it.depth == 0 }
        val warnings = messages.count { it.severity == QuartusSeverity.WARNING && it.depth == 0 }
        val counts = listOfNotNull(
            critical.takeIf { it > 0 }?.let { YosysSynthesis.plural(it, "critical warning") },
            warnings.takeIf { it > 0 }?.let { YosysSynthesis.plural(it, "warning") },
        ).joinToString(", ")
        add("" to out)
        if (exitCode != 0) {
            add("${flow.title} of ${revision.name} failed after ${elapsed(elapsedMs)}" +
                (if (errors.isEmpty()) " (exit code $exitCode)." else " with ${YosysSynthesis.plural(errors.size, "error")}:") to ProcessOutputTypes.STDERR)
            for (error in errors.take(MAX_LISTED)) {
                val location = error.file?.let { " File: $it Line: ${error.line ?: 1}" }.orEmpty()
                add("  " + listOfNotNull(error.code?.let { "($it)" }, error.text).joinToString(" ") + location to ProcessOutputTypes.STDERR)
            }
            if (errors.size > MAX_LISTED) add("  … and ${errors.size - MAX_LISTED} more, see above." to ProcessOutputTypes.STDERR)
            return@buildList
        }
        if (flow == QuartusFlow.PROGRAM) {
            add("${revision.name} is programmed." to out)
            return@buildList
        }
        val target = listOfNotNull(revision.family, revision.device).joinToString(" ").ifEmpty { null }
        add("${flow.title} of ${revision.name}" + (target?.let { " for $it" } ?: "") + " finished in ${elapsed(elapsedMs)}" +
            (if (counts.isEmpty()) "." else ", with $counts.") to out)
        if (flow == QuartusFlow.ANALYSIS) return@buildList

        val compilation = QuartusCompilation(revision)
        val usage = compilation.resources.filter { (name, value) -> !name.startsWith(" ") && value.firstOrNull()?.isDigit() == true && !value.startsWith("0") }
        if (usage.isNotEmpty()) add("  " + usage.joinToString("   ") { (name, value) -> "$name: $value" } to out)
        if (flow in TIMED && compilation.slacks.isNotEmpty()) {
            val failing = compilation.failingSlacks
            if (failing.isEmpty()) {
                val worst = compilation.worstSlacks.filter { it.check == "Setup" || it.check == "Hold" }
                    .joinToString(", ") { "${it.check.lowercase()} slack ${it.slack} ns" }
                val fmax = compilation.worstFmax.joinToString(", ") { "${it.clock} ${it.restricted} MHz" }
                add("  Timing is met" + (if (worst.isEmpty()) "." else ": $worst.") + (if (fmax.isEmpty()) "" else " Fmax: $fmax.") to out)
            } else {
                add("  Timing is NOT met:" to ProcessOutputTypes.STDERR)
                for (slack in failing.sortedBy { it.slack }.take(MAX_LISTED)) {
                    add("    ${slack.check} slack ${slack.slack} ns for clock '${slack.clock}' (${slack.corner}), " +
                        "total negative slack ${slack.totalNegativeSlack} ns" to ProcessOutputTypes.STDERR)
                }
            }
        }
        compilation.programmingFiles.firstOrNull()?.takeIf { flow in ASSEMBLED }?.let { add("  Programming file: $it" to out) }
        compilation.reports.firstOrNull()?.let { (_, file) -> add("  Report: $file" to out) }
        if (flow == QuartusFlow.COMPILE_AND_PROGRAM) add("${revision.name} is programmed." to out)
    }

    private val TIMED = setOf(QuartusFlow.TIMING, QuartusFlow.COMPILE, QuartusFlow.COMPILE_AND_PROGRAM)
    private val ASSEMBLED = setOf(QuartusFlow.ASSEMBLER, QuartusFlow.COMPILE, QuartusFlow.COMPILE_AND_PROGRAM)

    /** `95000` as `1:35 min`, `34000` as `34 s`. */
    fun elapsed(milliseconds: Long): String {
        val seconds = (milliseconds + 500) / 1000
        return if (seconds < 60) "$seconds s" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} min"
    }
}

/**
 * Links the locations of Quartus messages to the source: `File: C:/project/top.sv Line: 9` at the end of
 * a message, and the files a run names by their full path, such as a report or the programming file.
 */
class QuartusConsoleFilter(private val project: Project, private val directory: Path) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (line.length > MAX_LINE_LENGTH) return null
        val lineStart = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()
        LOCATION.find(line)?.let { match ->
            val file = find(match.groupValues[1]) ?: return@let
            val lineNumber = (match.groupValues[2].toIntOrNull() ?: 1) - 1
            items += Filter.ResultItem(
                lineStart + match.range.first + 1, lineStart + match.range.last + 1,
                OpenFileHyperlinkInfo(project, file, maxOf(lineNumber, 0)),
            )
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
        val LOCATION = Regex(""" File: (.+) Line: (\d+)\s*$""")

        /** The lines of the conclusion that name a file. */
        val OUTPUT = Regex("""^\s*(Programming file|Report): (.+)$""")
    }
}

/** The Quartus runs of an IDE project: whether one is under way, and what the last one said. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class QuartusRuns(private val project: Project) {
    /** The flow that is running, if any. */
    @Volatile
    var running: QuartusFlow? = null
        private set

    private class Last(val settingsFile: Path, val flow: QuartusFlow, val messages: List<QuartusMessage>)

    @Volatile
    private var last: Last? = null

    /** The tool of the flow that is running, such as `Fitter`, as far as it has said. */
    @Volatile
    var stage: String? = null
        private set

    internal fun stage(name: String) {
        stage = name
        QuartusProjectService.getInstance(project).changed()
    }

    internal fun started(flow: QuartusFlow) {
        stage = null
        running = flow
        QuartusProjectService.getInstance(project).changed()
    }

    internal fun finished(revision: QuartusRevision, flow: QuartusFlow, messages: List<QuartusMessage>) {
        running = null
        stage = null
        // Programming says little; what it says replaces the compilation's messages only when it failed.
        if (flow.compiles || messages.any { it.severity == QuartusSeverity.ERROR }) last = Last(revision.settingsFile, flow, messages)
        QuartusProjectService.getInstance(project).outputChanged()
    }

    /**
     * The last run of [revision] in this session: its flow and its messages. Null for both before one,
     * when the reports of the last compilation are all there is to show, whoever ran it.
     */
    fun lastRun(revision: QuartusRevision): Pair<QuartusFlow?, List<QuartusMessage>?> =
        last?.takeIf { it.settingsFile == revision.settingsFile }?.let { it.flow to it.messages } ?: (null to null)

    companion object {
        fun getInstance(project: Project): QuartusRuns = project.getService(QuartusRuns::class.java)
    }
}
