package com.jotrorox.verilogtoolbox.common

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The design a slang command (`.f`) file describes, read the way slang's driver reads it so that every
 * tool given the file sees the same design. All paths are absolute.
 */
data class FileList(
    val sources: List<Path>,
    val includeDirectories: List<Path>,
    /** `NAME` or `NAME=value`. */
    val defines: List<String>,
    /** Searched for the modules the sources do not define. */
    val libraryDirectories: List<Path>,
    val libraryFiles: List<Path>,
    /** Tried in the library directories on top of slang's defaults, each with its leading dot. */
    val libraryExtensions: List<String>,
    val topModules: List<String>,
    /** Options that say nothing this plugin passes on, such as `--std`; reported rather than rejected. */
    val ignored: List<String>,
    /** What slang refuses the file for: unreadable or recursive nested files and options without a value. */
    val errors: List<String>,
) {
    companion object {
        /**
         * Reads [file]. Its relative paths start at [directory], as do those of files nested with `-f`;
         * the paths of a file nested with `-F` start at that file's own directory.
         */
        fun parse(file: Path, directory: Path, environment: (String) -> String? = System::getenv): FileList {
            val parser = Parser(environment)
            parser.read(file.toAbsolutePath().normalize(), directory.toAbsolutePath().normalize())
            return FileList(
                parser.sources.distinct(), parser.includeDirectories.distinct(), parser.defines,
                parser.libraryDirectories.distinct(), parser.libraryFiles.distinct(),
                parser.libraryExtensions.distinct(), parser.topModules.distinct(), parser.ignored.distinct(),
                parser.errors,
            )
        }

        /**
         * The directory the relative paths of a top-level [file] start at. It has to be the one
         * slang-server uses, or the tools would see different designs: the server loads a build file from
         * the [workspace] root, unless its configuration sets `buildRelativePaths`, which makes it the
         * file's own directory. Of the server's three configuration files the last that has the setting wins.
         */
        fun baseDirectory(
            file: Path,
            workspace: Path?,
            home: Path? = System.getProperty("user.home")?.let { Path.of(it) },
        ): Path {
            val own = file.toAbsolutePath().parent
            if (workspace == null) return own
            val configurations = listOfNotNull(
                workspace.resolve(".slang/server.json"),
                home?.resolve(".slang/server.json"),
                workspace.resolve(".slang/local/server.json"),
            )
            val relativeToFile = configurations.mapNotNull { configuration ->
                runCatching {
                    JsonParser.parseString(Files.readString(configuration)).asJsonObject["buildRelativePaths"].asBoolean
                }.getOrNull()
            }.lastOrNull() ?: false
            return if (relativeToFile) own else workspace
        }
    }
}

private enum class Option {
    INCLUDE, DEFINE, LIBRARY_DIRECTORY, LIBRARY_FILE, LIBRARY_EXTENSION, TOP,

    /** `-f`: a nested file whose paths start where those of the file naming it do. */
    COMMAND_FILE,

    /** `-F`: a nested file whose paths start at its own directory. */
    RELATIVE_COMMAND_FILE,

    /** Takes a value, which must not be mistaken for a source file. */
    IGNORED,
}

/** The spellings slang's driver accepts. A plus option takes its values as `+name+value+value`. */
private val OPTIONS: Map<String, Option> = buildMap {
    fun add(option: Option, vararg names: String) = names.forEach { put(it, option) }
    add(Option.INCLUDE, "-I", "--incdir", "--include-directory", "+incdir", "--isystem")
    add(Option.DEFINE, "-D", "--define-macro", "+define")
    add(Option.LIBRARY_DIRECTORY, "-y", "--libdir")
    add(Option.LIBRARY_FILE, "-v", "--libfile")
    add(Option.LIBRARY_EXTENSION, "-Y", "--libext", "+libext")
    add(Option.TOP, "--top")
    add(Option.COMMAND_FILE, "-f")
    add(Option.RELATIVE_COMMAND_FILE, "-F")
    add(
        Option.IGNORED,
        "-C", "-G", "-L", "-T", "-U", "-W", "-j", "--cmd-ignore", "--cmd-rename", "--compat",
        "--constexpr-backtrace-limit", "--defaultLibName", "--diag-json", "--dir-prefix", "--error-limit",
        "--exclude-ext", "--ignore-directive", "--libmap", "--map-keyword-version", "--max-case-analysis-steps",
        "--max-constant-size", "--max-constexpr-depth", "--max-constexpr-steps", "--max-enum-values",
        "--max-generate-steps", "--max-hierarchy-depth", "--max-include-depth", "--max-instance-array",
        "--max-lexer-errors", "--max-loop-analysis-steps", "--max-parse-depth", "--max-udp-coverage-notes",
        "--std", "--suppress-macro-warnings", "--suppress-warnings", "--threads", "--timescale", "--timing",
        "--translate-off-format", "--undefine-macro", "--waiver-file",
    )
}

