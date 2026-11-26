package com.jotrorox.verilogtoolbox.quartus

/**
 * A Quartus text report taken apart into its sections, for the report viewer. A report is a header, a table
 * of contents and then one box per section:
 *
 * ```
 * +--------------------------------------+
 * ; Fitter Settings                      ;      title
 * +----------------------+---------------+
 * ; Option               ; Setting       ;      column names, when the table has them
 * +----------------------+---------------+
 * ; Device               ; 10M50DAF484C7G;      rows
 * +----------------------+---------------+
 * Note: ...                                     notes, up to the next empty line
 * ```
 *
 * A box whose title is followed by text instead of rows, such as the messages, holds that text. So do the
 * sections set between lines of dashes, such as the legal notice. [line]s count from 0.
 */
class QuartusReportDocument(
    /** The first line of the report, such as `Fitter report for blinky`. */
    val title: String?,
    /** The lines below the title: the date and the version of Quartus. */
    val details: List<String>,
    val sections: List<Section>,
) {
    sealed class Section {
        abstract val title: String
        abstract val line: Int

        /** Text above the box, such as the encoding of a state machine. */
        abstract val preface: List<String>

        /** Text below the box, such as the notes that explain a table. */
        abstract val notes: List<String>

        /** Whether [query] appears in what the section shows, the title left out. */
        abstract fun contains(query: String): Boolean
    }

    /** A table. [columns] is empty for those that have no column names, such as the summaries. */
    class Table(
        override val title: String,
        override val line: Int,
        val columns: List<String>,
        val rows: List<Row>,
        override val preface: List<String> = emptyList(),
        override val notes: List<String> = emptyList(),
    ) : Section() {
        /** The number of columns of the widest row or of the header. */
        val width: Int = maxOf(columns.size, rows.maxOfOrNull { it.cells.size } ?: 0)

        override fun contains(query: String): Boolean =
            rows.any { row -> row.cells.any { it.text.contains(query, ignoreCase = true) } } ||
                notes.any { it.contains(query, ignoreCase = true) }

        /** The value of the row whose first cell is [name], for the two-column summaries. */
        fun value(name: String): String? =
            rows.firstOrNull { it.cells.firstOrNull()?.text == name }?.cells?.getOrNull(1)?.text
    }

    class Row(val cells: List<Cell>, val line: Int)

    /** A cell, its text trimmed. [indent] is how deep it is set in, as in a hierarchy or a part of the row above. */
    data class Cell(val text: String, val indent: Int = 0)

    /** A section of plain text. */
    class Text(
        override val title: String,
        override val line: Int,
        val text: String,
        override val preface: List<String> = emptyList(),
        override val notes: List<String> = emptyList(),
    ) : Section() {
        override fun contains(query: String): Boolean = text.contains(query, ignoreCase = true)
    }

    /** What a tool printed while it ran, one message per line, with the [line] each one is on. */
    class Messages(
        override val title: String,
        override val line: Int,
        val messages: List<Pair<QuartusMessage, Int>>,
        override val preface: List<String> = emptyList(),
        override val notes: List<String> = emptyList(),
    ) : Section() {
        override fun contains(query: String): Boolean = messages.any { it.first.text.contains(query, ignoreCase = true) }

        /** The number of top-level messages of [severity]. */
        fun count(severity: QuartusSeverity): Int = messages.count { it.first.depth == 0 && it.first.severity == severity }

        /** The most serious severity of a top-level message, null when there are none. */
        val worst: QuartusSeverity? get() = messages.filter { it.first.depth == 0 }.minOfOrNull { it.first.severity }
    }

    /** The summary at the top of the report, such as `Fitter Summary`: its first table whose title ends in `Summary`. */
    val summary: Table? get() = sections.filterIsInstance<Table>().firstOrNull { it.title.endsWith("Summary") && it.columns.isEmpty() }

    /** `Fitter Status` and the like of the summary: `Successful - Mon Nov 16 11:56:52 2026`. */
    val status: String?
        get() = summary?.rows?.firstOrNull { it.cells.firstOrNull()?.text?.endsWith(" Status") == true }?.cells?.getOrNull(1)?.text

    /**
     * For a timing report, which has no status: whether the requirements are met, from the worst-case slack of
     * each kind of check over all operating corners. Null for other reports.
     */
    val timing: String?
        get() {
            val table = sections.filterIsInstance<Table>().firstOrNull { it.title == "Multicorner Timing Analysis Summary" } ?: return null
            val worst = table.rows.firstOrNull { it.cells.firstOrNull()?.text == "Worst-case Slack" } ?: return null
            val slacks = table.columns.drop(1).zip(worst.cells.drop(1))
                .mapNotNull { (check, cell) -> cell.text.toDoubleOrNull()?.let { check to it } }
            if (slacks.isEmpty()) return null
            val failing = slacks.filter { it.second < 0 }
            val shown = failing.ifEmpty { slacks.filter { it.first == "Setup" || it.first == "Hold" } }
            val list = shown.joinToString(", ") { "${it.first.lowercase()} slack ${it.second} ns" }
            return if (failing.isEmpty()) "Timing requirements met" + (if (list.isEmpty()) "" else " ($list)")
            else "Timing requirements not met: $list"
        }

    companion object {
        /** The sections every report has and nobody reads: the viewer lists the sections itself. */
        private val LEFT_OUT = setOf("Table of Contents", "Legal Notice")
        private val PIN_TABLE_HEADER = Regex("""^\s*Pin Name/Usage\s*:""")

        fun parse(text: CharSequence): QuartusReportDocument {
            val lines = text.lines()
            return if (lines.any { PIN_TABLE_HEADER.containsMatchIn(it) }) pinFile(lines) else report(lines)
        }

        private fun boxStart(lines: List<String>, i: Int): Boolean =
            lines[i].startsWith("+-") && lines.getOrNull(i + 1)?.startsWith(";") == true

        private fun dashedStart(lines: List<String>, i: Int): Boolean =
            lines[i].startsWith("--") && lines.getOrNull(i + 1)?.startsWith(";") == true &&
                lines.getOrNull(i + 2)?.startsWith("--") == true

        private fun sectionStart(lines: List<String>, i: Int): Boolean = boxStart(lines, i) || dashedStart(lines, i)

        private fun report(lines: List<String>): QuartusReportDocument {
            val sections = mutableListOf<Section>()
            val header = mutableListOf<String>()
            val pending = mutableListOf<String>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                when {
                    dashedStart(lines, i) -> {
                        val title = titleOf(lines[i + 1])
                        val (body, next) = textUntilNextSection(lines, i + 3)
                        if (title !in LEFT_OUT) sections += textSection(title, i, lines, body, pending.toList())
                        pending.clear()
                        i = next
                    }
                    boxStart(lines, i) -> {
                        val (section, next) = box(lines, i, pending.toList())
                        if (section.title !in LEFT_OUT) sections += section
                        pending.clear()
                        i = next
                    }
                    line.isBlank() -> {
                        pending.clear()
                        i++
                    }
                    else -> {
                        if (sections.isEmpty() && i < FIRST_SECTION_LIMIT && !lines.subList(0, i).any { it.startsWith("--") || it.startsWith("+-") }) {
                            header += line.trim()
                        } else {
                            pending += line.trim()
                        }
                        i++
                    }
                }
            }
            return QuartusReportDocument(header.firstOrNull(), header.drop(1), sections)
        }

        /** The header of a report is its first few lines; a file with text further down is something else. */
        private const val FIRST_SECTION_LIMIT = 10

        private fun titleOf(line: String): String = line.trim().trim(';').trim()

        /**
         * The indices of the lines from [start] up to the next section, without the empty lines at either end,
         * and the index where it stops.
         */
        private fun textUntilNextSection(lines: List<String>, start: Int): Pair<IntRange, Int> {
            var end = start
            while (end < lines.size && !sectionStart(lines, end)) end++
            // Text right above the next box belongs to it, such as the encoding of a state machine.
            var bodyEnd = end
            if (end < lines.size) {
                var j = end
                while (j > start && lines[j - 1].isNotBlank()) j--
                if (j > start && j < end && lines.subList(start, j).any { it.isNotBlank() }) bodyEnd = j
            }
            var first = start
            var last = bodyEnd - 1
            while (first <= last && lines[first].isBlank()) first++
            while (last >= first && lines[last].isBlank()) last--
            return (first..last) to (if (bodyEnd < end) bodyEnd else end)
        }

        private fun textSection(title: String, line: Int, lines: List<String>, body: IntRange, preface: List<String>): Section {
            if (title.endsWith(" Messages") && !title.endsWith("Suppressed Messages")) {
                val messages = body.mapNotNull { index -> QuartusMessage.parse(lines[index])?.let { it to index } }
                if (messages.isNotEmpty()) return Messages(title, line, messages, preface)
            }
            return Text(title, line, body.joinToString("\n") { lines[it] }, preface)
        }

        /** The box that starts at [start], and the index of the line after it and its notes. */
        private fun box(lines: List<String>, start: Int, preface: List<String>): Pair<Section, Int> {
            val title = titleOf(lines[start + 1])
            var i = start + 2
            // A title line closed by a rule and followed by text, not rows: a box of text, such as the messages.
            if (i >= lines.size || !lines[i].startsWith("+-") || lines.getOrNull(i + 1)?.startsWith(";") != true) {
                if (i < lines.size && lines[i].startsWith("+-")) i++
                val (body, next) = textUntilNextSection(lines, i)
                return textSection(title, start, lines, body, preface) to next
            }
            // Groups of rows between rules: all but the last are the header.
            val groups = mutableListOf<Pair<String, List<Int>>>()
            while (i < lines.size && lines[i].startsWith("+-") && lines.getOrNull(i + 1)?.startsWith(";") == true) {
                val rule = lines[i]
                i++
                val rows = mutableListOf<Int>()
                while (i < lines.size && lines[i].startsWith(";")) rows += i++
                groups += rule to rows
            }
            if (i < lines.size && lines[i].startsWith("+-")) i++
            val notes = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && !sectionStart(lines, i)) notes += lines[i++].trim()

            val body = groups.last()
            val bounds = bounds(body.first)
            val rows = body.second.map { Row(cells(lines[it], bounds), it) }
            // A table without rows is its header alone, which looks like a summary of one row. Summaries have two columns.
            if (groups.size == 1 && rows.size == 1 && rows[0].cells.size > 2) {
                return Table(title, start, rows[0].cells.map { it.text }, emptyList(), preface, notes) to i
            }
            val columns = if (groups.size > 1) {
                val headers = groups.dropLast(1).flatMap { (rule, indices) -> indices.map { cells(lines[it], bounds(rule)) } }
                // A header of several lines names each column with all of them.
                val width = headers.maxOf { it.size }
                (0 until width).map { column -> headers.mapNotNull { it.getOrNull(column)?.text?.ifEmpty { null } }.joinToString(" ") }
            } else {
                emptyList()
            }
            return Table(title, start, columns, rows, preface, notes) to i
        }

        /** The columns at which a rule such as `+------+----+` has its `+`. */
        private fun bounds(rule: String): List<Int> = rule.indices.filter { rule[it] == '+' }

        /**
         * The cells of a row. The rule above it says where the columns are, so a cell may hold a `;`; a row that
         * does not fit the rule is split at each `;`.
         */
        internal fun cells(row: String, bounds: List<Int>): List<Cell> {
            val fits = bounds.size >= 2 && bounds.all { it < row.length && row[it] == ';' }
            val raw = if (fits) {
                bounds.zipWithNext { from, to -> row.substring(from + 1, to) }
            } else {
                row.trimEnd().removePrefix(";").removeSuffix(";").split(';')
            }
            return raw.map { cell ->
                // One space pads every cell; more set it in.
                val text = cell.trim()
                val spaces = cell.length - cell.trimStart().length
                Cell(text, if (text.isEmpty()) 0 else maxOf(spaces - 1, 0))
            }
        }

        /**
         * A `.pin` file: a legend in comments, then one table with columns split at ` : `.
         *
         * ```
         * Pin Name/Usage               : Location  : Dir.   : I/O Standard      : ...
         * -------------------------------------------------------------------------
         * GND                          : A1        : gnd    :                   : ...
         * ```
         */
        private fun pinFile(lines: List<String>): QuartusReportDocument {
            val headerLine = lines.indexOfFirst { PIN_TABLE_HEADER.containsMatchIn(it) }
            val columns = lines[headerLine].split(':').map { it.trim() }
            val rows = lines.withIndex().drop(headerLine + 1)
                .filter { (_, line) -> ':' in line && !line.trimStart().startsWith("--") }
                .map { (index, line) -> Row(line.split(':').map { Cell(it.trim()) }, index) }
            // Blocks of comments: the first is the copyright, the others explain the names of the pins.
            val blocks = mutableListOf<MutableList<String>>()
            for (line in lines.subList(0, headerLine)) {
                val comment = line.trimStart()
                if (!comment.startsWith("--")) {
                    if (blocks.lastOrNull()?.isNotEmpty() == true) blocks += mutableListOf<String>()
                } else if (comment.trim('-', ' ').isNotEmpty()) {
                    if (blocks.isEmpty()) blocks += mutableListOf<String>()
                    blocks.last() += comment.removePrefix("--").removePrefix(" ")
                }
            }
            val legend = blocks.drop(1).filter { it.isNotEmpty() }.joinToString("\n\n") { it.joinToString("\n") }
            val info = lines.subList(0, headerLine).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }
            val chip = info.firstOrNull { it.startsWith("CHIP") }
            val sections = buildList {
                add(Table("Pins", headerLine, columns, rows))
                if (legend.isNotEmpty()) add(Text("Legend", 0, legend))
            }
            val title = chip?.let { Regex("""CHIP\s+"([^"]*)"\s+ASSIGNED TO AN:\s*(\S+)""").find(it) }
                ?.let { "Pin-out of ${it.groupValues[1]} on ${it.groupValues[2]}" } ?: "Pin-out"
            return QuartusReportDocument(title, info.filter { it != chip }, sections)
        }
    }
}
