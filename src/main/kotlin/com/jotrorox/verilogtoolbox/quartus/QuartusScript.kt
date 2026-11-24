package com.jotrorox.verilogtoolbox.quartus

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One command of a Quartus settings file (`.qsf`, `.qip`), which is a Tcl script: its words after
 * substitution, and where it stands. [start] and [end] are offsets in the text of [file], or of the text
 * given to [QuartusScript.commands]; [end] is exclusive and leaves out the line break.
 */
data class QuartusCommand(val words: List<String>, val start: Int, val end: Int, val file: Path? = null) {
    val command: String get() = words.first()

    /** The value of [option], such as `-name` or `-to`; null when the command does not have it. */
    fun option(option: String): String? {
        var index = 1
        while (index < words.size) {
            val word = words[index]
            if (word in VALUE_OPTIONS) {
                if (word == option) return words.getOrNull(index + 1)
                index += 2
            } else {
                index++
            }
        }
        return null
    }

    fun hasFlag(flag: String): Boolean = positionalOrFlags().second.contains(flag)

    /** What is neither an option nor a flag: the value of an assignment, or the location of a location assignment. */
    val positional: List<String> get() = positionalOrFlags().first

    val value: String? get() = positional.firstOrNull()

    /** `FAMILY` of `set_global_assignment -name FAMILY "MAX 10"`, in upper case as Quartus compares names. */
    val name: String? get() = option("-name")?.uppercase()

    val to: String? get() = option("-to")

    /** Whether the assignment is switched off or takes another one back; neither assigns anything. */
    val isInactive: Boolean get() = hasFlag("-disable") || hasFlag("-remove")

    private fun positionalOrFlags(): Pair<List<String>, List<String>> {
        val positional = mutableListOf<String>()
        val flags = mutableListOf<String>()
        var index = 1
        while (index < words.size) {
            val word = words[index]
            when (word) {
                in VALUE_OPTIONS -> index++
                in FLAGS -> flags += word
                else -> positional += word
            }
            index++
        }
        return positional to flags
    }

    companion object {
        const val GLOBAL = "set_global_assignment"
        const val INSTANCE = "set_instance_assignment"
        const val LOCATION = "set_location_assignment"
        const val PARAMETER = "set_parameter"

        /** The options of the assignment commands that take a value. */
        private val VALUE_OPTIONS =
            setOf("-name", "-to", "-from", "-section_id", "-entity", "-library", "-tag", "-comment", "-hdl_version")
        private val FLAGS = setOf("-remove", "-disable", "-rise", "-fall")
    }
}

/**
 * Reads the Tcl of Quartus settings files as far as they use it: one command per line, comments, quotes,
 * braces, variables, and the `[file join $::quartus(qip_path) "x.v"]` of `.qip` files. Control flow is not
 * run; the commands inside its braces are not seen.
 */
object QuartusScript {
    /**
     * The commands of [text], without those of files it names with `source`. [variables] are known from
     * the start, under the names scripts use, such as `::quartus(qip_path)`.
     */
    fun commands(text: String, file: Path? = null, variables: Map<String, String> = emptyMap()): List<QuartusCommand> {
        val known = variables.toMutableMap()
        return Reader(text).commands().mapNotNull { raw ->
            val words = raw.words.map { if (it.literal) it.text else substitute(it.text, known, file) }
            if (words.isEmpty()) return@mapNotNull null
            if (words[0] == "set" && words.size == 3) known[words[1].removePrefix("::")] = words[2]
            QuartusCommand(words, raw.start, raw.end, file)
        }
    }

    /**
     * The commands of [file] and, in their place, those of the scripts it runs with `source`, whose
     * relative paths start at [directory], where Quartus runs. Empty when the file cannot be read.
     */
    fun read(file: Path, directory: Path, variables: Map<String, String> = emptyMap()): List<QuartusCommand> =
        read(file.toAbsolutePath().normalize(), directory, variables, mutableListOf())

    private fun read(file: Path, directory: Path, variables: Map<String, String>, active: MutableList<Path>): List<QuartusCommand> {
        if (file in active) return emptyList()
        val text = try {
            Files.readString(file)
        } catch (_: IOException) {
            return emptyList()
        }
        active.add(file)
        try {
            return read(text, file, directory, variables, active)
        } finally {
            active.removeLast()
        }
    }

    /** As [read], for the [text] of [file] when that is not what is on disk, as in an editor. */
    fun read(text: String, file: Path, directory: Path, variables: Map<String, String> = emptyMap()): List<QuartusCommand> =
        read(text, file, directory, variables, mutableListOf(file.toAbsolutePath().normalize()))

    private fun read(
        text: String, file: Path, directory: Path, variables: Map<String, String>, active: MutableList<Path>,
    ): List<QuartusCommand> =
        commands(text, file, variables).flatMap { command ->
            if (command.command == "source" && command.words.size == 2) {
                val nested = runCatching { directory.resolve(command.words[1]).normalize() }.getOrNull()
                if (nested == null) emptyList() else read(nested, directory, variables, active)
            } else {
                listOf(command)
            }
        }

