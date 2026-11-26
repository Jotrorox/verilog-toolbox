package com.jotrorox.verilogtoolbox.gowin.eda

import com.jotrorox.verilogtoolbox.quartus.QuartusMessage
import com.jotrorox.verilogtoolbox.quartus.QuartusReportDocument
import com.jotrorox.verilogtoolbox.quartus.QuartusReportDocument.Cell
import com.jotrorox.verilogtoolbox.quartus.QuartusReportDocument.Row
import com.jotrorox.verilogtoolbox.quartus.QuartusReportDocument.Section
import com.jotrorox.verilogtoolbox.quartus.QuartusSeverity

/**
 * GOWIN EDA's reports taken apart into the sections of the report viewer, which Quartus's reports are shown in
 * too: the place and route report (`<name>.rpt.txt`), the timing report (`<name>.tr`), the logs of synthesis and
 * of place and route, and the HTML reports (synthesis, power, pins). Sections under a heading of their own,
 * such as the clock summary under `Timing Summaries`, are titled `Timing Summaries: Clock Summary`, which the
 * viewer lists under one node.
 */
object GowinEdaReportDocument {
    enum class Kind { PNR, TIMING, LOG, HTML }

    /** The HTML reports read; the timing report's frames (`.tr.html`) are left to the browser, its text is read instead. */
    private val HTML_SUFFIXES = listOf("_syn.rpt.html", "_syn_resource.html", ".rpt.html", ".pin.html", ".power.html")

    /** What kind of report a file called [name] is; null for none. */
    fun kind(name: String): Kind? = when {
        name.endsWith(".rpt.txt") -> Kind.PNR
        name.endsWith(".tr") -> Kind.TIMING
        name.endsWith(".log") -> Kind.LOG
        HTML_SUFFIXES.any { name.endsWith(it) } -> Kind.HTML
        else -> null
    }

    /** Whether the file at [path] is a report the flow wrote: one of [kind] in `impl/pnr` or `impl/gwsynthesis`. */
    fun isReport(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        if (parts.size < 3 || parts[parts.size - 3] != GowinEdaProject.IMPL || parts[parts.size - 2] !in OUTPUT_DIRECTORIES) return false
        return kind(parts.last()) != null
    }

    private val OUTPUT_DIRECTORIES = setOf("pnr", "gwsynthesis")

    fun parse(text: CharSequence, name: String): QuartusReportDocument = when (kind(name)) {
        Kind.PNR -> pnr(text.lines())
        Kind.TIMING -> timing(text.toString())
        Kind.LOG -> log(text.toString())
        Kind.HTML -> html(text.toString())
        null -> QuartusReportDocument(name, emptyList(), listOf(QuartusReportDocument.Text(name, 0, text.toString())))
    }

    /** A message of GOWIN EDA as the viewer shows messages: notes are information. */
    internal fun quartusMessage(message: GowinEdaMessage): QuartusMessage = QuartusMessage(
        when (message.severity) {
            GowinEdaSeverity.ERROR -> QuartusSeverity.ERROR
            GowinEdaSeverity.WARNING -> QuartusSeverity.WARNING
            GowinEdaSeverity.NOTE, GowinEdaSeverity.INFO -> QuartusSeverity.INFO
        },
        message.code, message.text, message.file, message.line,
    )

    /** A section waiting for its title, which has its heading in front only when the heading has more than one section under it. */
    private class Pending(val group: String?, val name: String, val build: (String) -> Section)

    private fun titled(pending: List<Pending>): List<Section> {
        val counts = pending.groupingBy { it.group }.eachCount()
        val used = HashMap<String, Int>()
        return pending.map { section ->
            val base = if (section.group != null && (counts[section.group] ?: 0) > 1) "${section.group}: ${section.name}" else section.name
            val count = used.merge(base, 1, Int::plus)!!
            section.build(if (count == 1) base else "$base ($count)")
        }
    }

    // ---- Text reports ------------------------------------------------------------------------------------

