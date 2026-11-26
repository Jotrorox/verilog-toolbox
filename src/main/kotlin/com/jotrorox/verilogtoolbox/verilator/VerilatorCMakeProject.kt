package com.jotrorox.verilogtoolbox.verilator

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The CMake project that builds the Verilator simulations of an IDE project, in `build/verilator`: a directory
 * for each simulation with what Verilator generated and a `CMakeLists.txt` that compiles it with the runtime and
 * any C++ testbench, and a `CMakeLists.txt` above them that takes them all in. The run configurations build it in
 * `cmake-build-run`; CLion can load it as a CMake project of its own, to build, run and debug the simulations
 * with its tools. Verilator's `verilator-config.cmake` would do the same, but needs Perl.
 */
object VerilatorCMakeProject {
    /** In the first line of every file written here, so that only those are written again or taken in. */
    const val MARKER = "# Written by Verilog Toolbox"

    /** Where the run configurations build, apart from the `cmake-build-*` directories of CLion's profiles. */
    const val BUILD_DIRECTORY = "cmake-build-run"

    /** Executables go to `bin` of the build directory; `$<0:>` keeps multi-configuration generators from adding one per configuration. */
    private const val OUTPUT_DIRECTORY = "\${CMAKE_BINARY_DIR}/bin/$<0:>"

    /** The project of [root], `build/verilator` of an IDE project. */
    fun rootFile(root: Path): Path = root.resolve("CMakeLists.txt")

    fun executable(root: Path, target: String): Path =
        root.resolve(BUILD_DIRECTORY).resolve("bin").resolve(if (SystemInfo.isWindows) "$target.exe" else target)

    /** What one simulation is built from. */
    class Simulation(
        /** The target and the name of its directory: letters, digits and underscores. */
        val target: String,
        /** Said in the files, such as the run configuration's name. */
        val description: String,
        val prefix: String,
        val manifest: VerilatorManifest,
        /** Verilator's output directory, `--Mdir`. */
        val modelDirectory: Path,
        val installation: VerilatorInstallation,
        /** The command that verilates the design again, run in [workingDirectory]; without the executable. */
        val verilatorArguments: List<String>,
        val workingDirectory: Path,
        /** The C++ testbench and its other files; none for a Verilog testbench, whose `main` Verilator writes. */
        val cppSources: List<Path>,
        /** For CTest, which CLion shows in its test runner. */
        val simulationDirectory: Path,
        val simulationArguments: List<String>,
    )