    /** A word as it has to be written to be read back as [value]: quoted when it holds more than plain characters. */
    fun quote(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "_-./:[]|~*?+,=" } && !value.startsWith("-")) {
            value
        } else {
            // Brackets stay as they are: Quartus writes "data[0]" itself and reads it back as that.
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        }

    private class RawWord(val text: String, val literal: Boolean)
    private class RawCommand(val words: List<RawWord>, val start: Int, val end: Int)

    private class Reader(private val text: String) {
        private var index = 0

        fun commands(): List<RawCommand> {
            val commands = mutableListOf<RawCommand>()
            while (true) {
                skipBetweenCommands()
                if (index >= text.length) return commands
                if (text[index] == '#') {
                    skipComment()
                    continue
                }
                val start = index
                val words = mutableListOf<RawWord>()
                var end = index
                while (index < text.length) {
                    skipBlanks()
                    if (index >= text.length || text[index] == '\n' || text[index] == '\r' || text[index] == ';') break
                    words += word()
                    end = index
                }
                if (words.isNotEmpty()) commands += RawCommand(words, start, end)
            }
        }

        private fun skipBetweenCommands() {
            while (index < text.length && (text[index].isWhitespace() || text[index] == ';')) index++
        }

        /** Spaces, tabs and line continuations, which separate the words of a command. */
        private fun skipBlanks() {
            while (index < text.length) {
                val c = text[index]
                if (c == ' ' || c == '\t') index++
                else if (c == '\\' && continuesLine(index)) index = afterContinuation(index)
                else break
            }
        }

        private fun continuesLine(at: Int): Boolean =
            text.startsWith("\\\n", at) || text.startsWith("\\\r\n", at)

        private fun afterContinuation(at: Int): Int = if (text.startsWith("\\\r\n", at)) at + 3 else at + 2

        /** A comment ends with its line, unless a backslash continues that. */
        private fun skipComment() {
            while (index < text.length && text[index] != '\n') {
                if (text[index] == '\\' && continuesLine(index)) index = afterContinuation(index) else index++
            }
        }

        private fun word(): RawWord {
            when (text[index]) {
                '{' -> {
                    val close = closing(index, '{', '}')
                    val content = text.substring(index + 1, minOf(close, text.length))
                    index = minOf(close + 1, text.length)
                    return RawWord(content, literal = true)
                }
                '"' -> {
                    val start = ++index
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\' && index + 1 < text.length) index++
                        index++
                    }
                    val content = text.substring(start, index)
                    if (index < text.length) index++
                    return RawWord(content, literal = false)
                }
            }
            val start = index
            while (index < text.length) {
                val c = text[index]
                when {
                    c == '\\' && continuesLine(index) -> break
                    c == '\\' && index + 1 < text.length -> index += 2
                    // A command substitution is one word however many spaces it has.
                    c == '[' -> index = minOf(closing(index, '[', ']') + 1, text.length)
                    c.isWhitespace() || c == ';' -> break
                    else -> index++
                }
            }
            return RawWord(text.substring(start, index), literal = false)
        }

        /** The offset of the [close] that matches the [open] at [from], or the end of the text. */
        private fun closing(from: Int, open: Char, close: Char): Int {
            var depth = 0
            var at = from
            while (at < text.length) {
                when (text[at]) {
                    '\\' -> at++
                    open -> depth++
                    close -> if (--depth == 0) return at
                }
                at++
            }
            return text.length
        }
    }

    /** Replaces the variables, command substitutions and backslash escapes of [word]. */
    private fun substitute(word: String, variables: Map<String, String>, file: Path?): String {
        if ('$' !in word && '[' !in word && '\\' !in word) return word
        val result = StringBuilder()
        var index = 0
        while (index < word.length) {
            val c = word[index]
            when {
                c == '\\' && index + 1 < word.length -> {
                    result.append(
                        when (val escaped = word[index + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> escaped
                        }
                    )
                    index += 2
                }
                c == '$' -> {
                    val end = variableEnd(word, index + 1)
                    val name = word.substring(index + 1, end).removeSurrounding("{", "}").removePrefix("::")
                    val value = if (name.isEmpty()) null else variables[name] ?: environment(name)
                    // A variable this reader does not know stays as written, which at least shows in messages.
                    result.append(value ?: word.substring(index, end))
                    index = end
                }
                c == '[' -> {
                    var depth = 0
                    var end = index
                    while (end < word.length) {
                        if (word[end] == '[') depth++ else if (word[end] == ']' && --depth == 0) break
                        end++
                    }
                    val inner = word.substring(index + 1, minOf(end, word.length))
                    val words = commands(inner, file, variables).firstOrNull()?.words
                    result.append(words?.let { call(it, file) } ?: word.substring(index, minOf(end + 1, word.length)))
                    index = minOf(end + 1, word.length)
                }
                else -> {
                    result.append(c)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun variableEnd(word: String, from: Int): Int {
        if (from < word.length && word[from] == '{') {
            return word.indexOf('}', from).let { if (it < 0) word.length else it + 1 }
        }
        var end = from
        while (end < word.length && (word[end].isLetterOrDigit() || word[end] == '_' || word[end] == ':')) end++
        // An array element, such as quartus(qip_path).
        if (end > from && end < word.length && word[end] == '(') {
            val close = word.indexOf(')', end)
            if (close > 0) end = close + 1
        }
        return end
    }

    private fun environment(name: String): String? =
        if (name.startsWith("env(") && name.endsWith(")")) System.getenv(name.substring(4, name.length - 1)) else null

    /** The few commands settings files compute paths with; null for any other, which is then left as written. */
    private fun call(words: List<String>, file: Path?): String? = when {
        words.size >= 3 && words[0] == "file" && words[1] == "join" ->
            words.drop(2).fold("") { path, part ->
                if (path.isEmpty() || part.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(part)) part
                else path.trimEnd('/') + "/" + part
            }
        words.size == 3 && words[0] == "file" && words[1] == "dirname" ->
            words[2].replace('\\', '/').substringBeforeLast('/', ".")
        words.size == 3 && words[0] == "file" && words[1] == "tail" -> words[2].replace('\\', '/').substringAfterLast('/')
        words.size == 3 && words[0] == "file" && words[1] == "normalize" -> words[2]
        words.size == 2 && words[0] == "info" && words[1] == "script" -> file?.toString()?.replace('\\', '/')
        else -> null
    }
}
