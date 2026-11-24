package com.jotrorox.verilogtoolbox.quartus

import java.nio.file.Files
import java.nio.file.Path

/** How serious Quartus rates a message, most serious first. */
enum class QuartusSeverity(val label: String) {
    ERROR("Error"),
    CRITICAL_WARNING("Critical Warning"),
    WARNING("Warning"),
    INFO("Info"),
    EXTRA_INFO("Extra Info");

    companion object {
        private val BY_LABEL = entries.associateBy { it.label }
        fun ofLabel(label: String): QuartusSeverity? = BY_LABEL[label]
    }
}

/**
 * A message as the tools print it: `Warning (10230): Verilog HDL assignment warning at top.sv(9): … File:
 * C:/project/top.sv Line: 9`. [text] is without the location. [depth] is how far it is indented below the
 * message it adds detail to. [code] is null for the lines the tools print about themselves.
 */
data class QuartusMessage(
    val severity: QuartusSeverity,
    val code: String?,
    val text: String,
    val file: String? = null,
    val line: Int? = null,
    val depth: Int = 0,
) {
    companion object {
        private val MESSAGE = Regex("""^(\s*)(Error|Critical Warning|Warning|Extra Info|Info)(?: \((\d+)\))?: (.*)$""")
        private val LOCATION = Regex(""" File: (.+) Line: (\d+)\s*$""")

        /** The advice every syntax error ends with, which says nothing about the error. */
        private val KNOWLEDGE_BASE = Regex("""\s*The (?:Intel|Altera) FPGA Knowledge Database contains.*?message number\.""")
        private const val INDENT = 4

        /** Reads one line of tool output or of the messages section of a report; null for any other line. */
        fun parse(line: String): QuartusMessage? {
            val match = MESSAGE.matchEntire(line.trimEnd()) ?: return null
            val (indent, label, code, rest) = match.destructured
            val severity = QuartusSeverity.ofLabel(label) ?: return null
            val location = LOCATION.find(rest)
            val text = (if (location != null) rest.substring(0, location.range.first) else rest)
                .replace(KNOWLEDGE_BASE, "").trim()
            return QuartusMessage(
                severity, code.ifEmpty { null }, text,
                location?.groupValues?.get(1), location?.groupValues?.get(2)?.toIntOrNull(), indent.length / INDENT,
            )
        }
    }
}

/** Reads what the tools write next to the programming files: the summaries, the reports and their messages. */
object QuartusReports {
    /** One line of a timing summary: the worst slack of a kind of check for a clock, at one operating corner. */
    data class Slack(val corner: String, val check: String, val clock: String, val slack: Double, val totalNegativeSlack: Double)

    /** The highest frequency a clock can run at, at one operating corner. [restricted] takes the device's limits into account. */
    data class Fmax(val corner: String, val clock: String, val fmax: Double, val restricted: Double)

    /** A step of the last compilation and how long it took, as `hh:mm:ss`. */
    data class Step(val name: String, val elapsed: String)

    private val SUMMARY_LINE = Regex("""^(\s*)([^:]+?)\s*:\s*(.*?)\s*$""")
    private val SLACK_TYPE = Regex("""^Type\s*:\s*(.*?)\s*(Setup|Hold|Recovery|Removal|Minimum Pulse Width)\s*'(.*)'\s*$""")
    private val SLACK_VALUE = Regex("""^(Slack|TNS)\s*:\s*(-?[\d.]+)\s*$""")
    private val FMAX_TITLE = Regex("""^;\s*(.*?)\s*Fmax Summary\s*;\s*$""")
    private val FMAX_ROW = Regex("""^;\s*([\d.]+) MHz\s*;\s*([\d.]+) MHz\s*;\s*(.*?)\s*;.*$""")
    private val TABLE_TITLE = Regex("""^;\s*(.*?)\s*;\s*$""")
    private val MESSAGES_TITLE = Regex("""^;\s*.* Messages\s*;\s*$""")

