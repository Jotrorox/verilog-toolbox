package com.jotrorox.verilogtoolbox.gowin.eda

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.jotrorox.verilogtoolbox.gowin.GowinCstLexer
import com.jotrorox.verilogtoolbox.gowin.GowinCstTokenTypes
import com.jotrorox.verilogtoolbox.quartus.QuartusPin
import com.jotrorox.verilogtoolbox.quartus.QuartusPins
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import java.nio.file.Files
import java.nio.file.Path

/**
 * The statements of a `.cst` file, read with [GowinCstLexer], and changes to its text that touch only the
 * statements they are about, so that comments and the order of everything else stay as they were.
 */
object GowinEdaCstText {
    /** `NAME=VALUE` of a statement; [range] covers all of it in the text. */
    data class Attribute(val name: String, val value: String, val range: IntRange)

    /**
     * A statement up to its `;`: [keyword] such as `IO_LOC`, [name] the quoted object without its quotes,
     * [locations] the words after it, such as `52` or `39,40` as `39` and `40`, and its attributes.
     */
    data class Statement(
        val keyword: String,
        val name: String?,
        val nameEnd: Int,
        val locations: List<String>,
        val attributes: List<Attribute>,
        val range: IntRange,
    ) {
        fun attribute(name: String): String? = attributes.lastOrNull { it.name.equals(name, ignoreCase = true) }?.value

        /** The node it is about, with the spaces Gowin allows in `"led[ 0 ]"` left out. */
        val node: String? get() = name?.replace(" ", "")
    }

    fun statements(text: CharSequence): List<Statement> {
        val lexer = GowinCstLexer()
        lexer.start(text, 0, text.length, 0)
        val statements = mutableListOf<Statement>()
        var keyword: String? = null
        var start = 0
        var name: String? = null
        var nameEnd = -1
        val locations = mutableListOf<String>()
        val attributes = mutableListOf<Attribute>()
        var attributeName: Pair<String, Int>? = null
        var expectValue = false
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val token = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            when {
                type == GowinCstTokenTypes.COMMENT || type == TokenType.WHITE_SPACE -> Unit
                type == GowinCstTokenTypes.KEYWORD || type == GowinCstTokenTypes.STATEMENT -> {
                    keyword = token
                    start = lexer.tokenStart
                    name = null
                    nameEnd = -1
                    locations.clear()
                    attributes.clear()
                }
                type == GowinCstTokenTypes.STRING && keyword != null && name == null -> {
                    name = token.removePrefix("\"").removeSuffix("\"")
                    nameEnd = lexer.tokenEnd
                }
                type == GowinCstTokenTypes.ATTRIBUTE -> attributeName = token to lexer.tokenStart
                type == GowinCstTokenTypes.EQ -> expectValue = attributeName != null
                (type == GowinCstTokenTypes.VALUE || type == GowinCstTokenTypes.NUMBER || type == GowinCstTokenTypes.LOCATION) && expectValue -> {
                    val (attribute, from) = attributeName!!
                    attributes += Attribute(attribute, token, from until lexer.tokenEnd)
                    attributeName = null
                    expectValue = false
                }
                type == GowinCstTokenTypes.LOCATION || type == GowinCstTokenTypes.NUMBER -> if (keyword != null) locations += token
                type == GowinCstTokenTypes.SEMICOLON -> {
                    keyword?.let { statements += Statement(it, name, nameEnd, locations.toList(), attributes.toList(), start until lexer.tokenEnd) }
                    keyword = null
                    attributeName = null
                    expectValue = false
                }
            }
            lexer.advance()
        }
        return statements
    }

    private fun matches(statement: Statement, keyword: String, node: String) =
        statement.keyword == keyword && statement.node.equals(node.replace(" ", ""), ignoreCase = true)

    /** Places [node] on [location], such as `52`, or takes its place away for null. */
    fun setLocation(text: String, node: String, location: String?): String {
        val statements = statements(text)
        val existing = statements.filter { matches(it, IO_LOC, node) }
        if (location == null) return remove(text, existing)
        val last = existing.lastOrNull()
        if (last != null) {
            val replaced = text.replaceRange(last.nameEnd, last.range.last, " $location")
            return remove(replaced, existing.dropLast(1))
        }
        val line = "$IO_LOC \"$node\" $location;"
        return insert(text, line, statements.lastOrNull { it.keyword == IO_LOC })
    }

    /** Sets the attribute [attribute] of [node], such as its `IO_TYPE`, or removes it for null. */
    fun setAttribute(text: String, node: String, attribute: String, value: String?): String {
        val statements = statements(text)
        val ports = statements.filter { matches(it, IO_PORT, node) }
        val having = ports.filter { port -> port.attributes.any { it.name.equals(attribute, true) } }
        if (value == null) {
            var result = text
            for (port in having.sortedByDescending { it.range.first }) {
                val remaining = port.attributes.filter { !it.name.equals(attribute, true) }
                result = if (remaining.isEmpty()) remove(result, listOf(port)) else {
                    port.attributes.filter { it.name.equals(attribute, true) }.sortedByDescending { it.range.first }
                        .fold(result) { r, a -> removeWithSpace(r, a.range) }
                }
            }
            return result
        }
        having.lastOrNull()?.let { port ->
            val target = port.attributes.last { it.name.equals(attribute, true) }
            return text.replaceRange(target.range, "$attribute=$value")
        }
        ports.lastOrNull()?.let { port -> return text.substring(0, port.range.last) + " $attribute=$value" + text.substring(port.range.last) }
        val line = "$IO_PORT \"$node\" $attribute=$value;"
        // Next to where the node is placed, else after the other ports' attributes.
        val after = statements.lastOrNull { matches(it, IO_LOC, node) } ?: statements.lastOrNull { it.keyword == IO_PORT }
            ?: statements.lastOrNull { it.keyword == IO_LOC }
        return insert(text, line, after)
    }

    /** Removes [range] and a space before it, as an attribute in the middle of a statement leaves one. */
    private fun removeWithSpace(text: String, range: IntRange): String {
        var start = range.first
        while (start > 0 && text[start - 1] == ' ') start--
        return text.removeRange(start, range.last + 1)
    }

    private fun insert(text: String, line: String, after: Statement?): String {
        if (after == null) {
            val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
            return text + separator + line + "\n"
        }
        val lineEnd = text.indexOf('\n', after.range.last).let { if (it < 0) text.length else it }
        return text.substring(0, lineEnd) + "\n" + line + text.substring(lineEnd)
    }

    /** Removes [statements] and the lines they leave empty. */
    private fun remove(text: String, statements: List<Statement>): String {
        var result = text
        for (statement in statements.sortedByDescending { it.range.first }) {
            var start = statement.range.first
            var end = statement.range.last + 1
            val lineStart = result.lastIndexOf('\n', start - 1) + 1
            val lineEnd = result.indexOf('\n', end).let { if (it < 0) result.length else it }
            if (result.substring(lineStart, start).isBlank() && result.substring(end, lineEnd).isBlank()) {
                start = lineStart
                end = minOf(lineEnd + 1, result.length)
            }
            result = result.removeRange(start, end)
        }
        return result
    }

    const val IO_LOC = "IO_LOC"
    const val IO_PORT = "IO_PORT"
}