/** Options whose value is a comma-separated list. A define is not one: its value may hold commas. */
private val LISTS = Option.entries - Option.DEFINE - Option.IGNORED

private class Parser(private val environment: (String) -> String?) {
    val sources = mutableListOf<Path>()
    val includeDirectories = mutableListOf<Path>()
    val defines = mutableListOf<String>()
    val libraryDirectories = mutableListOf<Path>()
    val libraryFiles = mutableListOf<Path>()
    val libraryExtensions = mutableListOf<String>()
    val topModules = mutableListOf<String>()
    val ignored = mutableListOf<String>()
    val errors = mutableListOf<String>()

    /** The files being read, outermost first: a file that names one of them would never end. */
    private val active = mutableListOf<Path>()

    fun read(file: Path, directory: Path) {
        if (file in active) {
            errors += "File list '$file' includes itself."
            return
        }
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            errors += "Cannot read file list '$file'."
            return
        }
        active.add(file)
        arguments(tokens(text), directory)
        active.removeLast()
    }

    private fun arguments(tokens: List<String>, directory: Path) {
        var index = 0
        var onlySources = false
        while (index < tokens.size) {
            val token = tokens[index++]
            if (onlySources || token.length <= 1 || token[0] != '-' && token[0] != '+') {
                sources += paths(directory, token, directories = false)
            } else if (token == "--") {
                onlySources = true
            } else if (token[0] == '+') {
                val parts = token.substring(1).split('+')
                val name = "+" + parts[0]
                val option = OPTIONS[name]
                if (option == null) ignored += name
                else parts.drop(1).filter { it.isNotEmpty() }.forEach { apply(option, name, it, directory) }
            } else {
                var name = token.substringBefore('=')
                var value = if ('=' in token) token.substringAfter('=') else null
                // A one-letter option may have its value attached: -Irtl.
                if (name !in OPTIONS && !token.startsWith("--") && token.take(2) in OPTIONS) {
                    name = token.take(2)
                    value = token.drop(2).removePrefix("=")
                }
                val option = OPTIONS[name]
                if (option == null) {
                    ignored += name
                    continue
                }
                if (value == null) {
                    if (index == tokens.size) {
                        errors += "Option '$name' in file list '${active.last()}' has no value."
                        return
                    }
                    value = tokens[index++]
                }
                apply(option, name, value, directory)
            }
        }
    }

    private fun apply(option: Option, name: String, value: String, directory: Path) {
        for (item in if (option in LISTS) value.split(',').filter { it.isNotEmpty() } else listOf(value)) {
            when (option) {
                Option.INCLUDE -> includeDirectories += paths(directory, item, directories = true)
                Option.DEFINE -> defines += item
                Option.LIBRARY_DIRECTORY -> libraryDirectories += paths(directory, item, directories = true)
                Option.LIBRARY_FILE -> libraryFiles += paths(directory, item, directories = false)
                Option.LIBRARY_EXTENSION -> libraryExtensions += if (item.startsWith(".")) item else ".$item"
                Option.TOP -> topModules += item
                Option.COMMAND_FILE -> paths(directory, item, directories = false).forEach { read(it, directory) }
                Option.RELATIVE_COMMAND_FILE ->
                    paths(directory, item, directories = false).forEach { read(it, it.parent ?: directory) }
                Option.IGNORED -> ignored += name
            }
        }
    }

    /**
     * Splits [text] like a shell does: quotes group, a backslash takes the next character literally, `#`
     * starts a comment anywhere and `//` or a block comment where a word would start (so not in `a//b`).
     */
    private fun tokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var pending = false // An empty quoted string is an argument too.
        var index = 0

        fun finish() {
            if (pending) tokens += token.toString()
            token.setLength(0)
            pending = false
        }

        fun lineEnd(): Int = text.indexOfAny(charArrayOf('\n', '\r'), index).let { if (it < 0) text.length else it }

        /** Expands the variable whose `$` was just read; text that names none stays as it is. */
        fun variable(): String {
            val start = index
            val close = when (text[index]) {
                '(' -> ')'
                '{' -> '}'
                else -> null
            }
            if (close != null) {
                val end = text.indexOf(close, index)
                index = if (end < 0) text.length else end + 1
                return if (end < 0) text.substring(start - 1) else environment(text.substring(start + 1, end)).orEmpty()
            }
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
            if (index == start) return "$" + text[index++]
            return environment(text.substring(start, index)).orEmpty()
        }

        while (index < text.length) {
            val c = text[index++]
            when {
                c.isWhitespace() -> finish()
                c == '#' -> {
                    finish()
                    index = lineEnd()
                }
                c == '/' && !pending && text.startsWith("/", index) -> index = lineEnd()
                c == '/' && !pending && text.startsWith("*", index) ->
                    index = text.indexOf("*/", index + 1).let { if (it < 0) text.length else it + 2 }
                c == '$' && index < text.length -> {
                    val value = variable()
                    token.append(value)
                    pending = pending || value.isNotEmpty()
                }
                c == '\\' -> if (index < text.length && text[index] != '\n' && text[index] != '\r') {
                    token.append(text[index++])
                    pending = true
                }
                c == '\'' -> {
                    pending = true
                    val end = text.indexOf('\'', index).let { if (it < 0) text.length else it }
                    token.append(text, index, end)
                    index = minOf(end + 1, text.length)
                }
                c == '"' -> {
                    pending = true
                    while (index < text.length) {
                        val quoted = text[index++]
                        when {
                            quoted == '"' -> break
                            quoted == '\\' && index < text.length && (text[index] == '\\' || text[index] == '"') ->
                                token.append(text[index++])
                            quoted == '$' && index < text.length -> token.append(variable())
                            else -> token.append(quoted)
                        }
                    }
                }
                else -> {
                    token.append(c)
                    pending = true
                }
            }
        }
        finish()
        return tokens
    }

    /**
     * The files, or [directories], that [pattern] names below [directory]: `?` stands for one character
     * of a name, `*` for any number, `...` for any number of directories, and a trailing `/` for every
     * file of a directory. A path without these is returned whether it exists or not, so that the tool
     * given it can report it missing.
     */
    private fun paths(directory: Path, pattern: String, directories: Boolean): List<Path> {
        val segments = (if (!directories && pattern.endsWith("/")) "$pattern*" else pattern)
            .split('/', File.separatorChar)
        val first = segments.indexOfFirst { it == "..." || '*' in it || '?' in it }
        try {
            if (first < 0) return listOf(directory.resolve(pattern).normalize())
            val fixed = segments.take(first).joinToString("/").ifEmpty { if (pattern.startsWith("/")) "/" else "" }
            return matches(directory.resolve(fixed), segments.drop(first))
                .filter { if (directories) it.isDirectory() else it.isRegularFile() }
                .map { it.normalize() }.distinct().sorted()
        } catch (e: Exception) { // An invalid path, or a directory that cannot be listed.
            errors += "Cannot find '$pattern' of file list '${active.last()}'."
            return emptyList()
        }
    }

    private fun matches(directory: Path, segments: List<String>): List<Path> {
        val segment = segments.firstOrNull() ?: return listOf(directory)
        val rest = segments.drop(1)
        if (!directory.isDirectory()) return emptyList()
        return when {
            segment == "..." -> Files.walk(directory).use { all -> all.filter { it.isDirectory() }.toList() }
                .flatMap { matches(it, rest) }
            '*' in segment || '?' in segment -> {
                val name = Regex(segment.map { if (it == '*') ".*" else if (it == '?') "." else Regex.escape(it.toString()) }
                    .joinToString(""))
                directory.listDirectoryEntries().filter { name.matches(it.name) }.flatMap { matches(it, rest) }
            }
            else -> matches(directory.resolve(segment), rest)
        }
    }
}