    /**
     * The `Name : value` lines of a `.summary` file, such as `Total logic elements : 28 / 49,760 ( < 1 % )`,
     * in their order. Indented lines are parts of the one above and keep their indentation in the name.
     */
    fun summary(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { line ->
            val match = SUMMARY_LINE.matchEntire(line) ?: return@mapNotNull null
            val (indent, name, value) = match.destructured
            if (value.isEmpty()) null else (if (indent.isEmpty()) name else "  $name") to compact(value)
        }.toList()

    /** `28 / 49,760 ( < 1 % )` as `28 / 49,760 (< 1 %)`. */
    private fun compact(value: String): String = value.replace("( ", "(").replace(" )", ")")

    /** The worst slacks of a `.sta.summary` file. */
    fun slacks(text: String): List<Slack> {
        val slacks = mutableListOf<Slack>()
        var type: MatchResult? = null
        var slack: Double? = null
        for (line in text.lineSequence()) {
            SLACK_TYPE.matchEntire(line.trim())?.let {
                type = it
                slack = null
            }
            val value = SLACK_VALUE.matchEntire(line.trim()) ?: continue
            val number = value.groupValues[2].toDoubleOrNull() ?: continue
            if (value.groupValues[1] == "Slack") {
                slack = number
            } else {
                val (corner, check, clock) = (type ?: continue).destructured
                slacks += Slack(corner.removeSuffix("Model").trim(), check, clock, slack ?: continue, number)
                type = null
            }
        }
        return slacks
    }

    /** The Fmax tables of a `.sta.rpt` file, one row per clock and corner. */
    fun fmax(report: String): List<Fmax> {
        val rows = mutableListOf<Fmax>()
        var corner: String? = null
        for (line in report.lineSequence()) {
            val title = TABLE_TITLE.matchEntire(line)
            if (title != null && ';' !in title.groupValues[1]) {
                corner = FMAX_TITLE.matchEntire(line)?.groupValues?.get(1)?.removeSuffix("Model")?.trim()
                continue
            }
            val row = FMAX_ROW.matchEntire(line) ?: continue
            val (fmax, restricted, clock) = row.destructured
            rows += Fmax(corner ?: continue, clock, fmax.toDoubleOrNull() ?: continue, restricted.toDoubleOrNull() ?: continue)
        }
        return rows
    }

    /** The steps of the `Flow Elapsed Time` table of a `.flow.rpt` file, the total left out. */
    fun steps(flowReport: String): List<Step> {
        val lines = flowReport.lines()
        val title = lines.indexOfFirst { it.startsWith("; Flow Elapsed Time") }
        if (title < 0) return emptyList()
        return lines.drop(title + 1).takeWhile { it.isNotBlank() }
            .filter { it.startsWith(";") }
            .map { row -> row.trim(';', ' ').split(';').map { it.trim() } }
            .filter { it.size >= 2 && Regex("""\d+:\d+:\d+""").matches(it[1]) && it[0] != "Total" }
            .map { Step(it[0], it[1]) }
    }

    /**
     * The messages section of a report, which holds what the tool printed while it ran. It ends where
     * the next table starts: the Fitter's report goes on with the messages it suppressed.
     */
    fun messages(report: String): List<QuartusMessage> {
        val lines = report.lines()
        val title = lines.indexOfFirst { MESSAGES_TITLE.matches(it) && "Suppressed" !in it }
        if (title < 0) return emptyList()
        // The line below the title closes its box; the next box starts another section.
        return lines.drop(title + 2).takeWhile { !it.startsWith("+-") }
            .mapNotNull { QuartusMessage.parse(it) }.filter { isReported(it) }
    }

    /**
     * Whether a message says something about the design. The tools also print their version, the legal
     * notice and their run time as messages without a number, in the severity the run ended with.
     */
    fun isReported(message: QuartusMessage): Boolean =
        message.code != null ||
            message.depth == 0 && message.severity <= QuartusSeverity.WARNING && !ENDED.containsMatchIn(message.text)

    private val ENDED = Regex("""^Quartus Prime .* was (un)?successful\.""")

    fun read(file: Path): String? = runCatching { Files.readString(file) }.getOrNull()
}

/** What the reports of a revision say about its last compilation. Reads the files when it is created. */
class QuartusCompilation(revision: QuartusRevision) {
    /** The settings file of the revision this is about. */
    val settingsFile: Path = revision.settingsFile

