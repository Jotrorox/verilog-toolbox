package com.jotrorox.verilogtoolbox.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.jotrorox.verilogtoolbox.common.OssCadSuite
import com.jotrorox.verilogtoolbox.lsp.SlangServerDownloader
import com.jotrorox.verilogtoolbox.openfpgaloader.OpenFpgaLoader
import com.jotrorox.verilogtoolbox.waveform.WaveformViewer
import com.jotrorox.verilogtoolbox.yosys.SynthesisTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Settings of the slang language server, the simulation, lint and synthesis tools, the programmer and the waveform viewers. */
@Service(Service.Level.APP)
@State(name = "VerilogToolboxToolchain", storages = [Storage("verilogToolbox.xml")])
class ToolchainSettings :
    SerializablePersistentStateComponent<ToolchainSettings.State>(State()) {

    /**
     * Replaced as a whole, never changed in place. The fields are `var` all the same: the platform leaves a
     * final field out when it writes the state, so that with `val` nothing would be saved.
     */
    data class State(
        @JvmField var slangServerEnabled: Boolean = true,
        @JvmField var slangServerPath: String = "",
        @JvmField var slangServerAutoDownload: Boolean = true,
        @JvmField var iverilogPath: String = "",
        @JvmField var waveformViewer: WaveformViewer = WaveformViewer.GTKWAVE,
        @JvmField var gtkwavePath: String = "",
        @JvmField var surferPath: String = "",
        @JvmField var verilatorPath: String = "",
        @JvmField var verilatorLintEnabled: Boolean = true,
        @JvmField var verilatorOptions: String = "",
        @JvmField var yosysPath: String = "",
        @JvmField var synthesisTarget: SynthesisTarget = SynthesisTarget.GENERIC,
        @JvmField var quartusPath: String = "",
        @JvmField var quartusSimulationLibraries: Boolean = true,
        @JvmField var openFpgaLoaderPath: String = "",
    )

    /** Whether slang-server, the default language server, is used at all. */
    var slangServerEnabled: Boolean
        get() = state.slangServerEnabled
        set(value) {
            updateState { it.copy(slangServerEnabled = value) }
        }

    /** Path to `slang-server`; empty means "use the downloaded copy, or the one on PATH". */
    var slangServerPath: String
        get() = state.slangServerPath
        set(value) {
            updateState { it.copy(slangServerPath = value.trim()) }
        }

    /** Whether slang-server is downloaded without asking when none is found. */
    var slangServerAutoDownload: Boolean
        get() = state.slangServerAutoDownload
        set(value) {
            updateState { it.copy(slangServerAutoDownload = value) }
        }

    /** Path to `iverilog`; empty means "use the OSS CAD Suite's, or else look it up on PATH". */
    var iverilogPath: String
        get() = state.iverilogPath
        set(value) {
            updateState { it.copy(iverilogPath = value.trim()) }
        }

    /** The viewer "Open Waveform" prefers. */
    var waveformViewer: WaveformViewer
        get() = state.waveformViewer
        set(value) {
            updateState { it.copy(waveformViewer = value) }
        }

    /** Path to `gtkwave`; empty means "look it up on PATH". */
    var gtkwavePath: String
        get() = state.gtkwavePath
        set(value) {
            updateState { it.copy(gtkwavePath = value.trim()) }
        }

    /** Path to `surfer`; empty means "look it up on PATH". */
    var surferPath: String
        get() = state.surferPath
        set(value) {
            updateState { it.copy(surferPath = value.trim()) }
        }

    /** Path to `verilator`; empty means "use the OSS CAD Suite's, or else look it up on PATH". */
    var verilatorPath: String
        get() = state.verilatorPath
        set(value) {
            updateState { it.copy(verilatorPath = value.trim()) }
        }

    /** Whether Verilator lints the open Verilog files, next to slang-server's diagnostics. */
    var verilatorLintEnabled: Boolean
        get() = state.verilatorLintEnabled
        set(value) {
            updateState { it.copy(verilatorLintEnabled = value) }
        }

    /** Extra Verilator options for linting, such as `-Wall`. */
    var verilatorOptions: String
        get() = state.verilatorOptions
        set(value) {
            updateState { it.copy(verilatorOptions = value.trim()) }
        }

    /** Path to `yosys`; empty means "use the OSS CAD Suite's, or else look it up on PATH". */
    var yosysPath: String
        get() = state.yosysPath
        set(value) {
            updateState { it.copy(yosysPath = value.trim()) }
        }

    /** The FPGA family, or generic gates, that Check Synthesis maps the design to. */
    var synthesisTarget: SynthesisTarget
        get() = state.synthesisTarget
        set(value) {
            updateState { it.copy(synthesisTarget = value) }
        }

    /**
     * Where Quartus Prime is installed: its version directory, its `quartus` directory or the directory of
     * its tools. Empty means "where QUARTUS_ROOTDIR points, on PATH, or where it installs by default".
     */
    var quartusPath: String
        get() = state.quartusPath
        set(value) {
            updateState { it.copy(quartusPath = value.trim()) }
        }

    /**
     * Whether a design read from a Quartus project gets Altera's simulation models as libraries, so that
     * the megafunctions and primitives it instantiates are known to the other tools.
     */
    var quartusSimulationLibraries: Boolean
        get() = state.quartusSimulationLibraries
        set(value) {
            updateState { it.copy(quartusSimulationLibraries = value) }
        }

    /** Path to `openFPGALoader`; empty means "use the OSS CAD Suite's, or else look it up on PATH". */
    var openFpgaLoaderPath: String
        get() = state.openFpgaLoaderPath
        set(value) {
            updateState { it.copy(openFpgaLoaderPath = value.trim()) }
        }

    /**
     * The slang-server to launch: the configured path if valid, otherwise the copy the plugin downloaded,
     * otherwise the one on PATH. `null` when it is switched off or not installed.
     */
    fun findSlangServer(): Path? = when {
        !slangServerEnabled -> null
        slangServerPath.isNotEmpty() -> executable(slangServerPath)
        else -> SlangServerDownloader.installed() ?: findOnPath(SLANG_SERVER_BINARY)
    }

    /** The compiler to run: the configured path if valid, otherwise the OSS CAD Suite's, otherwise the one on PATH. */
    fun findIverilog(): Path? = findTool(iverilogPath, "iverilog")

    /**
     * Verilator for linting. The suite's `verilator_bin` launcher is used rather than its `verilator`, a Perl
     * script that only passes `--lint-only` on to it, so that no Perl is needed.
     */
    fun findVerilator(): Path? =
        if (verilatorPath.isNotEmpty()) executable(verilatorPath)
        else OssCadSuite.tool("verilator_bin") ?: findOnPath(binary("verilator"))

    fun findYosys(): Path? = findTool(yosysPath, "yosys")

    fun findOpenFpgaLoader(): Path? = findTool(openFpgaLoaderPath, OpenFpgaLoader.TOOL)

    /** The [configured] path if it is valid, otherwise the suite's copy of [name], otherwise the one on PATH. */
    private fun findTool(configured: String, name: String): Path? =
        if (configured.isNotEmpty()) executable(configured) else OssCadSuite.tool(name) ?: findOnPath(binary(name))

    /** The simulation runtime, which is installed next to the compiler. */
    fun findVvp(): Path? =
        findIverilog()?.resolveSibling(VVP_BINARY)?.takeIf { Files.isExecutable(it) } ?: findOnPath(VVP_BINARY)

    /** The viewer to launch and where it is: the preferred one, or the other one if only that is installed. */
    fun findWaveformViewer(): Pair<WaveformViewer, Path>? =
        (listOf(waveformViewer) + WaveformViewer.entries).distinct()
            .firstNotNullOfOrNull { viewer -> findViewer(viewer)?.let { viewer to it } }

    private fun findViewer(viewer: WaveformViewer): Path? {
        val configured = when (viewer) {
            WaveformViewer.GTKWAVE -> gtkwavePath
            WaveformViewer.SURFER -> surferPath
        }
        if (configured.isNotEmpty()) return executable(configured) ?: application(configured)
        // A viewer the user installed comes first: the suite's GTKWave is only there because Icarus is.
        return findOnPath(viewer.binary) ?: viewer.macApplication?.let { application("/Applications/$it") }
            ?: if (viewer == WaveformViewer.GTKWAVE) OssCadSuite.tool("gtkwave") else null
    }

    /** A macOS application bundle. */
    private fun application(path: String): Path? =
        path.takeIf { SystemInfo.isMac && it.removeSuffix("/").endsWith(".app") }
            ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { Files.isDirectory(it) }

    private fun executable(path: String): Path? =
        path.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf { Files.isExecutable(it) && !Files.isDirectory(it) }

    private fun findOnPath(binary: String): Path? = findInstalledExecutable(binary)

    companion object {
        val SLANG_SERVER_BINARY: String = if (SystemInfo.isWindows) "slang-server.exe" else "slang-server"
        val IVERILOG_BINARY: String = if (SystemInfo.isWindows) "iverilog.exe" else "iverilog"
        val VVP_BINARY: String = if (SystemInfo.isWindows) "vvp.exe" else "vvp"

        private fun binary(name: String): String = if (SystemInfo.isWindows) "$name.exe" else name

        fun getInstance(): ToolchainSettings = service()

        /**
         * A command line for a tool. On Linux and macOS the OSS CAD Suite's launchers set up their own
         * environment. On Windows they are the programs themselves, so what the suite's `environment.bat`
         * would set is set here: its `bin` and `lib` directories, which hold the DLLs, go on PATH, and
         * Verilator is told where its standard library is.
         */
        fun toolCommandLine(tool: Path): GeneralCommandLine {
            val commandLine = GeneralCommandLine(tool.toString()).withCharset(Charsets.UTF_8)
            val binaries = tool.parent ?: return commandLine
            val root = binaries.parent ?: return commandLine
            val libraries = root.resolve("lib")
            if (SystemInfo.isWindows && Files.isDirectory(libraries)) {
                val path = listOfNotNull(binaries.toString(), libraries.toString(), System.getenv("PATH"))
                    .joinToString(File.pathSeparator)
                commandLine.withEnvironment("PATH", path)
                val verilatorRoot = root.resolve("share").resolve("verilator")
                if (tool.fileName.toString().startsWith("verilator") && Files.isDirectory(verilatorRoot)) {
                    commandLine.withEnvironment("VERILATOR_ROOT", verilatorRoot.toString())
                }
            }
            return commandLine
        }
    }
}

/** The first executable called [binary] in the directories of `PATH`, as the IDE's processes see it. */
internal fun findExecutableOnPath(binary: String, path: String? = EnvironmentUtil.getValue("PATH")): Path? =
    path.orEmpty().split(File.pathSeparatorChar).asSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { directory -> runCatching { Path.of(directory, binary) }.getOrNull() }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

/** Where Homebrew links its binaries: `/opt/homebrew` on Apple silicon, `/usr/local` on Intel Macs. */
private val HOMEBREW_DIRECTORIES = listOf("/opt/homebrew/bin", "/usr/local/bin")

/**
 * The executable called [binary] on `PATH`, or else in Homebrew's directories on macOS: an IDE started from
 * the Dock does not always inherit the shell's PATH.
 */
internal fun findInstalledExecutable(binary: String): Path? =
    findExecutableOnPath(binary)
        ?: HOMEBREW_DIRECTORIES.takeIf { SystemInfo.isMac }
            ?.mapNotNull { runCatching { Path.of(it, binary) }.getOrNull() }
            ?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
