package com.jotrorox.verilogtoolbox.verilator

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.findInstalledExecutable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The Verilator that verilates a simulation, and what has to be known about it to compile its output without
 * Verilator's own build files: those want `make` and Python, or for CMake Perl, which Windows does not have.
 */
data class VerilatorInstallation(
    val executable: Path,
    /** `VERILATOR_ROOT`, which holds the runtime's sources in `include`. */
    val root: Path,
    /** Whether it writes its list of files as JSON (`--make json`, Verilator 5.030 and later) or as CMake. */
    val writesJson: Boolean,
    /** What Verilator needs in its environment, such as the OSS CAD Suite's DLLs on Windows. */
    val environment: Map<String, String>,
) {
    companion object {
        private const val PROBE_TIMEOUT_MILLIS = 30_000

        private val known = ConcurrentHashMap<Pair<Path, FileTime>, VerilatorInstallation>()

        /** Asks [verilator] once per version of the file. Blocking: it starts Verilator. */
        @Throws(ExecutionException::class)
        fun of(verilator: Path): VerilatorInstallation {
            val modified = try {
                Files.getLastModifiedTime(verilator)
            } catch (e: IOException) {
                throw ExecutionException("Cannot read '$verilator': ${e.message}", e)
            }
            known[verilator to modified]?.let { return it }
            val environment = ToolchainSettings.toolCommandLine(verilator).environment.toMap()
            val root = run(verilator, "--getenv", "VERILATOR_ROOT").takeIf { it.exitCode == 0 }?.stdout?.trim()
                ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.resolve("include").isDirectory() }
                ?: suiteRoot(verilator)
                ?: throw ExecutionException(
                    "Verilator at '$verilator' does not say where its runtime is (verilator --getenv VERILATOR_ROOT)."
                )
            // An unknown --make is refused while the options are read, before --version prints and exits.
            val json = run(verilator, "--make", "json", "--version").exitCode == 0
            return VerilatorInstallation(verilator, root, json, environment).also { known[verilator to modified] = it }
        }

        /** The OSS CAD Suite keeps the runtime in `share/verilator` next to its `bin`. */
        private fun suiteRoot(verilator: Path): Path? =
            verilator.parent?.parent?.resolve("share")?.resolve("verilator")?.takeIf { it.resolve("include").isDirectory() }

        private fun run(verilator: Path, vararg arguments: String) =
            try {
                CapturingProcessHandler(ToolchainSettings.toolCommandLine(verilator).withParameters(*arguments))
                    .runProcess(PROBE_TIMEOUT_MILLIS)
            } catch (e: ExecutionException) {
                throw ExecutionException("Verilator at '$verilator' does not start: ${e.message}", e)
            }
    }
}

/**
 * CMake, the build tool it drives and the C++ compiler that build a Verilated simulation. CLion comes with CMake
 * and Ninja, and on Windows with MinGW, which are taken when none is set or on the PATH.
 */
class CppToolchain(
    val cmake: Path,
    /** Null to let CMake choose its default generator. */
    val ninja: Path?,
    /** Put in front of PATH for the build and the simulation, such as the bundled MinGW for its DLLs. */
    val pathDirectories: List<Path>,
    /** The compilers to configure with, when they are not the ones CMake would find itself. */
    val cCompiler: Path?,
    val cxxCompiler: Path?,
) {
    companion object {
        private val CMAKE = executableName("cmake")
        private val NINJA = executableName("ninja")

        /** Compilers CMake finds on the PATH by itself. */
        private val COMPILERS = listOf("c++", "g++", "clang++", "cl").map(::executableName)

        /** Null when there is no CMake. */
        fun find(settings: ToolchainSettings = ToolchainSettings.getInstance(), ideHome: Path? = ideHome()): CppToolchain? {
            val cmake = settings.cmakePath.takeIf { it.isNotEmpty() }?.let { executable(it) ?: return null }
                ?: findInstalledExecutable(CMAKE)
                ?: ideHome?.let { bundled(it.resolve("bin").resolve("cmake"), CMAKE) }
                ?: return null
            val ninja = findInstalledExecutable(NINJA) ?: ideHome?.let { bundled(it.resolve("bin").resolve("ninja"), NINJA) }
            val mingw = if (SystemInfo.isWindows && COMPILERS.none { findInstalledExecutable(it) != null }) {
                ideHome?.resolve("bin")?.resolve("mingw")?.resolve("bin")?.takeIf { it.resolve("g++.exe").isRegularFile() }
            } else {
                null
            }
            return CppToolchain(
                cmake, ninja, listOfNotNull(mingw), mingw?.resolve("gcc.exe"), mingw?.resolve("g++.exe"),
            )
        }

        private fun ideHome(): Path? = runCatching { Path.of(PathManager.getHomePath()) }.getOrNull()

        /** What [bundled] found, by directory and name: the IDE's files do not change while it runs. */
        private val bundledTools = ConcurrentHashMap<Pair<Path, String>, Optional<Path>>()

        /**
         * The IDE's copy of a tool lies a few directories down, such as `bin/cmake/win/x64/bin/cmake.exe`. Looked
         * up once, as checking a run configuration asks for it on the UI thread.
         */
        internal fun bundled(directory: Path, name: String): Path? =
            bundledTools.computeIfAbsent(directory to name) { Optional.ofNullable(search(directory, name)) }.orElse(null)

        /**
         * The IDE keeps a copy per system and processor, `<tool>/<system>/<arch>/…`, next to copies for its Cygwin
         * and WSL toolchains that do not run here: the Cygwin `ninja.exe` fails to start without Cygwin's DLLs.
         */
        private fun search(directory: Path, name: String): Path? {
            if (!directory.isDirectory()) return null
            val candidates = try {
                Files.walk(directory, 5).use { paths ->
                    paths.filter { it.name == name && it.isRegularFile() && it.isExecutable() }.toList()
                }
            } catch (_: IOException) {
                return null
            }
            return choose(directory, candidates)
        }

        /** Of the copies of a tool below [directory], the one for this system, preferably for this processor. */
        internal fun choose(
            directory: Path, candidates: List<Path>, system: String = systemDirectory(), architectures: Set<String> = architectureDirectories(),
        ): Path? {
            fun segments(path: Path) = directory.relativize(path).map { it.toString().lowercase() }
            return candidates.filter { candidate -> segments(candidate).none { it in OTHER_TOOLCHAINS } }
                .sortedWith(
                    compareBy<Path>(
                        { if (system in segments(it)) 0 else 1 },
                        { if (segments(it).any { segment -> segment in architectures }) 0 else 1 },
                    ).thenBy { it.toString() }
                )
                .firstOrNull()
        }

        /** Copies of the IDE's tools for toolchains other than the system's own. */
        private val OTHER_TOOLCHAINS = setOf("cygwin", "wsl")

        private fun systemDirectory(): String = when {
            SystemInfo.isWindows -> "win"
            SystemInfo.isMac -> "mac"
            else -> "linux"
        }

        private fun architectureDirectories(): Set<String> =
            if (CpuArch.isArm64()) setOf("aarch64", "arm64") else setOf("x64", "amd64", "x86_64")

        private fun executable(path: String): Path? =
            runCatching { Path.of(path) }.getOrNull()?.takeIf { it.isRegularFile() && it.isExecutable() }

        private fun executableName(name: String): String = if (SystemInfo.isWindows) "$name.exe" else name
    }
}
