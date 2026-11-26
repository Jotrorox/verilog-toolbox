package com.jotrorox.verilogtoolbox.gowin.eda

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** How serious GOWIN EDA rates a message, most serious first. */
enum class GowinEdaSeverity(val label: String) {
    ERROR("ERROR"),
    WARNING("WARN"),
    NOTE("NOTE"),
    INFO("INFO");

    companion object {
        fun ofLabel(label: String): GowinEdaSeverity? = entries.firstOrNull { it.label == label }
    }
}

/**
 * A message as the tools print it. Synthesis puts the location last, `ERROR (EX3863) : Syntax error near
 * 'wire'("C:\p\top.v":21)`; the constraint reader first, `ERROR  (CT1135) : "C:\p\top.cst":2 | Can't find
 * object named 'clk'`. [text] is without the location.
 */
data class GowinEdaMessage(
    val severity: GowinEdaSeverity,
    val code: String?,
    val text: String,
    val file: String? = null,
    val line: Int? = null,
) {
    companion object {
        private val MESSAGE = Regex("""^(ERROR|WARN|NOTE|INFO)\s*(?:\(([A-Z]+\d+)\))?\s*:\s?(.*)$""")
        private val TRAILING_LOCATION = Regex("""\("([^"]+)":(\d+)\)\s*$""")
        private val LEADING_LOCATION = Regex("""^"([^"]+)":(\d+)\s*\|\s*(.*)$""")

        /** Reads one line of tool output or of a log; null for any other line. */
        fun parse(line: String): GowinEdaMessage? {
            val match = MESSAGE.matchEntire(line.trim()) ?: return null
            val (label, code, rest) = match.destructured
            val severity = GowinEdaSeverity.ofLabel(label) ?: return null
            LEADING_LOCATION.matchEntire(rest.trim())?.let { located ->
                val (file, number, text) = located.destructured
                return GowinEdaMessage(severity, code.ifEmpty { null }, text.trim(), file, number.toIntOrNull())
            }
            val trailing = TRAILING_LOCATION.find(rest)
            val text = if (trailing != null) rest.substring(0, trailing.range.first) else rest
            return GowinEdaMessage(
                severity, code.ifEmpty { null }, text.trim(), trailing?.groupValues?.get(1), trailing?.groupValues?.get(2)?.toIntOrNull(),
            )
        }
    }
}

/** Reads what the flow writes into `impl`: the logs, the place and route report and the timing report. */
object GowinEdaReports {
    /** A line of a resource table: [name] is indented for a part of the line above it, such as the registers used as flip-flops. */
    data class Resource(val name: String, val usage: String, val utilization: String?) {
        val isPart: Boolean get() = name.startsWith(" ")
    }

    /** The highest frequency a clock reaches, and what the constraints ask of it. */
    data class Fmax(val clock: String, val constraint: Double?, val fmax: Double)

    /** The worst slack of a kind of check, and the total negative slack of each clock for it. */
    data class Slack(val check: String, val slack: Double, val from: String?, val to: String?)

    data class NegativeSlack(val clock: String, val check: String, val total: Double, val endpoints: Int)

    /** A port as place and route placed it: [location] is the pin (`52`), [bank] its bank. */
    data class PlacedPort(
        val port: String,
        val location: String,
        val bank: String?,
        val constrained: Boolean,
        val direction: String,
        val ioType: String?,
        val pullMode: String?,
        val drive: String?,
        val bankVoltage: String?,
    )

    /** What the timing report says: the clocks' Fmax, the worst slacks and the endpoints that fail. */
    data class Timing(
        val fmax: List<Fmax>,
        val worst: List<Slack>,
        val negative: List<NegativeSlack>,
        val setupViolations: Int?,
        val holdViolations: Int?,
    ) {
        val isMet: Boolean get() = (setupViolations ?: 0) == 0 && (holdViolations ?: 0) == 0 && worst.none { it.slack < 0 }
    }

