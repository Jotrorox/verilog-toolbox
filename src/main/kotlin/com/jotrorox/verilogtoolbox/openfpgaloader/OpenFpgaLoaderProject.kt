package com.jotrorox.verilogtoolbox.openfpgaloader

import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Just enough of a Makefile to see how a project programs its FPGA: the variables, the rules and their
 * recipes, so that a recipe such as `$(LOADER) -b $(BOARD) $<` reads as `openFPGALoader -b tangprimer25k
 * build/top.fs`. Conditionals are not evaluated: every branch is read, the later assignment winning.
 */
internal class Makefile private constructor(
    private val variables: Map<String, Variable>,
    val rules: List<Rule>,
) {
    /** A variable's value; a recursive one (`=`) is expanded where it is used, a simple one (`:=`) already was. */
    private class Variable(val value: String, val recursive: Boolean)

    /** A rule with its targets and prerequisites expanded, and its recipe as written. */
    class Rule(val targets: List<String>, val prerequisites: List<String>, val recipe: List<String>)

    /** An `openFPGALoader` command of a recipe, with what its options say. */
    data class Invocation(
        /** The target of the rule, such as `load` or `flash`. */
        val target: String,
        val bitstream: String?,
        val board: String,
        val cable: String,
        val fpgaPart: String,
        val destination: FpgaDestination,
        val verify: Boolean,
        /** The options that are not covered by the fields, as they would be typed. */
        val options: String,
    )

    /** The value of [name], expanded; empty when it is not set. */
    fun value(name: String): String = expand("\$($name)", emptyMap())

    /**
     * Expands the variable and function references of [text]. [automatic] holds the automatic variables of a
     * recipe, such as `@` and `<`. Of the functions only those that need no file system are known; the others
     * expand to nothing.
     */
    fun expand(text: String, automatic: Map<String, String>, depth: Int = 0): String {
        if (depth > MAX_DEPTH || '$' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }
            val next = text[i + 1]
            when (next) {
                '$' -> {
                    out.append('$')
                    i += 2
                }
                '(', '{' -> {
                    val close = matching(text, i + 1)
                    if (close < 0) {
                        out.append(text, i, text.length)
                        i = text.length
                    } else {
                        out.append(reference(text.substring(i + 2, close), automatic, depth))
                        i = close + 1
                    }
                }
                else -> {
                    out.append(reference(next.toString(), automatic, depth))
                    i += 2
                }
            }
        }
        return out.toString()
    }

    /** The index of the bracket that closes the one at [open], counting nested ones. */
    private fun matching(text: String, open: Int): Int {
        val opening = text[open]
        val closing = if (opening == '(') ')' else '}'
        var level = 0
        for (i in open until text.length) {
            when (text[i]) {
                opening -> level++
                closing -> if (--level == 0) return i
            }
        }
        return -1
    }

    private fun reference(body: String, automatic: Map<String, String>, depth: Int): String {
        val name = expand(body, automatic, depth + 1)
        // `$(@D)`, `$(<F)`: the directory or file part of an automatic variable.
        if (name.length == 2 && name[0] in AUTOMATIC && name[1] in "DF") {
            val path = automatic[name[0].toString()].orEmpty()
            return if (name[1] == 'D') path.substringBeforeLast('/', ".") else path.substringAfterLast('/')
        }
        automatic[name]?.let { return it }
        val space = name.indexOfFirst { it.isWhitespace() }
        if (space > 0) return function(name.substring(0, space), name.substring(space + 1).trimStart(), automatic, depth)
        // `$(VAR:.json=.fs)`: a substitution reference.
        val colon = name.indexOf(':')
        if (colon > 0 && '=' in name.substring(colon)) {
            val value = variable(name.substring(0, colon), automatic, depth)
            val (from, to) = name.substring(colon + 1).split('=', limit = 2)
            return words(value).joinToString(" ") { word -> if (word.endsWith(from)) word.removeSuffix(from) + to else word }
        }
        return variable(name, automatic, depth)
    }

    private fun variable(name: String, automatic: Map<String, String>, depth: Int): String {
        val variable = variables[name] ?: return ""
        return if (variable.recursive) expand(variable.value, automatic, depth + 1) else variable.value
    }

    private fun function(name: String, arguments: String, automatic: Map<String, String>, depth: Int): String {
        fun argument(index: Int) = arguments.split(',').getOrElse(index) { "" }.let { expand(it, automatic, depth + 1) }
        return when (name) {
            "strip" -> words(argument(0)).joinToString(" ")
            "firstword" -> words(argument(0)).firstOrNull().orEmpty()
            "lastword" -> words(argument(0)).lastOrNull().orEmpty()
            "notdir" -> words(argument(0)).joinToString(" ") { it.substringAfterLast('/') }
            "dir" -> words(argument(0)).joinToString(" ") { if ('/' in it) it.substringBeforeLast('/') + "/" else "./" }
            "basename" -> words(argument(0)).joinToString(" ") { word ->
                val dot = word.lastIndexOf('.')
                if (dot > word.lastIndexOf('/')) word.substring(0, dot) else word
            }
            "addprefix" -> words(argument(1)).joinToString(" ") { argument(0).trim() + it }
            "addsuffix" -> words(argument(1)).joinToString(" ") { it + argument(0).trim() }
            "subst" -> argument(2).replace(argument(0), argument(1))
            "patsubst" -> {
                val pattern = argument(0).trim()
                val replacement = argument(1).trim()
                words(argument(2)).joinToString(" ") { word -> patternStem(pattern, word)?.let { replacement.replace("%", it) } ?: word }
            }
            "if" -> if (argument(0).isNotBlank()) argument(1) else argument(2)
            "or" -> arguments.split(',').map { expand(it, automatic, depth + 1) }.firstOrNull { it.isNotBlank() }.orEmpty()
            else -> ""
        }
    }

    /** The invocations of openFPGALoader in the recipes, in the order of the rules. */
    fun invocations(): List<Invocation> = rules.flatMap { rule ->
        val target = rule.targets.firstOrNull() ?: return@flatMap emptyList()
        val automatic = mapOf(
            "@" to target,
            "<" to rule.prerequisites.firstOrNull().orEmpty(),
            "^" to rule.prerequisites.distinct().joinToString(" "),
            "+" to rule.prerequisites.joinToString(" "),
        )
        rule.recipe.flatMap { line -> commands(expand(line, automatic)) }
            .mapNotNull { command -> invocation(target, command) }
    }

    /** Whether a rule makes [path], given relative to the Makefile's directory: by its name, or by a pattern rule. */
    fun makes(path: String): Boolean {
        val wanted = normalize(path)
        return rules.any { rule -> rule.targets.any { target -> normalize(target) == wanted || patternStem(normalize(target), wanted) != null } }
    }

    companion object {
        val NAMES = listOf("GNUmakefile", "makefile", "Makefile")
        private const val MAX_DEPTH = 20
        private const val AUTOMATIC = "@<^+*?|"
        private val ASSIGNMENT = Regex("""^(?:(?:override|export|private)\s+)*([^\s:#=+?!]+)\s*(\?=|::?=|:::=|\+=|!=|=)(.*)$""")
        private val DIRECTIVE = Regex("""^(ifeq|ifneq|ifdef|ifndef|else|endif|include|-include|sinclude|vpath|unexport|undefine)\b""")

        /** Options of openFPGALoader that take a value, so that the value is not taken for the bitstream. */
        private val WITH_VALUE = setOf(
            "--altsetting", "--bitstream", "--secondary-bitstream", "-b", "--board", "-B", "--bridge", "-c", "--cable",
            "--status-pin", "--vid", "--pid", "--cable-index", "--busdev-num", "--usb-serial-num", "--ftdi-serial",
            "--ftdi-channel", "-d", "--device", "--target-flash", "--file-size", "--file-type", "--flash-sector", "--fpga-part",
            "--freq", "--index-chain", "--misc-device", "--ip", "-o", "--offset", "--pins", "--probe-firmware",
            "--protect-flash", "--verbose-level", "--port", "--mcufw", "--read-register", "--user-flash",
        )

        /** Options that the run configuration sets itself, or that would make the run something else than programming. */
        private val DROPPED = setOf("-m", "--write-sram", "-f", "--write-flash", "--external-flash", "--verify", "--quiet",
            "--force-terminal-mode", "-v", "--verbose")

        fun parse(text: String): Makefile {
            val variables = LinkedHashMap<String, Variable>()
            val rules = mutableListOf<Rule>()
            var recipe: MutableList<String>? = null
            var inDefine = false
            val lines = joinContinuations(text)
            // Expands while reading, as make does for targets, prerequisites and simple variables.
            val reader = Makefile(variables, rules)
            for (raw in lines) {
                if (inDefine) {
                    if (raw.trim().startsWith("endef")) inDefine = false
                    continue
                }
                if (raw.startsWith("\t")) {
                    recipe?.add(raw.substring(1).trim())
                    continue
                }
                val line = stripComment(raw).trim()
                if (line.isEmpty()) continue
                if (line.startsWith("define ") || line == "define") {
                    inDefine = true
                    recipe = null
                    continue
                }
                if (DIRECTIVE.containsMatchIn(line)) continue
                val assignment = ASSIGNMENT.matchEntire(line)
                if (assignment != null) {
                    recipe = null
                    val (name, operator, value) = assignment.destructured
                    val trimmed = value.trim()
                    when (operator) {
                        "=" -> variables[name] = Variable(trimmed, recursive = true)
                        ":=", "::=", ":::=" -> variables[name] = Variable(reader.expand(trimmed, emptyMap()), recursive = false)
                        "?=" -> if (name !in variables) variables[name] = Variable(trimmed, recursive = true)
                        "+=" -> {
                            val old = variables[name]
                            variables[name] = when {
                                old == null -> Variable(trimmed, recursive = true)
                                old.recursive -> Variable("${old.value} $trimmed".trim(), recursive = true)
                                else -> Variable("${old.value} ${reader.expand(trimmed, emptyMap())}".trim(), recursive = false)
                            }
                        }
                        // `!=` runs a shell command, which is not done here.
                        else -> variables[name] = Variable("", recursive = false)
                    }
                    continue
                }
                val colon = ruleColon(line)
                if (colon < 0) {
                    recipe = null
                    continue
                }
                val targets = words(reader.expand(line.substring(0, colon), emptyMap()))
                var rest = line.substring(colon + 1).removePrefix(":")
                // `target: VAR = value` sets a variable for the target only; it is not a rule of its own.
                if (ASSIGNMENT.matches(rest.trim()) && rest.trim().substringBefore('=').none { it.isWhitespace() }) {
                    recipe = null
                    continue
                }
                val inline = rest.indexOf(';').takeIf { it >= 0 }?.let { index -> rest.substring(index + 1).trim().also { rest = rest.substring(0, index) } }
                val prerequisites = words(reader.expand(rest.substringBefore('|'), emptyMap()))
                val commands = mutableListOf<String>()
                if (!inline.isNullOrEmpty()) commands += inline
                rules += Rule(targets, prerequisites, commands)
                recipe = commands
            }
            return Makefile(variables, rules)
        }

        /** Lines with their backslash-newline continuations joined, as make joins them. */
        private fun joinContinuations(text: String): List<String> {
            val lines = mutableListOf<String>()
            val current = StringBuilder()
            for (line in text.replace("\r\n", "\n").split('\n')) {
                if (line.endsWith("\\") && !line.endsWith("\\\\")) {
                    current.append(line, 0, line.length - 1).append(' ')
                } else {
                    current.append(line)
                    lines += current.toString()
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
            return lines
        }

        private fun stripComment(line: String): String {
            var i = 0
            while (i < line.length) {
                if (line[i] == '\\') i += 2
                else if (line[i] == '#') return line.substring(0, i)
                else i++
            }
            return line
        }

        /** The colon that separates targets from prerequisites, outside of references; -1 for a line without one. */
        private fun ruleColon(line: String): Int {
            var level = 0
            for ((i, c) in line.withIndex()) {
                when (c) {
                    '(', '{' -> level++
                    ')', '}' -> level--
                    ':' -> if (level == 0 && line.getOrNull(i + 1) != '=') {
                        // A drive letter, as in `C:/tools`, is no rule.
                        if (i == 1 && line[0].isLetter() && line.getOrNull(2) in listOf('/', '\\')) continue
                        return i
                    }
                }
            }
            return -1
        }

        internal fun words(text: String): List<String> = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

        private fun normalize(path: String): String = path.trim().removePrefix("./").replace('\\', '/')

        /** The part of [word] that `%` of [pattern] stands for, or null when it does not match. */
        private fun patternStem(pattern: String, word: String): String? {
            val percent = pattern.indexOf('%')
            if (percent < 0) return null
            val prefix = pattern.substring(0, percent)
            val suffix = pattern.substring(percent + 1)
            if (word.length < prefix.length + suffix.length || !word.startsWith(prefix) || !word.endsWith(suffix)) return null
            return word.substring(prefix.length, word.length - suffix.length).takeIf { it.isNotEmpty() }
        }

        /** The commands of a recipe line, split where the shell would start another one. */
        private fun commands(line: String): List<String> =
            line.split(Regex("""&&|\|\||;""")).map { it.trim().trimStart('@', '-', '+').trim() }.filter { it.isNotEmpty() }

        private fun isLoader(word: String): Boolean {
            val name = word.substringAfterLast('/').substringAfterLast('\\')
            return name.equals(OpenFpgaLoader.TOOL, ignoreCase = true) || name.equals("${OpenFpgaLoader.TOOL}.exe", ignoreCase = true)
        }

        /** What an `openFPGALoader …` command says; null for any other command, or one that does not program. */
        private fun invocation(target: String, command: String): Invocation? {
            val words = ParametersListUtil.parse(command)
            val start = words.indexOfFirst { isLoader(it) }.takeIf { it >= 0 } ?: return null
            var bitstream: String? = null
            var board = ""
            var cable = ""
            var fpgaPart = ""
            var flash = false
            var external = false
            var verify = false
            val options = mutableListOf<String>()
            var i = start + 1
            while (i < words.size) {
                val word = words[i]
                val (option, inlineValue) = if (word.startsWith("--") && '=' in word) word.substringBefore('=') to word.substringAfter('=') else word to null
                val takesValue = option in WITH_VALUE
                val value = if (takesValue) inlineValue ?: words.getOrNull(++i).orEmpty() else null
                when {
                    // Detecting, listing or dumping is no programming.
                    option in setOf("--detect", "--scan-usb", "--list-boards", "--list-cables", "--list-fpga", "--dump-flash",
                        "--bulk-erase", "-h", "--help", "-V", "--version", "--Version", "--xvc") -> return null
                    option == "-b" || option == "--board" -> board = value.orEmpty()
                    option == "-c" || option == "--cable" -> cable = value.orEmpty()
                    option == "--fpga-part" -> fpgaPart = value.orEmpty()
                    option == "--bitstream" -> bitstream = value
                    option == "-f" || option == "--write-flash" -> flash = true
                    option == "--external-flash" -> external = true
                    option == "--verify" -> verify = true
                    option in DROPPED -> {}
                    takesValue -> options += listOf(option, value.orEmpty())
                    word.startsWith("-") -> options += word
                    bitstream == null -> bitstream = word
                }
                i++
            }
            val destination = when {
                flash && external -> FpgaDestination.EXTERNAL_FLASH
                flash -> FpgaDestination.FLASH
                else -> FpgaDestination.SRAM
            }
            // openFPGALoader verifies only what it writes to flash.
            return Invocation(target, bitstream?.takeIf { it.isNotBlank() }, board, cable, fpgaPart, destination, verify && flash, ParametersListUtil.join(options))
        }
    }
}

/**
 * What the run configuration for programming a project's FPGA is set up with: the bitstream, how to reach
 * the FPGA and how to build the bitstream first. Found in the project's Makefile, when it programs with
 * openFPGALoader, and among the bitstreams the project has built.
 */
internal data class FpgaProgramming(
    val bitstream: Path?,
    val target: FpgaTarget,
    /** Run in the project directory before programming, such as `make build/top.fs`; empty for none. */
    val buildCommand: String,
    /** Where the settings came from, such as `Makefile, rule load`, for the console. */
    val source: String?,
)

internal object OpenFpgaLoaderProject {
    private const val SCAN_DEPTH = 5

    /** Directories that hold nothing to program: the IDE's and other tools' own, and CMake's checks of the compiler. */
    private val SKIPPED_DIRECTORIES = setOf("node_modules", "CMakeFiles", "__pycache__", "venv")

    /**
     * How to program [destination] in the project at [base]. [file] is a bitstream the user chose, which then
     * is the one programmed; its directory and those above it, up to [base], are where the Makefile is looked for.
     */
    fun detect(base: Path, destination: FpgaDestination, file: Path? = null): FpgaProgramming {
        val makefilePath = findMakefile(base, file?.parent)
        val makefile = makefilePath?.let { path -> runCatching { Makefile.parse(Files.readString(path)) }.getOrNull() }
        val directory = makefilePath?.parent ?: base
        val invocations = makefile?.invocations().orEmpty()
        // The rule that writes where this run writes, else any that programs, for the board and the cable.
        val invocation = invocations.firstOrNull { it.destination == destination && (file == null || it.bitstream.resolvesTo(directory, file)) }
            ?: invocations.firstOrNull { it.destination == destination }
            ?: invocations.firstOrNull()
        val bitstream = file
            ?: invocation?.bitstream?.let { runCatching { directory.resolve(it).normalize() }.getOrNull() }?.takeIf { OpenFpgaLoader.isBitstream(it) }
            ?: bitstreams(base).firstOrNull()
        val target = FpgaTarget(
            board = invocation?.board.orEmpty(),
            cable = invocation?.cable.orEmpty(),
            fpgaPart = invocation?.fpgaPart.orEmpty(),
            destination = destination,
            verify = destination.writesFlash && invocation?.destination == destination && invocation.verify,
            options = invocation?.options.orEmpty(),
        )
        val buildCommand = if (makefile != null && bitstream != null) buildCommand(base, makefilePath, makefile, bitstream) else ""
        val source = makefilePath?.takeIf { invocation != null || buildCommand.isNotEmpty() }?.let { path ->
            relative(base, path) + (invocation?.let { ", rule ${it.target}" }.orEmpty())
        }
        return FpgaProgramming(bitstream, target, buildCommand, source)
    }

    private fun String?.resolvesTo(directory: Path, file: Path): Boolean =
        this != null && runCatching { directory.resolve(this).normalize() == file.normalize() }.getOrDefault(false)

    /** The Makefile in [start] or the closest directory above it, up to [base]; else the one in [base]. */
    internal fun findMakefile(base: Path, start: Path?): Path? {
        var directory = start?.takeIf { it.normalize().startsWith(base.normalize()) } ?: base
        while (true) {
            // By the names in the directory: on a file system that ignores case, `makefile` would find `Makefile` too.
            val names = runCatching { directory.listDirectoryEntries().filter { it.isRegularFile() }.map { it.name }.toSet() }.getOrDefault(emptySet())
            Makefile.NAMES.firstOrNull { it in names }?.let { return directory.resolve(it) }
            if (directory.normalize() == base.normalize()) return null
            directory = directory.parent ?: return null
        }
    }

    /** `make <bitstream>`, when the Makefile has a rule for it; with `-C` when the Makefile is not in [base]. */
    private fun buildCommand(base: Path, makefilePath: Path, makefile: Makefile, bitstream: Path): String {
        val directory = makefilePath.parent
        if (!bitstream.normalize().startsWith(directory.normalize())) return ""
        val target = directory.relativize(bitstream.normalize()).toString().replace('\\', '/')
        if (!makefile.makes(target)) return ""
        val words = mutableListOf("make")
        if (directory.normalize() != base.normalize()) words += listOf("-C", relative(base, directory))
        if (makefilePath.name !in listOf("GNUmakefile", "makefile", "Makefile")) words += listOf("-f", makefilePath.name)
        words += target
        return ParametersListUtil.join(words)
    }

    /** [path] relative to [base] with forward slashes, or absolute when it is not below [base]. */
    internal fun relative(base: Path, path: Path): String =
        if (path.normalize().startsWith(base.normalize())) base.normalize().relativize(path.normalize()).toString().replace('\\', '/').ifEmpty { "." }
        else path.toString()

    /**
     * The bitstreams at most [SCAN_DEPTH] directories below [base], the most recently built first. A `.bin`
     * comes after the others: it is as often firmware as a bitstream.
     */
    fun bitstreams(base: Path): List<Path> {
        if (!base.isDirectory()) return emptyList()
        val found = mutableListOf<Path>()

        fun visit(directory: Path, depth: Int) {
            val entries = runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
            for (entry in entries) {
                val name = entry.name
                if (entry.isDirectory()) {
                    if (depth < SCAN_DEPTH && !name.startsWith(".") && name !in SKIPPED_DIRECTORIES && !name.startsWith("cmake-build-")) {
                        visit(entry, depth + 1)
                    }
                } else if (OpenFpgaLoader.isBitstream(entry) && entry.isRegularFile()) {
                    found.add(entry)
                }
            }
        }
        visit(base, 0)
        return found.sortedWith(
            compareBy<Path> { it.extension.lowercase() == "bin" }.thenByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0) }
        )
    }

    /** What a bitstream is built from: HDL sources and the constraint files of the open and the vendors' tools. */
    private val SOURCE_EXTENSIONS = setOf("v", "sv", "vh", "svh", "vhd", "vhdl", "cst", "pcf", "lpf", "xdc", "ucf", "pdc", "sdc", "qsf", "ccf")

    /**
     * A source or constraint file below [base] that was changed after [bitstream] was written, if there is
     * one: then the bitstream does not have the latest changes.
     */
    fun newerSource(base: Path, bitstream: Path): Path? {
        val built = runCatching { Files.getLastModifiedTime(bitstream).toMillis() }.getOrNull() ?: return null
        val output = bitstream.parent?.normalize()
        var newest: Path? = null
        var newestTime = built

        fun visit(directory: Path, depth: Int) {
            for (entry in runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())) {
                val name = entry.name
                if (entry.isDirectory()) {
                    // The directory the bitstream is in is the build's, which writes files after it.
                    if (depth < SCAN_DEPTH && !name.startsWith(".") && name !in SKIPPED_DIRECTORIES && name != "build" &&
                        !name.startsWith("cmake-build-") && entry.normalize() != output
                    ) {
                        visit(entry, depth + 1)
                    }
                    continue
                }
                val extension = entry.extension.lowercase()
                if (extension !in SOURCE_EXTENSIONS) continue
                val time = runCatching { Files.getLastModifiedTime(entry).toMillis() }.getOrDefault(0)
                if (time > newestTime) {
                    newest = entry
                    newestTime = time
                }
            }
        }
        visit(base, 0)
        return newest
    }
}
