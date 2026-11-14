package com.jotrorox.verilogtoolbox

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** A waveform viewer that "Open Waveform" can launch. */
enum class WaveformViewer(
    val displayName: String,
    binary: String,
    private val layoutExtension: String,
    /** The macOS application bundle, for an installation that puts no command on PATH. */
    val macApplication: String?,
) {
    GTKWAVE("GTKWave", "gtkwave", "gtkw", "gtkwave.app") {
        /**
         * GTKWave restores the save file and File | Write Save File writes it back without asking. That
         * holds for a save file that does not exist yet too, which only causes a warning.
         */
        override fun arguments(dump: Path, layout: Path): List<String> = listOf(dump.toString(), layout.toString())
    },
    SURFER("Surfer", "surfer", "surf.ron", null) {
        /**
         * Surfer saves back to a state file only if it could load it, so the first layout has to be saved
         * to this path by hand; a missing file would only log an error.
         */
        override fun arguments(dump: Path, layout: Path): List<String> =
            listOf(dump.toString()) + if (Files.isRegularFile(layout)) listOf("--state-file", layout.toString()) else emptyList()
    };

    val binary: String = if (SystemInfo.isWindows) "$binary.exe" else binary

    /** The viewer's arguments for showing [dump] with the signals saved in [layout]. */
    abstract fun arguments(dump: Path, layout: Path): List<String>

    /** The layout of a testbench lives next to its source, named after the module, so it can be shared. */
    fun layoutFile(testbenchFile: Path, topModule: String?): Path {
        val name = topModule?.takeIf { it.isNotBlank() }?.let { FileUtil.sanitizeFileName(it) }
            ?: testbenchFile.nameWithoutExtension
        return testbenchFile.resolveSibling("$name.$layoutExtension")
    }

    override fun toString(): String = displayName
}

/** Finds the waveform a simulation dumps and opens it in GTKWave or Surfer. */
object Waveforms {
    /** What vvp prints when `$dumpfile`/`$dumpvars` opens the dump, whatever its format. */
    private val DUMP_OPENED = Regex("""^(?:VCD|FST|LXT2?) info: dumpfile (.+) opened for output\.$""")

    /** Viewers started here that are still open, by the dump they show. */
    private val running = HashMap<Path, ProcessHandler>()

    /** The range of the dump file's name in a line of simulation output, if the line announces one. */
    internal fun dumpFileRange(line: String): IntRange? = DUMP_OPENED.find(line.trimEnd())?.groups?.get(1)?.range

    internal fun resolve(workingDirectory: Path, dumpFile: String): Path? =
        try {
            workingDirectory.resolve(dumpFile).normalize()
        } catch (_: InvalidPathException) {
            null
        }

    /** On macOS a viewer can be an application bundle, which is started through `open`. */
    internal fun commandLine(executable: Path, arguments: List<String>): GeneralCommandLine =
        if (executable.name.endsWith(".app") && Files.isDirectory(executable)) {
            GeneralCommandLine("open", "-n", "-a", executable.toString(), "--args").withParameters(arguments)
        } else {
            GeneralCommandLine(executable.toString()).withParameters(arguments)
        }

    /** Opens [dump], written by a run of [configuration], with the layout saved for its testbench. */
    fun open(configuration: IcarusRunConfiguration, dump: Path?) {
        val project = configuration.project
        ApplicationManager.getApplication().executeOnPooledThread {
            if (dump == null) {
                return@executeOnPooledThread notify(
                    project, "No waveform was dumped",
                    "The simulation did not open a dump file. Add <code>\$dumpfile(\"tb.vcd\"); \$dumpvars;</code> " +
                        "to an <code>initial</code> block of the testbench and run it again.",
                    NotificationType.WARNING,
                )
            }
            if (!Files.isRegularFile(dump)) {
                return@executeOnPooledThread notify(
                    project, "Waveform not found", "'$dump' does not exist.", NotificationType.WARNING,
                )
            }
            val (viewer, executable) = ToolchainSettings.getInstance().findWaveformViewer()
                ?: return@executeOnPooledThread notifyMissingViewer(project)
            launch(project, viewer, executable, dump, configuration)
        }
    }