    /** The project above the simulations, taking in every directory below [root] that has a file of ours. */
    fun rootText(root: Path): String {
        val simulations = try {
            Files.list(root).use { entries ->
                entries.filter { it.isDirectory() && isOurs(it.resolve("CMakeLists.txt")) }.map { it.name }.sorted().toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
        return buildString {
            appendLine("$MARKER for the Verilator run configurations of this project; each run writes it again.")
            appendLine("# Load it in CLion with Load CMake Project to build, run, debug and test the simulations there.")
            appendLine("cmake_minimum_required(VERSION 3.16)")
            appendLine("project(verilator_simulations LANGUAGES C CXX)")
            appendLine()
            appendLine("enable_testing()")
            appendLine("find_package(Threads REQUIRED)")
            appendLine("# Verilator's runtime needs libatomic where it exists.")
            appendLine("include(CheckCXXSourceCompiles)")
            appendLine("set(CMAKE_REQUIRED_LIBRARIES atomic)")
            appendLine("check_cxx_source_compiles(\"int main() { return 0; }\" VERILATOR_LIBATOMIC)")
            appendLine("unset(CMAKE_REQUIRED_LIBRARIES)")
            if (simulations.isNotEmpty()) appendLine()
            for (simulation in simulations) appendLine("add_subdirectory($simulation)")
        }
    }

    fun simulationText(simulation: Simulation): String {
        val target = simulation.target
        val manifest = simulation.manifest
        return buildString {
            appendLine("$MARKER for '${comment(simulation.description)}' from Verilator's list of the files it generated;")
            appendLine("# each run writes it again. Changes to the Verilog sources verilate them again when this is built.")
            appendLine()
            appendLine("set(VERILATOR_ROOT ${quote(simulation.installation.root)})")
            appendLine()
            appendLine("set(${target}_GENERATED")
            manifest.generated.forEach { appendLine("    ${quote(it)}") }
            appendLine(")")
            appendLine("add_executable($target")
            appendLine("    \${${target}_GENERATED}")
            (manifest.global + manifest.userClasses + simulation.cppSources).distinct().forEach { appendLine("    ${quote(it)}") }
            appendLine(")")
            appendLine("set_target_properties($target PROPERTIES RUNTIME_OUTPUT_DIRECTORY \"$OUTPUT_DIRECTORY\")")
            appendLine("target_include_directories($target PRIVATE")
            appendLine("    ${quote(simulation.modelDirectory)}")
            appendLine("    \"\${VERILATOR_ROOT}/include\"")
            appendLine("    \"\${VERILATOR_ROOT}/include/vltstd\"")
            appendLine(")")
            appendLine("target_compile_definitions($target PRIVATE")
            appendLine("    VM_COVERAGE=${bit(manifest.coverage)} VM_DPI=${bit(manifest.dpi)} VM_SC=${bit(manifest.systemC)}")
            appendLine(
                "    VM_TRACE=${bit(manifest.trace)} VM_TRACE_VCD=${bit(manifest.traceVcd)} " +
                    "VM_TRACE_FST=${bit(manifest.traceFst)} VM_TRACE_SAIF=${bit(manifest.traceSaif)}"
            )
            appendLine("    VM_PREFIX=${simulation.prefix} \"VM_PREFIX_INCLUDE=<${simulation.prefix}.h>\"")
            appendLine(")")
            if (manifest.timing) {
                appendLine("# --timing makes coroutines of the delays, which take C++20 and, with GCC, -fcoroutines.")
                appendLine("target_compile_features($target PRIVATE cxx_std_20)")
                appendLine("target_compile_options($target PRIVATE \$<\$<CXX_COMPILER_ID:GNU>:-fcoroutines>)")
            } else {
                appendLine("target_compile_features($target PRIVATE cxx_std_17)")
            }
            appendLine("# Generated code has many functions in one file, more than MinGW's assembler takes by default.")
            appendLine("target_compile_options($target PRIVATE \$<\$<AND:\$<CXX_COMPILER_ID:GNU>,\$<PLATFORM_ID:Windows>>:-Wa,-mbig-obj>)")
            if (manifest.cflags.isNotEmpty()) {
                appendLine("target_compile_options($target PRIVATE ${manifest.cflags.joinToString(" ") { quote(it) }})")
            }
            appendLine("target_link_libraries($target PRIVATE Threads::Threads)")
            appendLine("if(VERILATOR_LIBATOMIC)")
            appendLine("    target_link_libraries($target PRIVATE atomic)")
            appendLine("endif()")
            if (manifest.traceFst) {
                appendLine("find_package(ZLIB)")
                appendLine("if(ZLIB_FOUND)")
                appendLine("    target_link_libraries($target PRIVATE ZLIB::ZLIB)")
                appendLine("endif()")
            }
            if (manifest.ldflags.isNotEmpty()) {
                appendLine("target_link_libraries($target PRIVATE ${manifest.ldflags.joinToString(" ") { quote(it) }})")
            }
            appendLine()
            appendLine("add_custom_command(")
            appendLine("    OUTPUT \${${target}_GENERATED}")
            appendLine("    COMMAND ${verilatorCommand(simulation)}")
            appendLine("    DEPENDS ${quote(simulation.installation.executable)}")
            manifest.dependencies.forEach { appendLine("        ${quote(it)}") }
            appendLine("    WORKING_DIRECTORY ${quote(simulation.workingDirectory)}")
            appendLine("    COMMENT \"Verilating ${comment(simulation.description)}\"")
            appendLine("    VERBATIM")
            appendLine(")")
            appendLine()
            val arguments = simulation.simulationArguments.joinToString("") { " " + quote(it) }
            appendLine("add_test(NAME $target COMMAND $target$arguments WORKING_DIRECTORY ${quote(simulation.simulationDirectory)})")
            appendLine("set_tests_properties($target PROPERTIES FAIL_REGULAR_EXPRESSION \"%(Error|Fatal)\")")
        }
    }

    /** Verilator with its environment, which on Windows holds a PATH: `;` would split the argument, so it is written as `$<SEMICOLON>`. */
    private fun verilatorCommand(simulation: Simulation): String {
        val environment = simulation.installation.environment.entries.sortedBy { it.key }
            .map { (name, value) -> quote("$name=${value.replace(File.pathSeparator, "$<SEMICOLON>")}", path = false) }
        val command = if (environment.isEmpty()) emptyList() else listOf("\"\${CMAKE_COMMAND}\"", "-E", "env") + environment
        return (command + quote(simulation.installation.executable) + simulation.verilatorArguments.map { quote(it, path = false) })
            .joinToString(" ")
    }

    /** Writes [text] to [file] unless it has it already, which keeps CMake from configuring again for nothing. */
    @Throws(IOException::class)
    fun write(file: Path, text: String) {
        if (file.isRegularFile() && runCatching { Files.readString(file) }.getOrNull() == text) return
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    fun isOurs(file: Path): Boolean =
        file.isRegularFile() && runCatching { Files.newBufferedReader(file).use { it.readLine() } }.getOrNull()?.startsWith(MARKER) == true

    /** A CMake target name, which may not hold most punctuation. */
    fun target(name: String): String = name.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "simulation" }

    private fun bit(value: Boolean): Int = if (value) 1 else 0

    private fun quote(path: Path): String = quote(path.toString(), path = true)

    /**
     * A quoted CMake argument. A path gets forward slashes, which CMake takes on every system and which need no
     * escaping; in other text backslashes, quotes and `$` are escaped. `$<SEMICOLON>` is left as it is.
     */
    internal fun quote(text: String, path: Boolean = true): String {
        if (path) return "\"" + text.replace('\\', '/').replace("\"", "\\\"").replace("\${", "\\\${") + "\""
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace(Regex("""\$(?!<SEMICOLON>)"""), "\\\\\\$")
        return "\"$escaped\""
    }

    /** Text for a comment or a quoted message, on one line. */
    private fun comment(text: String): String = text.replace(Regex("[\\r\\n\"\\\\$]"), " ")
}