    /** `1. PnR Messages`, `2.3 Max Frequency Summary`, `3.1.1 Setup Paths Table`: a heading, at the start of a line. */
    private val HEADING = Regex("""^((?:\d+\.)+\d*)\s+([A-Za-z].*?)\s*$""")
    private val UNDERLINE = Regex("""^\s*=+(\s+=+)+\s*$""")
    private val RULE = Regex("""^\s*[-=]{4,}\s*$""")
    private val ANGLE_VALUE = Regex("""^\s*<([^>]+)>\s*:\s*(.*?)\s*$""")
    private val PLAIN_VALUE = Regex("""^(\s*)([A-Za-z][\w /().,-]*?)\s*:\s*(.*?)\s*$""")

    /** A `<Name>: value` or `Name : value` line as the name and the value, and how far it is set in. */
    private fun keyValue(line: String, strict: Boolean): Triple<String, String, Int>? {
        ANGLE_VALUE.matchEntire(line)?.let { return Triple(it.groupValues[1], it.groupValues[2], 0) }
        if (strict) return null
        val match = PLAIN_VALUE.matchEntire(line) ?: return null
        return Triple(match.groupValues[2], match.groupValues[3], match.groupValues[1].length)
    }

    /**
     * The section of the lines [start] until [end], under [group]: a table when they hold one, set in columns
     * by `|` or under a line of `=`; names and values when every line is one; else text.
     */
    private fun body(group: String?, name: String, lines: List<String>, start: Int, end: Int): Pending? {
        val indices = (start until end).filter { lines[it].isNotBlank() }
        if (indices.isEmpty()) return null
        val first = indices.first()
        val underline = indices.firstOrNull { UNDERLINE.matches(lines[it]) && it - 1 >= start && lines[it - 1].isNotBlank() }
        val table = when {
            underline != null -> underlined(lines, underline, end)
            indices.count { '|' in lines[it] } >= 2 -> piped(lines, indices)
            else -> null
        }
        if (table != null) {
            val (columns, rows, used) = table
            val before = indices.filter { it < used.first }.map { display(lines[it]) }
            val after = indices.filter { it > used.last && !RULE.matches(lines[it]) }.map { display(lines[it]) }
            return Pending(group, name) { title -> QuartusReportDocument.Table(title, first, columns, rows, before, after) }
        }
        val values = indices.mapNotNull { index -> keyValue(lines[index], strict = false)?.let { index to it } }
        if (values.size == indices.size) {
            val base = values.minOf { it.second.third }
            val rows = values.map { (index, value) ->
                Row(listOf(Cell(value.first, (value.third - base) / 2), Cell(value.second)), index)
            }
            return Pending(group, name) { title -> QuartusReportDocument.Table(title, first, emptyList(), rows) }
        }
        val text = (first..indices.last()).joinToString("\n") { lines[it] }.trimIndent()
        return Pending(group, name) { title -> QuartusReportDocument.Text(title, first, text) }
    }

    /** `<Report Command>:report_timing …` as `Report Command: report_timing …`. */
    private fun display(line: String): String =
        ANGLE_VALUE.matchEntire(line)?.let { "${it.groupValues[1]}: ${it.groupValues[2]}" } ?: line.trim()

    /** A table whose columns are the runs of `=` below its header, and the lines it takes. */
    private fun underlined(lines: List<String>, underline: Int, end: Int): Triple<List<String>, List<Row>, IntRange> {
        val starts = Regex("=+").findAll(lines[underline]).map { it.range.first }.toList()
        fun cells(line: String): List<String> = starts.mapIndexed { index, from ->
            val to = starts.getOrNull(index + 1) ?: line.length
            if (from >= line.length) "" else line.substring(from, minOf(to, line.length)).trim()
        }
        val rows = mutableListOf<Row>()
        var index = underline + 1
        while (index < end && lines[index].isNotBlank() && !HEADING.matches(lines[index])) {
            rows += Row(cells(lines[index]).map { Cell(it) }, index)
            index++
        }
        return Triple(cells(lines[underline - 1]), rows, (underline - 1) until index)
    }

