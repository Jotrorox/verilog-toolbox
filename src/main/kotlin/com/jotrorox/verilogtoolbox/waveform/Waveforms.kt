package com.jotrorox.verilogtoolbox.waveform

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.util.ExecUtil
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
import com.jotrorox.verilogtoolbox.icarus.IcarusRunConfiguration
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
         * Surfer saves back to a state file only if it could load it, and a missing one only logs an error.
         * Until there is one, [Waveforms.surferSeed] makes Surfer write it.
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
    private val running = HashMap<Path, OpenViewer>()

    /** Whether a Surfer executable knows `--wcp-initiate`, which Surfer 0.4 introduced; asked once. */
    private val surferTakesCommands = ConcurrentHashMap<Path, Boolean>()

    /** The range of the dump file's name in a line of simulation output, if the line announces one. */
    internal fun dumpFileRange(line: String): IntRange? = DUMP_OPENED.find(line.trimEnd())?.groups?.get(1)?.range

    internal fun resolve(workingDirectory: Path, dumpFile: String): Path? =
        try {
            workingDirectory.resolve(dumpFile).normalize()
        } catch (_: InvalidPathException) {
            null
        }

    /**
     * On macOS a viewer can be an application bundle, which is started through `open`. `-W` makes `open`
     * last as long as the application, so that it stands for the viewer like a command would.
     */
    internal fun commandLine(executable: Path, arguments: List<String>): GeneralCommandLine =
        if (executable.name.endsWith(".app") && Files.isDirectory(executable)) {
            GeneralCommandLine("open", "-n", "-W", "-a", executable.toString(), "--args").withParameters(arguments)
        } else {
            GeneralCommandLine(executable.toString()).withParameters(arguments)
        }

    /**
     * The commands that give Surfer its first layout: the signals of the testbench, saved to [layout] at
     * once. Surfer then knows the file as its state file and saves later changes to it. Null if the path
     * cannot be written in a command file, where `;` and `#` end a command.
     */
    internal fun surferSeed(layout: Path, topModule: String?): String? {
        val path = layout.toAbsolutePath().toString()
        if (path.any { it == ';' || it == '#' || it == '\n' || it == '\r' } || path != path.trim()) return null
        // A scope that the dump does not have is only warned about.
        val scope = topModule?.takeIf { SIMPLE_IDENTIFIER.matches(it) }
        return listOfNotNull(scope?.let { "scope_add $it" }, "save_state_as $path").joinToString("\n", postfix = "\n")
    }

    /** Opens [dump], written by a run of [configuration], with the layout saved for its testbench. */
    fun open(configuration: IcarusRunConfiguration, dump: Path?) {
        val project = configuration.project
        ApplicationManager.getApplication().executeOnPooledThread {
            if (dump == null) {
                return@executeOnPooledThread notify(
                    project, "No waveform was dumped",
                    "The simulation did not open a dump file. Add <code>\$dumpfile(\"tb.vcd\"); \$dumpvars;</code> " +
                        "to an <code>initial</code> block of the testbench and run it again. " +
                        "A testbench made with Generate Testbench on a module already has both.",
                    NotificationType.WARNING,
                )
            }
            if (!Files.isRegularFile(dump)) {
                return@executeOnPooledThread notify(
                    project, "Waveform not found", "'$dump' does not exist.", NotificationType.WARNING,
                )
            }
            val open = openViewer(dump)
            if (open != null) {
                // A viewer that is still starting is not taken for one that does not listen.
                if (open.reload(raise = true) || open.isStarting) return@executeOnPooledThread
                // Asked for by hand, so the viewer is replaced: what its layout file has is shown again.
                stop(open)
            }
            start(configuration, dump)
        }
    }

    /**
     * Makes a viewer that shows [dump] show what a simulation has just written to it. Does nothing, and
     * costs nothing, if no viewer started here shows it. [WaveformDumpListener] calls this.
     */
    fun reloadIfOpen(dump: Path) {
        val open = openViewer(dump) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (open.reload(raise = false) || open.handler.isProcessTerminated) return@executeOnPooledThread
            // Not restarted unasked: that would drop what was added to the layout but not saved.
            val viewer = open.viewer.displayName
            NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                .createNotification(
                    "$viewer shows the previous run",
                    "It could not be told to reload '${dump.name}'. Restarting it shows the new waveform with the " +
                        "saved layout; changes to the layout that were not saved are lost.",
                    NotificationType.WARNING,
                )
                .addAction(NotificationAction.createSimpleExpiring("Restart $viewer") {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        stop(open)
                        start(open.configuration, dump)
                    }
                })
                .notify(open.configuration.project)
        }
    }

    private fun openViewer(dump: Path): OpenViewer? =
        synchronized(running) { running[dump]?.takeUnless { it.handler.isProcessTerminated } }

    /** The viewers started here that are still open, the most recently started first. */
    internal fun openViewers(): List<OpenViewer> =
        synchronized(running) { running.values.filter { !it.handler.isProcessTerminated }.sortedByDescending { it.started } }

    private fun stop(open: OpenViewer) {
        open.stopped = true
        synchronized(running) { running.remove(open.dump, open) }
        open.handler.destroyProcess()
        open.handler.waitFor(STOP_TIMEOUT_MILLIS)
    }

    private fun start(configuration: IcarusRunConfiguration, dump: Path) {
        val project = configuration.project
        val (viewer, executable) = ToolchainSettings.getInstance().findWaveformViewer()
            ?: return notifyMissingViewer(project)
        val topModule = configuration.resolveTopModule()
        val layout = viewer.layoutFile(configuration.testbenchFile ?: dump, topModule)
        val control = try {
            control(viewer, executable)
        } catch (_: IOException) {
            null // The viewer still shows the dump, it only cannot be told to reload it.
        }
        val arguments = control?.arguments.orEmpty() + viewer.arguments(dump, layout) +
            if (viewer == WaveformViewer.SURFER && !Files.isRegularFile(layout)) seedArguments(layout, topModule) else emptyList()
        synchronized(running) {
            // Started in the meantime by another request.
            if (running[dump]?.handler?.isProcessTerminated == false) return control?.close() ?: Unit
            val handler = try {
                OSProcessHandler(
                    commandLine(executable, arguments).withWorkingDirectory(dump.parent)
                        .withEnvironment(control?.environment.orEmpty())
                )
            } catch (e: ExecutionException) {
                control?.close()
                return notify(
                    project, "${viewer.displayName} could not be started", e.message ?: e.toString(), NotificationType.ERROR,
                )
            }
            val open = OpenViewer(viewer, dump, configuration, handler, control)
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
                    synchronized(running) { running.remove(dump, open) }
                    control?.close()
                    if (event.exitCode != 0 && !open.stopped) {
                        val output = synchronized(lastLines) { lastLines.joinToString("<br>") }
                        notify(
                            project, "${viewer.displayName} exited with code ${event.exitCode}", output, NotificationType.ERROR,
                        )
                    }
                }
            })
            running[dump] = open
            handler.startNotify()
        }
    }

    @Throws(IOException::class)
    private fun control(viewer: WaveformViewer, executable: Path): ViewerControl? = when (viewer) {
        WaveformViewer.GTKWAVE -> GtkwaveControl()
        WaveformViewer.SURFER -> if (surferTakesCommands.computeIfAbsent(executable, ::takesCommands)) SurferControl() else null
    }

    private fun takesCommands(surfer: Path): Boolean =
        try {
            "--wcp-initiate" in ExecUtil.execAndGetOutput(GeneralCommandLine(surfer.toString(), "--help"), HELP_TIMEOUT_MILLIS).stdout
        } catch (_: ExecutionException) {
            false
        }

    private fun seedArguments(layout: Path, topModule: String?): List<String> {
        val seed = surferSeed(layout, topModule) ?: return emptyList()
        return try {
            val file = FileUtil.createTempFile("surfer-layout", ".cmd", true).toPath()
            Files.writeString(file, seed)
            listOf("--command-file", file.toString())
        } catch (_: IOException) {
            emptyList() // The layout then has to be saved by hand, as it had to before.
        }
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

    private val SIMPLE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_$]*""")
    private const val REPORTED_LINES = 5
    private const val STOP_TIMEOUT_MILLIS = 3_000L
    private const val HELP_TIMEOUT_MILLIS = 10_000
}

/** A viewer started by [Waveforms] and the dump it shows. */
internal class OpenViewer(
    val viewer: WaveformViewer,
    val dump: Path,
    val configuration: IcarusRunConfiguration,
    val handler: ProcessHandler,
    private val control: ViewerControl?,
) {
    val started = System.nanoTime()

    /** Set before the viewer is stopped on purpose, which is no failure to report. */
    @Volatile
    var stopped = false

    val isStarting: Boolean
        get() = System.nanoTime() - started < TimeUnit.SECONDS.toNanos(STARTING_SECONDS)

    /** Whether the viewer was told to load the dump again, see [ViewerControl.reload]. */
    fun reload(raise: Boolean): Boolean = !handler.isProcessTerminated && control?.reload(raise) == true

    /** False for a viewer that cannot be told anything, such as a Surfer older than 0.4. */
    val takesCommands: Boolean
        get() = control != null

    /** Whether the viewer was told to show [signals], see [ViewerControl.addSignals]. */
    fun addSignals(signals: List<String>): Boolean = !handler.isProcessTerminated && control?.addSignals(signals) == true

    private companion object {
        const val STARTING_SECONDS = 10L
    }
}

/** The way a running viewer is told to load its dump again. */
internal interface ViewerControl : Closeable {
    /** What the viewer has to be started with for this to work, before its other arguments. */
    val arguments: List<String>
    val environment: Map<String, String>
        get() = emptyMap()

    /** Asks for the dump to be loaded again, and with [raise] for the window to come to the front if the viewer can do that. False if the viewer is known not to have got the request. */
    fun reload(raise: Boolean): Boolean

    /**
     * Asks for [signals], hierarchical names such as `tb.dut.count`, to be added to the waveform. False
     * if the viewer is known not to have got the request.
     */
    fun addSignals(signals: List<String>): Boolean
}

/**
 * Surfer's waveform control protocol: started with `--wcp-initiate`, Surfer connects to [port] and takes
 * commands as JSON messages that end in a zero byte. Surfer handles them when it next draws its window,
 * which for a window that is hidden can be when it is looked at again.
 */
internal class SurferControl : ViewerControl {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val connected = CountDownLatch(1)

    @Volatile
    private var connection: Socket? = null

    val port: Int
        get() = server.localPort

    override val arguments: List<String>
        get() = listOf("--wcp-initiate", port.toString())

    /**
     * Surfer watches the dump itself and by default asks whether to reload it as soon as the simulation
     * starts writing; the reload asked for here comes once the dump is complete. A state file next to
     * the dump is not offered either: the layout is the one given on the command line.
     */
    override val environment: Map<String, String>
        get() = mapOf("SURFER_AUTORELOAD_FILES" to "Never", "SURFER_AUTOLOAD_SIBLING_STATE_FILES" to "Never")

    init {
        // Its own thread: it waits for as long as Surfer is open.
        thread(isDaemon = true, name = "Surfer waveform control") { serve() }
    }

    private fun serve() {
        try {
            val socket = server.accept()
            send(socket, GREETING)
            connection = socket
            connected.countDown()
            // What Surfer answers is not needed, but has to be read.
            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            while (input.read(buffer) >= 0) {
            }
        } catch (_: IOException) {
            // Closed, here or by Surfer.
        }
    }

    /** Surfer cannot be asked to raise its window. */
    override fun reload(raise: Boolean): Boolean = command(RELOAD)

    override fun addSignals(signals: List<String>): Boolean =
        command(signals.joinToString(",", """{"type":"command","command":"add_variables","variables":[""", "]}") { jsonString(it) })

    private fun command(message: String): Boolean {
        // Surfer connects once it has loaded the dump.
        if (!connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false
        return try {
            send(connection ?: return false, message)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun send(socket: Socket, message: String) = synchronized(this) {
        socket.getOutputStream().apply {
            write(message.toByteArray())
            write(0)
            flush()
        }
    }

    override fun close() {
        try {
            connection?.close()
            server.close()
        } catch (_: IOException) {
        }
    }

    internal companion object {
        const val GREETING = """{"type":"greeting","version":"0","commands":[]}"""
        const val RELOAD = """{"type":"command","command":"reload"}"""
        const val CONNECT_TIMEOUT_SECONDS = 3L

        internal fun jsonString(value: String): String = buildString {
            append('"')
            for (c in value) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

/**
 * GTKWave takes no commands from outside, but runs a Tcl script every few hundred milliseconds when
 * started with `--repscript`. The script reloads the dump when it finds a file that [reload] creates,
 * and adds the signals listed in a file that [addSignals] creates. It deletes the file, which tells that
 * the request was seen: a GTKWave built without Tcl ignores the script. Written from GTKWave's
 * documentation and source; not tried with a GTKWave.
 */
internal class GtkwaveControl(private val directory: Path = FileUtil.createTempDirectory("gtkwave", null, true).toPath()) :
    ViewerControl {
    private val reloadRequest = directory.resolve("reload")
    private val raiseRequest = directory.resolve("raise")
    private val addRequest = directory.resolve("add")
    private val script = directory.resolve("reload.tcl")

    init {
        Files.writeString(script, script(reloadRequest, raiseRequest, addRequest))
    }

    override val arguments: List<String>
        get() = listOf("--repscript", script.toString(), "--repperiod", PERIOD_MILLIS.toString())

    override fun reload(raise: Boolean): Boolean {
        try {
            if (raise) Files.write(raiseRequest, ByteArray(0))
            Files.write(reloadRequest, ByteArray(0))
        } catch (_: IOException) {
            return false
        }
        return seen(reloadRequest)
    }

    @Synchronized
    override fun addSignals(signals: List<String>): Boolean {
        try {
            // Moved into place whole, so that the script never reads half a list.
            val partial = directory.resolve("add.partial")
            Files.writeString(partial, signals.joinToString("\n", postfix = "\n"))
            Files.move(partial, addRequest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            return false
        }
        return seen(addRequest)
    }

    /** Waits for the script to delete [request]. */
    private fun seen(request: Path): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEEN_TIMEOUT_MILLIS)
        while (Files.exists(request)) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(PERIOD_MILLIS / 2)
        }
        return true
    }

    override fun close() {
        FileUtil.delete(directory.toFile())
    }

    internal companion object {
        const val PERIOD_MILLIS = 250L
        const val SEEN_TIMEOUT_MILLIS = 3_000L

        internal fun script(reloadRequest: Path, raiseRequest: Path, addRequest: Path): String {
            val add = tclString(addRequest)
            return listOf(
                "# Written by Verilog Toolbox: reloads the dump and adds signals when the IDE asks for it.",
                "if {[file exists ${tclString(raiseRequest)}]} { file delete ${tclString(raiseRequest)}; gtkwave::presentWindow }",
                "if {[file exists ${tclString(reloadRequest)}]} { file delete ${tclString(reloadRequest)}; gtkwave::reLoadFile }",
                // The facilities of a vector are named with their range, tb.dut.count[3:0], which the IDE does not know.
                "if {[file exists $add]} {",
                "    set vt_file [open $add r]; set vt_names [split [string trim [read \$vt_file]] \"\\n\"]; close \$vt_file",
                "    file delete $add",
                "    set vt_found {}",
                "    set vt_count [gtkwave::getNumFacs]",
                "    for {set vt_i 0} {\$vt_i < \$vt_count} {incr vt_i} {",
                "        set vt_name [gtkwave::getFacName \$vt_i]",
                "        regsub {\\[[^]]*\\]\$} \$vt_name {} vt_base",
                "        if {[lsearch -exact \$vt_names \$vt_base] >= 0} { lappend vt_found \$vt_name }",
                "    }",
                "    if {[llength \$vt_found] > 0} { gtkwave::addSignalsFromList \$vt_found }",
                "}",
            ).joinToString("\n", postfix = "\n")
        }

        /** A path as a quoted Tcl word, in which backslashes and what starts a substitution are escaped. */
        internal fun tclString(path: Path): String =
            '"' + path.toString().replace(Regex("""[\\"$\[\]{}]""")) { "\\" + it.value } + '"'
    }
}

/**
 * Remembers the dump file a simulation opens, as announced in its output, and has a viewer that shows
 * it load it again when the simulation ends.
 */
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

    /** Whatever the exit code: a testbench that fails is one whose waveform is looked at. */
    override fun processTerminated(event: ProcessEvent) {
        dumpFile?.let { Waveforms.reloadIfOpen(it) }
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

/**
 * "Open Waveform" in the toolbar of a run. [source] names the testbench whose waveform is meant and the
 * dump it wrote, null if it wrote none.
 */
class OpenWaveformAction(private val source: () -> Pair<IcarusRunConfiguration, Path?>) :
    DumbAwareAction(
        "Open Waveform",
        "Open the waveform dumped by the selected testbench, or by the last one that dumped any, in GTKWave or Surfer",
        ICON,
    ) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val (configuration, dump) = source()
        Waveforms.open(configuration, dump)
    }

    private companion object {
        val ICON = IconLoader.getIcon("/icons/waveform.svg", OpenWaveformAction::class.java)
    }
}