    /**
     * A table of a text report: the rows below the line with [title], each split at `|` into trimmed cells,
     * until the table's closing line of `=`. Rows of dashes are left out.
     */
    internal fun table(text: String, title: Regex): List<List<String>> {
        val lines = text.lines()
        val start = lines.indexOfFirst { title.containsMatchIn(it) }
        if (start < 0) return emptyList()
        val rows = mutableListOf<List<String>>()
        var started = false
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trim()
            if (trimmed.startsWith("====")) break
            if (trimmed.startsWith("----") || trimmed.isEmpty()) {
                if (trimmed.isEmpty() && started && rows.isNotEmpty()) break
                continue
            }
            if ('|' !in line) {
                if (started) break else continue
            }
            started = true
            // The leading spaces of the first cell tell a part from a whole.
            rows += line.split('|').mapIndexed { index, cell -> if (index == 0) cell.trimEnd() else cell.trim() }
        }
        return rows
    }

    /** The resource table of the place and route report (`3. Resource Usage Summary`), the header left out. */
    fun resources(pnrReport: String): List<Resource> =
        table(pnrReport, Regex("""^\s*\d+\.\s*Resource Usage Summary""")).drop(1).mapNotNull { cells ->
            val first = cells.getOrNull(0) ?: return@mapNotNull null
            val name = first.trim()
            if (name.isEmpty()) return@mapNotNull null
            // `--LUT,ALU,ROM16` is a part of `Logic` above it.
            val isPart = name.startsWith("--")
            Resource(if (isPart) "  " + name.removePrefix("--").trim() else name, cells.getOrNull(1).orEmpty(), cells.getOrNull(2)?.takeIf { it != "-" && it.isNotEmpty() })
        }

    /** The value of a `<Name>: value` line of a report's header, such as `Part Number`. */
    fun header(report: String, name: String): String? =
        Regex("""<${Regex.escape(name)}>\s*:\s*(.*)""").find(report)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** `Total Time and Memory Usage: … Elapsed time = 0h 0m 1s` as `0h 0m 1s`. */
    fun elapsed(report: String): String? =
        Regex("""Total Time and Memory Usage.*?Elapsed time\s*=\s*([^,<\r\n]+)""", RegexOption.DOT_MATCHES_ALL)
            .find(report)?.groupValues?.get(1)?.trim()

    /** The ports of `7. Pinout by Port Name`, with the bank voltage of each. */
    fun placedPorts(pnrReport: String): List<PlacedPort> {
        val rows = table(pnrReport, Regex("""^\s*\d+\.\s*Pinout by Port Name"""))
        val header = rows.firstOrNull()?.map { it.trim() } ?: return emptyList()
        fun column(vararg names: String) = names.firstNotNullOfOrNull { name -> header.indexOfFirst { it.equals(name, true) }.takeIf { it >= 0 } }
        val port = column("Port Name") ?: return emptyList()
        val location = column("Loc./Bank") ?: return emptyList()
        val constraint = column("Constraint")
        val direction = column("Dir.")
        val ioType = column("IO Type")
        val pull = column("Pull Mode")
        val drive = column("Drive")
        val vccio = column("BankVccio", "Bank Vccio")
        return rows.drop(1).mapNotNull { cells ->
            fun cell(index: Int?) = index?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() && it != "-" && it != "NA" }
            val name = cell(port) ?: return@mapNotNull null
            val (pin, bank) = (cell(location) ?: return@mapNotNull null).split('/').let { it[0] to it.getOrNull(1) }
            PlacedPort(name, pin, bank, cell(constraint) == "Y", cell(direction).orEmpty(), cell(ioType), cell(pull), cell(drive), cell(vccio))
        }
    }

    /** The VCCIO of each bank, from `8. All Package Pins`. */
    fun bankVoltages(pnrReport: String): Map<String, String> {
        val rows = table(pnrReport, Regex("""^\s*\d+\.\s*All Package Pins"""))
        val header = rows.firstOrNull()?.map { it.trim() } ?: return emptyMap()
        val location = header.indexOfFirst { it.equals("Loc./Bank", true) }.takeIf { it >= 0 } ?: return emptyMap()
        val vccio = header.indexOfFirst { it.replace(" ", "").equals("BankVccio", true) }.takeIf { it >= 0 } ?: return emptyMap()
        val voltages = LinkedHashMap<String, String>()
        for (cells in rows.drop(1)) {
            val bank = cells.getOrNull(location)?.trim()?.substringAfter('/', "")?.takeIf { it.isNotEmpty() } ?: continue
            val voltage = cells.getOrNull(vccio)?.trim()?.takeIf { it.isNotEmpty() && it != "-" } ?: continue
            voltages.putIfAbsent(bank, voltage)
        }
        return voltages
    }

    private val FMAX_ROW = Regex("""^\s*\d+\s+(\S+)\s+(.*)$""")
    private val MHZ = Regex("""([\d.]+)\s*\(MHz\)""")
    private val TNS_ROW = Regex("""^\s*(\S+)\s+(setup|hold|recovery|removal)\s+(-?[\d.]+)\s+(\d+)\s*$""", RegexOption.IGNORE_CASE)
    private val PATH_ROW = Regex("""^\s*\d+\s+(-?[\d.]+)\s+(\S+)\s+(\S+)\s+""")

    /** Reads the text timing report, `impl/pnr/<name>.tr`. */
    fun timing(report: String): Timing {
        val lines = report.lines()
        // The report starts with a table of contents of the same titles; the section is the last one.
        fun section(title: String): List<String> {
            val start = lines.indexOfLast { it.trim() == title }
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !SECTION.matches(it.trim()) }
        }
        val fmax = section("2.3 Max Frequency Summary").mapNotNull { line ->
            if (line.trim().startsWith("NO.") || line.trim().startsWith("=")) return@mapNotNull null
            val match = FMAX_ROW.matchEntire(line) ?: return@mapNotNull null
            val values = MHZ.findAll(match.groupValues[2]).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
            when (values.size) {
                0 -> null
                1 -> Fmax(match.groupValues[1], null, values[0])
                else -> Fmax(match.groupValues[1], values[0], values[1])
            }
        }
        val negative = section("2.4 Total Negative Slack Summary").mapNotNull { line ->
            val match = TNS_ROW.matchEntire(line) ?: return@mapNotNull null
            NegativeSlack(match.groupValues[1], match.groupValues[2].lowercase(), match.groupValues[3].toDouble(), match.groupValues[4].toInt())
        }
        val worst = listOf("Setup" to "3.1.1 Setup Paths Table", "Hold" to "3.1.2 Hold Paths Table",
            "Recovery" to "3.1.3 Recovery Paths Table", "Removal" to "3.1.4 Removal Paths Table").mapNotNull { (check, title) ->
            section(title).mapNotNull { line ->
                PATH_ROW.find(line)?.let { Slack(check, it.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null, it.groupValues[2], it.groupValues[3]) }
            }.minByOrNull { it.slack }
        }
        fun count(name: String) = Regex("""<Numbers of $name Violated Endpoints>\s*:\s*(\d+)""").find(report)?.groupValues?.get(1)?.toIntOrNull()
        return Timing(fmax, worst, negative, count("Setup"), count("Hold"))
    }

    /**
     * `3. Timing Details`, `2.3 Max Frequency Summary` and the like: a numbered heading, which ends the section
     * before it. Its number has a dot, which tells it from a row of a table, such as `1     clk     27.000(MHz)`.
     */
    private val SECTION = Regex("""^(\d+\.)+\d*\s+[A-Za-z].*$""")

    /** `T_Register="30(6)" T_Lut="46(1)"` of the synthesis resource summary, as the resources synthesis estimates. */
    fun synthesisResources(xml: String): List<Resource> {
        val module = Regex("""<Module\b([^>]*)>""").find(xml)?.groupValues?.get(1) ?: return emptyList()
        fun total(attribute: String) = Regex("""\b$attribute="(\d+)""").find(module)?.groupValues?.get(1)
        return listOfNotNull(
            total("T_Lut")?.let { Resource("LUTs", it, null) },
            total("T_Register")?.let { Resource("Registers", it, null) },
        )
    }

    /** The messages of a log, in their order. */
    fun messages(log: String): List<GowinEdaMessage> = log.lineSequence().mapNotNull { GowinEdaMessage.parse(it) }.toList()

    /**
     * The text of a report. The tools write UTF-8 but copy the user's own text, such as a comment in a
     * constraint, in whatever it was; then the Windows code page is the best guess.
     */
    fun read(file: Path): String? {
        val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return null
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }
}