    private fun launch(
        project: Project, viewer: WaveformViewer, executable: Path, dump: Path, configuration: IcarusRunConfiguration,
    ) = synchronized(running) {
        if (running[dump]?.isProcessTerminated == false) {
            return notify(
                project, "${viewer.displayName} is already open",
                "It is showing '${dump.name}'. Reload the waveform there to see the latest run.",
                NotificationType.INFORMATION,
            )
        }
        val layout = viewer.layoutFile(configuration.testbenchFile ?: dump, configuration.resolveTopModule())
        val handler = try {
            OSProcessHandler(
                commandLine(executable, viewer.arguments(dump, layout)).withWorkingDirectory(dump.parent)
            )
        } catch (e: ExecutionException) {
            return notify(
                project, "${viewer.displayName} could not be started", e.message ?: e.toString(), NotificationType.ERROR,
            )
        }
        // The output has to be read, or a viewer that logs a lot blocks on a full pipe.
        val lastLines = ArrayDeque<String>()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM || event.text.isBlank()) return
                synchronized(lastLines) {
                    lastLines.addLast(event.text.trimEnd())
                    if (lastLines.size > REPORTED_LINES) lastLines.removeFirst()
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                synchronized(running) { running.remove(dump, handler) }
                if (event.exitCode != 0) {
                    val output = synchronized(lastLines) { lastLines.joinToString("<br>") }
                    notify(
                        project, "${viewer.displayName} exited with code ${event.exitCode}", output, NotificationType.ERROR,
                    )
                }
            }
        })
        running[dump] = handler
        handler.startNotify()
    }

    private fun notifyMissingViewer(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                "No waveform viewer was found",
                "Install GTKWave or Surfer, or set its path in Settings | Tools | Verilog Toolbox.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            })
            .notify(project)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(title, content, type).notify(project)
    }

    private const val REPORTED_LINES = 5
}

/** Remembers the dump file a simulation opens, as announced in its output. */
class WaveformDumpListener(private val workingDirectory: Path) : ProcessListener {
    @Volatile
    var dumpFile: Path? = null
        private set

    private val line = StringBuilder()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (outputType == ProcessOutputTypes.SYSTEM) return
        // Output arrives in chunks that need not end at a line break.
        synchronized(line) {
            line.append(event.text)
            while (true) {
                val end = line.indexOf("\n")
                if (end < 0) break
                val text = line.substring(0, end)
                line.delete(0, end + 1)
                Waveforms.dumpFileRange(text)?.let { dumpFile = Waveforms.resolve(workingDirectory, text.substring(it)) }
            }
            if (line.length > MAX_LINE_LENGTH) line.setLength(0)
        }
    }

    private companion object {
        const val MAX_LINE_LENGTH = 10_000
    }
}

/** Turns the dump file in `VCD info: dumpfile tb.vcd opened for output.` into a link that opens the viewer. */
class WaveformConsoleFilter(private val configuration: IcarusRunConfiguration, private val workingDirectory: Path) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val range = Waveforms.dumpFileRange(line) ?: return null
        val dump = Waveforms.resolve(workingDirectory, line.substring(range)) ?: return null
        val lineStart = entireLength - line.length
        return Filter.Result(
            lineStart + range.first, lineStart + range.last + 1, HyperlinkInfo { Waveforms.open(configuration, dump) },
        )
    }
}

/** "Open Waveform" in the toolbar of a simulation's console. */
class OpenWaveformAction(private val configuration: IcarusRunConfiguration, private val dumps: WaveformDumpListener) :
    DumbAwareAction("Open Waveform", "Open the waveform dumped by this simulation in GTKWave or Surfer", ICON) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = Waveforms.open(configuration, dumps.dumpFile)

    private companion object {
        val ICON = IconLoader.getIcon("/icons/waveform.svg", OpenWaveformAction::class.java)
    }
}