    /** A table set in columns by `|`, its first row the header; `--` before a name sets it in, as a part of the row above. */
    private fun piped(lines: List<String>, indices: List<Int>): Triple<List<String>, List<Row>, IntRange> {
        val rowIndices = indices.filter { '|' in lines[it] && !RULE.matches(lines[it]) }
        val header = lines[rowIndices.first()]
        val base = header.length - header.trimStart().length
        val rows = rowIndices.drop(1).map { index ->
            val parts = lines[index].split('|')
            val name = parts.first()
            val spaces = name.length - name.trimStart().length - base
            var text = name.trim()
            var indent = maxOf(spaces, 0) / 2
            if (text.startsWith("--")) {
                text = text.removePrefix("--").trim()
                indent = maxOf(indent, 1)
            }
            Row(listOf(Cell(text, indent)) + parts.drop(1).map { Cell(it.trim()) }, index)
        }
        return Triple(header.split('|').map { it.trim() }, rows, rowIndices.first()..rowIndices.last())
    }

    /** The values of the `<Name>: value` lines of a header, by name. */
    private fun headerValues(lines: List<String>): Map<String, String> =
        lines.mapNotNull { ANGLE_VALUE.matchEntire(it) }.associate { it.groupValues[1] to it.groupValues[2] }

    /** The part, device and time of a report, from its header values. */
    private fun details(values: Map<String, String>): List<String> = listOfNotNull(
        values["Part Number"],
        values["Device"]?.let { device -> device + (values["Device Version"]?.takeIf { it.isNotBlank() && it != "NA" }?.let { " version $it" } ?: "") },
        values["Created Time"],
        values["Tool Version"]?.let { "GOWIN EDA $it" },
    )

    /** `PnR Report of blinky`, the design being the netlist that place and route read. */
    private fun reportTitle(values: Map<String, String>, fallback: String): String {
        val report = values["Report Title"] ?: fallback
        val design = values["Design File"]?.replace('\\', '/')?.substringAfterLast('/')?.substringBeforeLast('.')
        return if (design.isNullOrBlank()) report else "$report of $design"
    }

    /** The place and route report: numbered sections, from the messages to every pin of the package. */
    private fun pnr(lines: List<String>): QuartusReportDocument {
        val headings = lines.indices.filter { HEADING.matches(lines[it]) && lines[it].first().isDigit() }
        val pending = headings.mapIndexedNotNull { index, line ->
            val name = HEADING.matchEntire(lines[line])!!.groupValues[2]
            body(null, name, lines, line + 1, headings.getOrElse(index + 1) { lines.size })
        }
        val values = headerValues(lines.take(headings.getOrElse(1) { lines.size }))
        return QuartusReportDocument(reportTitle(values, "PnR Report"), details(values), titled(pending))
    }

    /** `Path1`, `MPW3`: where a path of a detailed analysis starts. */
    private val PATH = Regex("""^\s*(Path|MPW)(\d+)\s*$""")

    /**
     * The timing report, from its core report on: the summaries, the tables of paths, and each path of the
     * detailed analyses as a section of its own, under the analysis.
     */
    private fun timing(text: String): QuartusReportDocument {
        val lines = text.lines()
        val core = lines.indexOfFirst { it.trim() == "Note:Core Timing Report" }.let { if (it < 0) 0 else it + 1 }
        val headings = (core until lines.size).filter { HEADING.matches(lines[it]) && lines[it].first().isDigit() }
        val numbered = headings.map { line ->
            val match = HEADING.matchEntire(lines[line])!!
            Triple(line, match.groupValues[1].trimEnd('.').split('.').filter { it.isNotEmpty() }, match.groupValues[2])
        }
        val pending = mutableListOf<Pending>()
        for ((index, heading) in numbered.withIndex()) {
            val (line, number, name) = heading
            val end = numbered.getOrNull(index + 1)?.first ?: lines.size
            // The heading one level up, which the section is listed under.
            val parent = numbered.subList(0, index).lastOrNull { it.second.size == number.size - 1 && number.take(it.second.size) == it.second }?.third
            val paths = (line + 1 until end).filter { PATH.matches(lines[it]) }
            if (paths.isEmpty()) {
                body(parent, name, lines, line + 1, end)?.let { pending += it }
                continue
            }
            for ((at, start) in paths.withIndex()) {
                val stop = paths.getOrElse(at + 1) { end }
                val match = PATH.matchEntire(lines[start])!!
                val block = (start + 1 until stop).map { lines[it] }
                val summary = block.mapNotNull { keyValue(it, strict = false) }.associate { it.first to it.second }
                val label = listOfNotNull(
                    "${if (match.groupValues[1] == "MPW") "Pulse" else "Path"} ${match.groupValues[2]}",
                    summary["Slack"]?.let { "slack $it" },
                    summary["From"]?.let { from -> summary["To"]?.let { to -> "$from → $to" } } ?: summary["Objects"],
                ).joinToString(", ")
                val body = block.joinToString("\n").trim('\n').trimIndent()
                pending += Pending(name, label) { title -> QuartusReportDocument.Text(title, start, body) }
            }
        }
        val values = headerValues(lines.subList(core, numbered.getOrNull(1)?.first ?: lines.size))
        return QuartusReportDocument(reportTitle(values, "Timing Analysis Report"), details(values), titled(pending), timingVerdict(text))
    }

