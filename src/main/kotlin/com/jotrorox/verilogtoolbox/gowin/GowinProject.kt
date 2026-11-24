package com.jotrorox.verilogtoolbox.gowin

import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * What the built-in Gowin build of a project needs, found without a Makefile: the constraint file, the
 * board it is for, the Verilog sources and the top module.
 */
object GowinProject {
    private const val SCAN_DEPTH = 5

    /** Where the built-in build writes, relative to the project directory. */
    const val OUTPUT_DIRECTORY = "build/gowin"

    private val SKIPPED_DIRECTORIES = setOf("node_modules", "CMakeFiles", "__pycache__", "venv", "build", "out")
    private val SOURCE_EXTENSIONS = setOf("v", "sv")
    private val HEADER_EXTENSIONS = setOf("vh", "svh")

    /** The `.cst` files below [base], those nearest to it first. */
    fun constraintFiles(base: Path): List<Path> = files(base) { it.extension.lowercase() == "cst" }
        .sortedWith(compareBy<Path> { it.nameCount }.thenBy { it.toString() })

    /** The board [constraints] is for, by its name or by its first comment lines. */
    fun board(constraints: Path): GowinBoard? {
        GowinBoards.named(constraints.nameWithoutExtension)?.let { return it }
        val head = runCatching { Files.readAllLines(constraints).take(HEAD_LINES) }.getOrDefault(emptyList())
            .filter { it.trimStart().startsWith("//") }
        return GowinBoards.named(head.joinToString(" "))
    }

    /**
     * The Verilog sources below [base] that make up the design: every `.v` and `.sv` file except those
     * whose modules are all testbenches (modules without ports), and outside build and tool directories.
     */
    fun sources(base: Path): List<Path> = files(base) { it.extension.lowercase() in SOURCE_EXTENSIONS }
        .filter { file ->
            val modules = runCatching { VerilogModuleHeaders.modules(Files.readString(file)) }.getOrDefault(emptyList())
            modules.isEmpty() || modules.any { it.ports.isNotEmpty() }
        }
        .sorted()

    /**
     * The modules that more than one of [sources] defines, with the files that do, such as a module kept as
     * both `uart.v` and `uart.sv` while it is ported. Yosys refuses such a design.
     */
    fun duplicateModules(sources: List<Path>): Map<String, List<Path>> =
        sources.flatMap { file ->
            val modules = runCatching { VerilogModuleHeaders.modules(Files.readString(file)) }.getOrDefault(emptyList())
            modules.map { it.name to file }
        }.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }

    /** The directories of the headers below [base], for `` `include ``. */
    fun includeDirectories(base: Path): List<Path> =
        files(base) { it.extension.lowercase() in HEADER_EXTENSIONS }.mapNotNull { it.parent }.distinct().sorted()

    /**
     * The top module of [sources] for [constraints]: the module whose ports the constraint file places the
     * most of; among equals the one no other source instantiates, then one named `top`. Null without modules.
     */
    fun topModule(sources: List<Path>, constraints: Path?): String? {
        val texts = sources.mapNotNull { file -> runCatching { Files.readString(file) }.getOrNull() }
        val modules = texts.flatMap { text -> runCatching { VerilogModuleHeaders.modules(text) }.getOrDefault(emptyList()) }
            .filter { it.ports.isNotEmpty() }
        if (modules.isEmpty()) return null
        val located = constraints?.let { file -> runCatching { GowinCst.locatedNames(Files.readString(file)) }.getOrNull() }.orEmpty()
        val placed = located.map { it.substringBefore('[') }.toSet()
        val all = texts.joinToString("\n")
        return modules.maxWithOrNull(
            compareBy<VerilogModuleHeaders.Module> { module -> module.ports.count { it.name in placed } }
                .thenBy { module -> !isInstantiated(module.name, all) }
                .thenBy { it.name == "top" }
                // The first source in path order wins the remaining ties.
                .thenByDescending { modules.indexOf(it) }
        )?.name
    }

    /** Whether [all] has an instance of [module]: its name, maybe parameters, then an instance name and `(`. */
    private fun isInstantiated(module: String, all: String): Boolean =
        Regex("""(?<![\w$.`])${Regex.escape(module)}\s*(#\s*\(.*?\)\s*)?[A-Za-z_][\w$]*\s*(\[[^]]*]\s*)?\(""", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(all)

    private fun files(base: Path, accept: (Path) -> Boolean): List<Path> {
        if (!base.isDirectory()) return emptyList()
        val found = mutableListOf<Path>()

        fun visit(directory: Path, depth: Int) {
            for (entry in runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())) {
                val name = entry.name
                if (entry.isDirectory()) {
                    if (depth < SCAN_DEPTH && !name.startsWith(".") && name !in SKIPPED_DIRECTORIES && !name.startsWith("cmake-build-")) {
                        visit(entry, depth + 1)
                    }
                } else if (entry.isRegularFile() && accept(entry)) {
                    found.add(entry)
                }
            }
        }
        visit(base, 0)
        return found
    }

    private const val HEAD_LINES = 10
}