/**
 * What the Pins tab shows, worked out from the top module, the constraints, the package of the device and the
 * report of the last place and route. Plain data, so that it can be tested without the IDE.
 */
object GowinEdaPins {
    data class Row(
        val node: QuartusPins.Node,
        val location: String?,
        val ioType: String?,
        val pullMode: String?,
        val drive: String?,
        /** Where the last place and route put it, and with what. */
        val placed: GowinEdaReports.PlacedPort?,
        /** The other nodes the constraints place on the same pin. */
        val conflicts: List<String> = emptyList(),
    ) {
        val name: String get() = node.name
        val isUnassigned: Boolean get() = location == null && node.direction in PIN_DIRECTIONS
    }

    val PULL_MODES = listOf("UP", "DOWN", "NONE", "KEEPER")
    val DRIVES = listOf("4", "8", "12", "16", "24")

    /** Offered when the installation's tables are not at hand. */
    val COMMON_IO_TYPES = listOf("LVCMOS33", "LVCMOS25", "LVCMOS18", "LVCMOS15", "LVCMOS12", "LVTTL33", "LVDS25", "LVDS25E", "SSTL15", "SSTL18_I", "HSTL18_I")

    private val PIN_DIRECTIONS = setOf(VerilogModuleHeaders.Direction.INPUT, VerilogModuleHeaders.Direction.OUTPUT, VerilogModuleHeaders.Direction.INOUT)

