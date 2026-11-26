package com.jotrorox.verilogtoolbox.sby

/**
 * What the plugin reads from a `.sby` file itself: its tasks, the files it copies and the top module. Tasks
 * that Python code makes up are not known here; SymbiYosys still runs them, and they show up in the run.
 */
object SbyConfig {
    /** A task of the `[tasks]` section; [offset] is where its name is. */
    data class Task(val name: String, val offset: Int, val isDefault: Boolean)

    /** A line of `[files]`: the file is copied from [source] to [destination] in the task's `src` directory. */
    data class FileEntry(val destination: String, val source: String)

    private class Line(val text: String, val offset: Int)

    /** The lines of each section, by the section's header (without brackets), comments left out. */
    private fun sections(text: String): List<Pair<String, List<Line>>> {
        val sections = mutableListOf<Pair<String, MutableList<Line>>>()
        var offset = 0
        for (line in text.split('\n')) {
            val header = HEADER.find(line)?.groupValues?.get(1)?.trim()
            when {
                header != null -> sections += header to mutableListOf()
                line.isNotBlank() && !line.trimStart().startsWith("#") -> sections.lastOrNull()?.second?.add(Line(line, offset))
            }
            offset += line.length + 1
        }
        return sections
    }

    private fun section(text: String, name: String): List<Line> =
        sections(text).filter { it.first.substringBefore(' ') == name }.flatMap { it.second }

    /**
     * The tasks the file declares. A line is `name tag…` or `name… : tag…`; `default` among the tags makes the
     * tasks default ones. Names with glob characters refer to tasks declared before them.
     */
    fun tasks(text: String): List<Task> {
        val tasks = linkedMapOf<String, Task>()
        for (line in section(text, "tasks")) {
            val content = line.text.substringBefore('#')
            val colon = content.indexOf(':')
            val names = WORD.findAll(if (colon >= 0) content.substring(0, colon) else content).toList()
                .let { if (colon >= 0) it else it.take(1) }
            val tags = WORD.findAll(content, if (colon >= 0) colon + 1 else names.firstOrNull()?.range?.last?.plus(1) ?: 0)
                .map { it.value }.toList()
            val isDefault = "default" in tags
            for (name in names) {
                if (name.value.any { it in GLOB }) {
                    if (!isDefault) continue
                    val pattern = glob(name.value)
                    tasks.replaceAll { key, task -> if (pattern.matches(key)) task.copy(isDefault = true) else task }
                } else {
                    val existing = tasks[name.value]
                    tasks[name.value] = existing?.copy(isDefault = existing.isDefault || isDefault)
                        ?: Task(name.value, line.offset + name.range.first, isDefault)
                }
            }
        }
        return tasks.values.toList()
    }

    /** The tasks SymbiYosys runs when it is given none: the default ones, else all. Empty for a file without tasks. */
    fun defaultTasks(text: String): List<String> {
        val tasks = tasks(text)
        return tasks.filter { it.isDefault }.ifEmpty { tasks }.map { it.name }
    }

    /** The lines of `[files]`, with task tags taken off. */
    fun files(text: String): List<FileEntry> =
        section(text, "files").mapNotNull { line ->
            val words = untagged(line.text).split(Regex("""\s+""")).filter { it.isNotEmpty() }
            when (words.size) {
                1 -> FileEntry(words[0].replace('\\', '/').substringAfterLast('/'), words[0])
                2 -> FileEntry(words[0], words[1])
                else -> null
            }
        }

    /** The top module the script names with `-top`, as in `prep -top counter`. */
    fun topModule(text: String): String? =
        section(text, "script").firstNotNullOfOrNull { TOP.find(untagged(it.text))?.groupValues?.get(1) }

    /** [line] without a `task:` in front. */
    private fun untagged(line: String): String = line.trim().replaceFirst(TAG, "").trim()

    private fun glob(pattern: String): Regex =
        Regex(pattern.map { c ->
            when (c) {
                '*' -> ".*"
                '?' -> "."
                '[', ']' -> c.toString()
                else -> Regex.escape(c.toString())
            }
        }.joinToString(""))

    private val HEADER = Regex("""^\s*\[(.*)]\s*$""")
    private val WORD = Regex("""[^\s:]+""")
    private val TAG = Regex("""^~?[A-Za-z_]\w*:(?=\s|$)""")
    private val TOP = Regex("""(?:^|\s)-top\s+(\S+)""")
    private const val GLOB = "?*.[]|()"
}
