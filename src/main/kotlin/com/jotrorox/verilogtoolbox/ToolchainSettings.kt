package com.jotrorox.verilogtoolbox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Paths of the optional tools: the slang language server, the Icarus Verilog simulator and the waveform viewers. */
@Service(Service.Level.APP)
@State(name = "VerilogToolboxToolchain", storages = [Storage("verilogToolbox.xml")])
class ToolchainSettings :
    SerializablePersistentStateComponent<ToolchainSettings.State>(State()) {

    data class State(
        @JvmField val slangServerPath: String = "",
        @JvmField val iverilogPath: String = "",
        @JvmField val waveformViewer: WaveformViewer = WaveformViewer.GTKWAVE,
        @JvmField val gtkwavePath: String = "",
        @JvmField val surferPath: String = "",
    )

    /** Path to `slang-server`; empty means "do not run slang-server". */
    var slangServerPath: String
        get() = state.slangServerPath
        set(value) {
            updateState { it.copy(slangServerPath = value.trim()) }
        }

    /** Path to `iverilog`; empty means "look it up on PATH". */
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

    /** slang-server is opt-in: it only runs when its path is configured and points at an executable. */
    fun findSlangServer(): Path? = executable(slangServerPath)

    /** The compiler to run: the configured path if valid, otherwise the one on PATH. */
    fun findIverilog(): Path? =
        if (iverilogPath.isNotEmpty()) executable(iverilogPath) else findOnPath(IVERILOG_BINARY)

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
        return findOnPath(viewer.binary) ?: viewer.macApplication?.let { application("/Applications/$it") }
    }

    /** A macOS application bundle. */
    private fun application(path: String): Path? =
        path.takeIf { SystemInfo.isMac && it.removeSuffix("/").endsWith(".app") }
            ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { Files.isDirectory(it) }

    private fun executable(path: String): Path? =
        path.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf { Files.isExecutable(it) && !Files.isDirectory(it) }

    private fun findOnPath(binary: String): Path? =
        PathEnvironmentVariableUtil.findInPath(binary)?.toPath()
            // An IDE started from the Dock does not always inherit the shell's PATH.
            ?: HOMEBREW_DIRECTORIES.takeIf { SystemInfo.isMac }
                ?.firstNotNullOfOrNull { executable("$it/$binary") }

    companion object {
        val IVERILOG_BINARY: String = if (SystemInfo.isWindows) "iverilog.exe" else "iverilog"
        val VVP_BINARY: String = if (SystemInfo.isWindows) "vvp.exe" else "vvp"
        private val HOMEBREW_DIRECTORIES = listOf("/opt/homebrew/bin", "/usr/local/bin")

        fun getInstance(): ToolchainSettings = service()

        /**
         * A command line for an Icarus tool. The Windows build from the OSS CAD Suite keeps its DLLs in a
         * `lib` directory next to `bin`, which has to be on PATH for the tools to start.
         */
        fun icarusCommandLine(tool: Path): GeneralCommandLine {
            val commandLine = GeneralCommandLine(tool.toString()).withCharset(Charsets.UTF_8)
            val libraries = tool.parent?.resolveSibling("lib")
            if (SystemInfo.isWindows && libraries != null && Files.isDirectory(libraries.resolve("ivl"))) {
                val path = listOfNotNull(libraries.toString(), System.getenv("PATH")).joinToString(File.pathSeparator)
                commandLine.withEnvironment("PATH", path)
            }
            return commandLine
        }
    }
}