    /** The rows for [module]'s ports, bit by bit, and for every node the constraints name that no port explains. */
    fun rows(module: VerilogModuleHeaders.Module?, statements: List<GowinEdaCstText.Statement>, placed: List<GowinEdaReports.PlacedPort>): List<Row> {
        val locations = LinkedHashMap<String, String>()
        val ports = LinkedHashMap<String, MutableMap<String, String>>()
        val spelled = LinkedHashMap<String, String>()
        for (statement in statements) {
            val node = statement.node ?: continue
            spelled.putIfAbsent(node.lowercase(), node)
            when (statement.keyword) {
                GowinEdaCstText.IO_LOC -> statement.locations.takeIf { it.isNotEmpty() }?.let { locations[node.lowercase()] = it.joinToString(",") }
                GowinEdaCstText.IO_PORT -> for (attribute in statement.attributes) {
                    ports.getOrPut(node.lowercase()) { LinkedHashMap() }[attribute.name.uppercase()] = attribute.value
                }
            }
        }
        // The port expansion of the Quartus pins, which knows parameters and the bits the constraints name.
        val pins = spelled.values.map { QuartusPin(it, locations[it.lowercase()], null) }
        val nodes = QuartusPins.nodes(module, pins)
        val byPort = placed.associateBy { it.port.replace(" ", "").lowercase() }
        val clashes = conflicts(nodes.associate { it.name to locations[it.name.lowercase()] })
        return nodes.map { node ->
            val key = node.name.lowercase()
            val attributes = ports[key].orEmpty()
            Row(node, locations[key], attributes["IO_TYPE"], attributes["PULL_MODE"], attributes["DRIVE"], byPort[key], clashes[node.name].orEmpty())
        }
    }

    /** For each node that shares its pin with others, those others. */
    fun conflicts(locations: Map<String, String?>): Map<String, List<String>> {
        val byPin = LinkedHashMap<String, MutableList<String>>()
        for ((node, location) in locations) {
            for (pin in location?.split(',')?.map { it.trim().uppercase() }.orEmpty().filter { it.isNotEmpty() }) {
                byPin.getOrPut(pin) { mutableListOf() } += node
            }
        }
        val result = LinkedHashMap<String, List<String>>()
        for (nodes in byPin.values) if (nodes.size > 1) for (node in nodes) result[node] = (result[node].orEmpty() + (nodes - node)).distinct()
        return result
    }

    /** The I/O pins of [pins] no row other than [node] is placed on. */
    fun freePins(pins: List<GowinEdaPackagePin>, rows: List<Row>, node: String? = null): List<GowinEdaPackagePin> {
        val taken = rows.filter { it.name != node }.flatMapTo(HashSet()) { row -> row.location?.split(',')?.map { it.trim().uppercase() }.orEmpty() }
        return pins.filter { it.isIo && it.index.uppercase() !in taken }
    }

    /** Whether [text] is a pin a `.cst` takes: `52`, `B3`, or a pair such as `39,40`; empty takes the pin away. */
    fun isLocation(text: String): Boolean = text.isEmpty() || LOCATION.matches(text)

    private val LOCATION = Regex("""^[A-Za-z]{0,2}\d+(\s*,\s*[A-Za-z]{0,2}\d+)?$""")

    /** The I/O standards a row of [direction] can take: those the device's tables offer for it, else the common ones. */
    fun ioTypes(standards: Map<String, List<String>>, direction: VerilogModuleHeaders.Direction?): List<String> {
        val table = when (direction) {
            VerilogModuleHeaders.Direction.INPUT -> "input"
            VerilogModuleHeaders.Direction.OUTPUT -> "output"
            else -> "bidir"
        }
        return standards[table] ?: standards.values.flatten().distinct().ifEmpty { COMMON_IO_TYPES }
    }
}

/** Creating the physical constraints of a project that has none. */
object GowinEdaConstraints {
    /**
     * Writes `<name>.cst` beside the `.gprj`, in `src` when the sources are there, lists it in the project and
     * returns it; null when that failed, which has been said. Call on the UI thread.
     */
    fun create(project: Project, gowinProject: GowinEdaProject): Path? {
        val source = gowinProject.directory.resolve("src")
        val directory = if (Files.isDirectory(source) && gowinProject.verilogSources.any { it.startsWith(source) }) source else gowinProject.directory
        val file = directory.resolve("${gowinProject.name}.cst")
        val service = GowinEdaProjectService.getInstance(project)
        val header = "// Physical constraints of ${gowinProject.name}" +
            (gowinProject.device?.let { " for ${it.partNumber}" } ?: "") + ", created by Verilog Toolbox.\n" +
            "// Assign pins in the Pins tab of the GOWIN EDA tool window, or write IO_LOC and IO_PORT statements here.\n"
        val written = service.editFile(file, "Create Constraints", create = true) { it ?: header }
        val listed = written && service.edit("Create Constraints") { GowinEdaActions.add(it, file, gowinProject.directory) }
        if (!listed) {
            GowinEdaActions.notify(project, "${file.fileName} could not be created.", NotificationType.ERROR)
            return null
        }
        return file
    }
}