    /** Whether timing is met, with the worst setup and hold slack, or what fails. */
    private fun timingVerdict(text: String): String? {
        val timing = GowinEdaReports.timing(text)
        if (timing.worst.isEmpty() && timing.setupViolations == null) return null
        val failing = timing.worst.filter { it.slack < 0 }
        return if (timing.isMet) {
            val shown = timing.worst.filter { it.check == "Setup" || it.check == "Hold" }.joinToString(", ") { "${it.check.lowercase()} slack ${it.slack} ns" }
            "Timing requirements met" + if (shown.isEmpty()) "" else " ($shown)"
        } else {
            val violations = (timing.setupViolations ?: 0) + (timing.holdViolations ?: 0)
            "Timing requirements not met: " + (failing.joinToString(", ") { "${it.check.lowercase()} slack ${it.slack} ns" }
                .ifEmpty { "$violations failing endpoints" })
        }
    }

    /** A log: its messages, which lead to the source, and all of it as text. */
    private fun log(text: String): QuartusReportDocument {
        val lines = text.lines()
        val messages = lines.mapIndexedNotNull { index, line -> GowinEdaMessage.parse(line)?.let { quartusMessage(it) to index } }
        val synthesis = "GowinSynthesis start" in text
        val errors = messages.count { it.first.severity == QuartusSeverity.ERROR }
        val verdict = when {
            errors > 0 -> "Failed with $errors error" + if (errors == 1) "" else "s"
            synthesis && "GowinSynthesis finish" in text -> "Successful"
            !synthesis && "Placement and routing completed" in text -> "Successful"
            else -> null
        }
        val sections = listOfNotNull(
            messages.takeIf { it.isNotEmpty() }?.let { QuartusReportDocument.Messages("Messages", it.first().second, it) },
            QuartusReportDocument.Text("Log", 0, text.trimEnd()),
        )
        val title = if (synthesis) "GowinSynthesis Log" else "Place & Route Log"
        val details = listOfNotNull(lines.lastOrNull { DATE.matches(it.trim()) }?.trim())
        return QuartusReportDocument(title, details, sections, verdict)
    }

    /** `Thu Nov 26 11:43:35 2026`, which place and route ends its log with. */
    private val DATE = Regex("""^[A-Z][a-z]{2} [A-Z][a-z]{2} +\d+ \d{2}:\d{2}:\d{2} \d{4}$""")

    // ---- HTML reports ------------------------------------------------------------------------------------