    /** `Flow Status` of the flow report, such as `Successful - Thu Nov 19 15:36:25 2026`; null before a compilation. */
    val status: String?

    /** Resource usage: of the Fitter when it ran, else the estimate of synthesis. */
    val resources: List<Pair<String, String>>
    val steps: List<QuartusReports.Step>
    val slacks: List<QuartusReports.Slack>
    val fmax: List<QuartusReports.Fmax>

    /** The programming files there are, the one for the FPGA's volatile configuration first. */
    val programmingFiles: List<Path>

    /** The report files there are, by what they are about. */
    val reports: List<Pair<String, Path>>

    init {
        val flow = QuartusReports.read(revision.output("flow.rpt"))
        status = flow?.lineSequence()?.firstOrNull { it.startsWith("; Flow Status") }
            ?.trim(';', ' ')?.substringAfter(';')?.trim()
        steps = flow?.let { QuartusReports.steps(it) }.orEmpty()
        val usage = SUMMARIES.firstNotNullOfOrNull { QuartusReports.read(revision.output(it)) }
        resources = usage?.let { QuartusReports.summary(it) }.orEmpty().filter { (name, _) -> name.trim() !in NOT_RESOURCES }
        slacks = QuartusReports.read(revision.output("sta.summary"))?.let { QuartusReports.slacks(it) }.orEmpty()
        fmax = QuartusReports.read(revision.output("sta.rpt"))?.let { QuartusReports.fmax(it) }.orEmpty()
        programmingFiles = PROGRAMMING_FILES.map { revision.output(it) }.filter { Files.isRegularFile(it) }
        reports = REPORTS.map { (title, suffix) -> title to revision.output(suffix) }.filter { Files.isRegularFile(it.second) }
    }

    /** The report file called `<revision>.[suffix]`, when the compilation wrote it. */
    fun report(suffix: String): Path? = reports.map { it.second }.firstOrNull { it.toString().endsWith(".$suffix") }

    /** The worst slack of each kind of check over all clocks and corners, the kinds in the order Quartus reports them. */
    val worstSlacks: List<QuartusReports.Slack>
        get() = slacks.groupBy { it.check }.values.map { same -> same.minBy { it.slack } }

    /** The checks that fail: those with negative slack. */
    val failingSlacks: List<QuartusReports.Slack> get() = slacks.filter { it.slack < 0 }

    /** For each clock the lowest Fmax over the corners, which is the one that holds. */
    val worstFmax: List<QuartusReports.Fmax>
        get() = fmax.groupBy { it.clock }.values.map { same -> same.minBy { it.restricted } }

    companion object {
        private val SUMMARIES = listOf("fit.summary", "map.summary", "syn.summary")
        private val PROGRAMMING_FILES = listOf("sof", "pof", "jic", "rbf")

        /** Lines of a summary that the header of the tool window shows already, or that say nothing. */
        private val NOT_RESOURCES = setOf(
            "Fitter Status", "Analysis & Synthesis Status", "Synthesis Status", "Quartus Prime Version", "Revision Name",
            "Top-level Entity Name", "Family", "Device", "Timing Models",
        )

        val REPORTS = listOf(
            "Flow Summary" to "flow.rpt",
            "Analysis & Synthesis" to "map.rpt",
            "Synthesis" to "syn.rpt",
            "Fitter" to "fit.rpt",
            "Timing Analyzer" to "sta.rpt",
            "Assembler" to "asm.rpt",
            "Pin-Out" to "pin",
        )

        /** The reports whose messages make up those of a compilation, in the order the tools run. */
        val MESSAGE_REPORTS = listOf("map.rpt", "syn.rpt", "fit.rpt", "asm.rpt", "sta.rpt")

        /** The messages of the last compilation of [revision], read from its reports. */
        fun messages(revision: QuartusRevision): List<QuartusMessage> =
            MESSAGE_REPORTS.mapNotNull { QuartusReports.read(revision.output(it)) }.flatMap { QuartusReports.messages(it) }
    }
}