/** What the files in `impl` say about the last run of a project's flow. Reads them when it is created. */
class GowinEdaCompilation(project: GowinEdaProject) {
    enum class Status { SUCCESSFUL, FAILED, SYNTHESIZED }

    data class Step(val name: String, val done: Boolean, val failed: Boolean, val elapsed: String?)

    val projectFile: Path = project.file
    val status: Status?
    val steps: List<Step>
    val resources: List<GowinEdaReports.Resource>

    /** Whether [resources] are the estimate of synthesis, as they are before place and route has run. */
    val estimated: Boolean
    val timing: GowinEdaReports.Timing?
    val placedPorts: List<GowinEdaReports.PlacedPort>
    val bankVoltages: Map<String, String>

    /** The part number the last place and route was for, from its report. */
    val placedPart: String?
    val bitstream: Path?

    /** The report files there are, by what they are about. */
    val reports: List<Pair<String, Path>>

    /** The messages of the last run, from its logs, synthesis first. */
    val messages: List<GowinEdaMessage>

    init {
        val synthesisLog = GowinEdaReports.read(project.synthesisOutput("log"))
        val pnrLog = GowinEdaReports.read(project.pnrOutput("log"))
        val pnrReport = GowinEdaReports.read(project.pnrOutput("rpt.txt"))
        val synthesisReport = GowinEdaReports.read(project.synthesisOutput("_syn.rpt.html"))
        val synthesisMessages = synthesisLog?.let { GowinEdaReports.messages(it) }.orEmpty()
        val pnrMessages = pnrLog?.let { GowinEdaReports.messages(it) }.orEmpty()
        messages = synthesisMessages + pnrMessages
        bitstream = project.bitstream.takeIf { Files.isRegularFile(it) }
        val synthesisFailed = synthesisMessages.any { it.severity == GowinEdaSeverity.ERROR } ||
            synthesisLog != null && "GowinSynthesis finish" !in synthesisLog
        val synthesized = synthesisLog != null && !synthesisFailed
        // A place and route log older than the synthesis that came after it belongs to an earlier run.
        val pnrCurrent = pnrLog != null && stamp(project.pnrOutput("log")) >= stamp(project.synthesisOutput("log"))
        val pnrFailed = pnrCurrent && (pnrMessages.any { it.severity == GowinEdaSeverity.ERROR } || "Placement and routing completed" !in pnrLog.orEmpty())
        val placed = pnrCurrent && !pnrFailed
        status = when {
            synthesisFailed || pnrFailed -> Status.FAILED
            placed -> Status.SUCCESSFUL
            synthesized -> Status.SYNTHESIZED
            else -> null
        }
        steps = buildList {
            if (synthesisLog != null) add(Step("Synthesis", synthesized, synthesisFailed, synthesisReport?.let { GowinEdaReports.elapsed(it) }))
            if (pnrCurrent) add(Step("Place & Route", placed, pnrFailed, pnrReport?.let { GowinEdaReports.elapsed(it) }))
            if (placed && pnrLog.orEmpty().contains("Bitstream generation completed")) add(Step("Bitstream", true, false, null))
        }
        val placedResources = pnrReport?.takeIf { placed }?.let { GowinEdaReports.resources(it) }.orEmpty()
        estimated = placedResources.isEmpty()
        resources = placedResources.ifEmpty {
            GowinEdaReports.read(project.synthesisOutput("_syn_rsc.xml"))?.takeIf { synthesized }?.let { GowinEdaReports.synthesisResources(it) }.orEmpty()
        }
        timing = GowinEdaReports.read(project.pnrOutput("tr"))?.takeIf { placed }?.let { GowinEdaReports.timing(it) }
        placedPorts = pnrReport?.takeIf { placed }?.let { GowinEdaReports.placedPorts(it) }.orEmpty()
        bankVoltages = pnrReport?.takeIf { placed }?.let { GowinEdaReports.bankVoltages(it) }.orEmpty()
        placedPart = pnrReport?.let { GowinEdaReports.header(it, "Part Number") }
        reports = reports(project)
    }

    private fun stamp(file: Path): Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0)

    companion object {
        /** The reports a project's flow writes, by title, as functions of the project; the text ones open in the editor. */
        private val REPORTS: List<Pair<String, (GowinEdaProject) -> Path>> = listOf(
            "Place & Route Report" to { it.pnrOutput("rpt.txt") },
            "Timing Report" to { it.pnrOutput("tr") },
            "Synthesis Report" to { it.synthesisOutput("_syn.rpt.html") },
            "Pin Report" to { it.pnrOutput("pin.html") },
            "Power Report" to { it.pnrOutput("power.html") },
            "Synthesis Log" to { it.synthesisOutput("log") },
            "Place & Route Log" to { it.pnrOutput("log") },
        )

        /** The reports of [project] that exist. */
        fun reports(project: GowinEdaProject): List<Pair<String, Path>> =
            REPORTS.map { (title, file) -> title to file(project) }.filter { Files.isRegularFile(it.second) }

        /** The files whose change means the reports are to be read again. */
        fun watched(project: GowinEdaProject): List<Path> =
            REPORTS.map { it.second(project) } + listOf(project.synthesisOutput("_syn_rsc.xml"), project.bitstream)
    }
}