    private val TOKEN = Regex(
        """<h([1-6])\b[^>]*>(.*?)</h\1\s*>|<table\b[^>]*>(.*?)</table\s*>|<b>(.*?)</b>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ROW = Regex("""<tr\b[^>]*>(.*?)</tr\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL = Regex("""<(t[dh])\b[^>]*>(.*?)</t[dh]\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** Tags, but not the `<1%` GOWIN EDA writes unescaped. */
    private val TAG = Regex("""</?[A-Za-z][^>]*>""")
    private val LEADING_SPACE = Regex("""^(?:\s|&nbsp;?)+""", RegexOption.IGNORE_CASE)
    private val BOLD = Regex("""^\s*<b>.*</b>\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** The text of HTML: tags left out, entities decoded, the lines of a `<br>` joined with `; `. */
    internal fun plain(html: String): String =
        BREAK.split(html).map { part -> decode(TAG.replace(part, "")).replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }.joinToString("; ")

    private fun decode(text: String): String =
        text.replace(Regex("""&nbsp;?""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""&#(\d+);""")) { match -> match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * An HTML report: a table per section, titled by the headings above it and the bold caption before it, as
     * `Timing: Clock Summary` or `Path 1: Data Arrival Path`. A first row of `<th>` or of bold cells names the columns.
     */
    private fun html(text: String): QuartusReportDocument {
        val lineStarts = text.indices.filter { text[it] == '\n' }
        fun line(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it < 0) -it - 1 else it }
        val headings = arrayOfNulls<String>(7)
        var caption: String? = null
        val pending = mutableListOf<Pending>()
        var header = emptyMap<String, String>()
        var verdict: String? = null
        for (token in TOKEN.findAll(text)) {
            val heading = token.groups[1]
            val table = token.groups[3]
            val bold = token.groups[4]
            when {
                heading != null -> {
                    val level = heading.value.toInt()
                    headings[level] = plain(token.groupValues[2]).removeSuffix(":").trim()
                    for (deeper in level + 1 until headings.size) headings[deeper] = null
                    caption = null
                }
                bold != null -> caption = plain(bold.value).removeSuffix(":").trim().ifEmpty { null }
                table != null -> {
                    val rows = ROW.findAll(table.value).map { row ->
                        val cells = CELL.findAll(row.groupValues[1]).map { it.groupValues[1].lowercase() to it.groupValues[2] }.toList()
                        Triple(line(table.range.first + row.range.first), cells, row)
                    }.filter { it.second.isNotEmpty() }.toList()
                    if (rows.isEmpty()) continue
                    val first = rows.first().second
                    val named = first.all { it.first == "th" } || first.all { BOLD.matches(it.second) }
                    val columns = if (named) first.map { plain(it.second) } else emptyList()
                    val body = (if (named) rows.drop(1) else rows).map { (index, cells, _) -> Row(cells.mapIndexed { column, (_, html) -> cell(html, column) }, index) }
                    val values = body.filter { it.cells.size == 2 }.associate { it.cells[0].text to it.cells[1].text }
                    if (header.isEmpty() && "Report Title" in values) header = values
                    if (verdict == null) values.entries.firstOrNull { it.key.startsWith("Total Power", ignoreCase = true) }?.let { verdict = "${it.key}: ${it.value}" }
                    val deepest = (headings.size - 1 downTo 1).firstOrNull { headings[it] != null }
                    val (group, name) = when {
                        caption != null -> deepest?.let { headings[it] } to caption!!
                        deepest != null && deepest > 1 -> (deepest - 1 downTo 1).firstNotNullOfOrNull { headings[it] } to headings[deepest]!!
                        else -> null to (deepest?.let { headings[it] } ?: "Table")
                    }
                    val start = line(table.range.first)
                    pending += Pending(group, name) { title -> QuartusReportDocument.Table(title, start, columns, body) }
                    caption = null
                }
            }
        }
        val title = header["Report Title"]?.let { report ->
            val design = header["Design File"]?.substringBefore(';')?.replace('\\', '/')?.substringAfterLast('/')?.substringBeforeLast('.')
            if (design.isNullOrBlank()) report else "$report of $design"
        }
        return QuartusReportDocument(title, details(header), titled(pending), verdict)
    }

    /** A cell, set in by the spaces it starts with or the `--` of a part of the row above. */
    private fun cell(html: String, column: Int): Cell {
        val leading = LEADING_SPACE.find(html)?.value.orEmpty()
        var text = plain(html)
        var indent = if (column == 0 && Regex("&nbsp", RegexOption.IGNORE_CASE).containsMatchIn(leading)) 1 else 0
        if (column == 0 && text.startsWith("--")) {
            text = text.removePrefix("--").trim()
            indent = 1
        }
        return Cell(text, indent)
    }
}
