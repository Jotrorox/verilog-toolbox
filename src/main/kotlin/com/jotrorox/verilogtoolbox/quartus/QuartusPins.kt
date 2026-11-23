package com.jotrorox.verilogtoolbox.quartus

import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Direction
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Module
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Port

/**
 * What the Pins tab shows, worked out from the top-level module, the settings and the Fitter's `.pin` file:
 * the nodes a pin can be assigned to, their effective I/O standards, the pins of the device that are free,
 * and assignments that clash. Everything here is plain data, so that it can be tested without the IDE.
 */
object QuartusPins {
    /**
     * A node the settings can assign a pin to: a scalar port, one bit of a vector, or a whole port whose
     * bits cannot be counted. [port] is the port it belongs to; null, like [direction], for a node that only
     * the settings name, such as a port that has been renamed since. [isWhole] marks a vector that is one
     * node because its bounds are neither literal nor computable from the parameters.
     */
    data class Node(val name: String, val direction: Direction?, val port: String? = null, val isWhole: Boolean = false) {
        val isOrphan: Boolean get() = port == null
    }

    enum class StandardSource { OWN, BUS, DEFAULT }

    /** An I/O standard and where it comes from: [from] is the bus node it is inherited from. */
    data class Standard(val value: String, val source: StandardSource, val from: String? = null) {
        val isInherited: Boolean get() = source != StandardSource.OWN
    }

    /**
     * A pin of the package as the Fitter reports it. [location] is as the settings write it (`PIN_P11`),
     * [usage] is the first column: a node name, or what Quartus did with an unused pin.
     */
    data class PackagePin(
        val location: String,
        val usage: String,
        val direction: String,
        val ioStandard: String,
        val bank: String,
        val userAssignment: String,
    ) {
        /** The node the Fitter placed here; null for a pin no node of the design uses. */
        val node: String?
            get() = usage.takeUnless { usage.startsWith("RESERVED") || usage.startsWith("GND") || usage.startsWith("~") }
    }

    /**
     * The assignable pins of a `.pin` file, in its order, without power, ground, no-connect and dedicated
     * configuration pins. [device] is the one it was fitted for; [bankVoltages] the VCCIO of each bank.
     */
    data class PinReport(val device: String?, val pins: List<PackagePin>, val bankVoltages: Map<String, String>) {
        private val byNode: Map<String, PackagePin> by lazy {
            pins.mapNotNull { pin -> pin.node?.let { it.lowercase() to pin } }.toMap()
        }
        private val byLocation: Map<String, PackagePin> by lazy { pins.associateBy { it.location.uppercase() } }

        /** Where the Fitter placed [node], whether the settings asked for it or not. */
        fun placement(node: String): PackagePin? = byNode[node.lowercase()]

        fun pin(location: String): PackagePin? = QuartusSettingsText.pinLocation(location)?.let { byLocation[it] }
    }

    /** One line of the table: a node with what the settings and the last compilation say about it. */
    data class Row(
        val node: Node,
        val location: String?,
        val standard: Standard?,
        val fitterLocation: String?,
        /** The other nodes the settings assign the same [location]. */
        val conflicts: List<String> = emptyList(),
    ) {
        val name: String get() = node.name

        /** A port that goes to a pin but has none: interfaces and the like are not counted. */
        val isUnassigned: Boolean get() = location == null && node.direction in PIN_DIRECTIONS
    }

    /** The I/O standards offered for every project; those a project uses are added to them. */
    val COMMON_IO_STANDARDS = listOf(
        "3.3-V LVTTL", "3.3-V LVCMOS", "3.0-V LVTTL", "3.0-V LVCMOS", "2.5 V", "1.8 V", "1.5 V", "1.2 V",
        "3.3 V Schmitt Trigger", "2.5 V Schmitt Trigger", "1.8 V Schmitt Trigger", "1.5 V Schmitt Trigger",
        "LVDS", "BLVDS", "Mini-LVDS", "RSDS", "PPDS",
        "SSTL-2 Class I", "SSTL-2 Class II", "SSTL-18 Class I", "SSTL-18 Class II",
        "SSTL-15", "SSTL-15 Class I", "SSTL-15 Class II", "SSTL-135",
        "1.8-V HSTL Class I", "1.5-V HSTL Class I", "1.2-V HSTL Class I", "1.2 V HSUL",
    )

    /** The global assignment that gives the I/O standard of every pin that has none of its own. */
    const val DEFAULT_IO_STANDARD = "STRATIX_DEVICE_IO_STANDARD"

    private val PIN_DIRECTIONS = setOf(Direction.INPUT, Direction.OUTPUT, Direction.INOUT)

    /** Vectors wider than this are left whole: no board has that many pins for one port. */
    private const val MAX_BITS = 1024

    private val BIT = Regex("""^(.*)\[(\d+)]$""")
    private val LOCATION = Regex("""^PIN_[A-Z]*\d+$""")

    /** The module called [entity] among [modules]; one that differs in case only if no name matches exactly. */
    fun topLevel(modules: List<Module>, entity: String): Module? =
        modules.firstOrNull { it.name == entity } ?: modules.firstOrNull { it.name.equals(entity, ignoreCase = true) }

    /**
     * The nodes of [module]'s ports, in their order, followed by every node [pins] assigns that no port
     * explains, so that nothing in the settings is hidden. A vector whose bounds can be worked out, from
     * literals or from the parameters with [overrides] (`set_parameter` of the top level) applied, is one
     * node per bit in the order of its range; one whose bounds cannot is one node, or the bits the settings
     * assign when they assign some. A node keeps the spelling of the settings where it differs in case.
     */
    fun nodes(module: Module?, pins: List<QuartusPin>, overrides: Map<String, String> = emptyMap()): List<Node> {
        val spelled = HashMap<String, String>()
        for (pin in pins) spelled.putIfAbsent(pin.node.lowercase(), pin.node)
        fun spell(name: String) = spelled[name.lowercase()] ?: name

        val parameters: (String) -> Long? = module?.let { parameters(it, overrides) } ?: { null }
        val nodes = mutableListOf<Node>()
        // Ports given as bits, whose own name may still carry an I/O standard for all of them.
        val buses = HashSet<String>()
        for (port in module?.ports.orEmpty()) {
            val bits = bits(port, parameters)
            when {
                bits != null -> {
                    buses += port.name.lowercase()
                    bits.mapTo(nodes) { Node(spell("${port.name}[$it]"), port.direction, port.name) }
                }
                isVector(port) -> {
                    val assigned = pins.mapNotNull { pin ->
                        BIT.matchEntire(pin.node)?.takeIf { it.groupValues[1].equals(port.name, ignoreCase = true) }
                            ?.let { pin.node to it.groupValues[2].toLong() }
                    }.distinctBy { it.first }.sortedByDescending { it.second }
                    if (assigned.isEmpty()) {
                        nodes += Node(spell(port.name), port.direction, port.name, isWhole = true)
                    } else {
                        buses += port.name.lowercase()
                        assigned.mapTo(nodes) { Node(it.first, port.direction, port.name) }
                    }
                }
                else -> nodes += Node(spell(port.name), port.direction, port.name)
            }
        }
        val shown = nodes.mapTo(HashSet()) { it.name.lowercase() }
        for (pin in pins) {
            val key = pin.node.lowercase()
            // The I/O standard of a bus shows on its bits; a location on it would not, so that stays a row.
            if (key in shown || key in buses && pin.location == null) continue
            shown += key
            nodes += Node(pin.node, null)
        }
        return nodes
    }

    /** A vector of plain bits, whose packed dimension can be split into pins. */
    private fun isVector(port: Port): Boolean =
        port.direction != Direction.INTERFACE && port.dataType == null && port.unpacked.isEmpty() && port.packed.isNotEmpty()

    /** The bit indices of [port] in the order of its range; null when it is a vector they cannot be found for. */
    private fun bits(port: Port, parameters: (String) -> Long?): List<Long>? {
        if (!isVector(port)) return null
        val range = port.packed.trim()
        // Several packed dimensions, `[1:0][7:0]`, would need names Quartus spells in more than one way.
        if (!range.startsWith("[") || closing(range, 0) != range.length - 1) return null
        val inner = range.substring(1, range.length - 1)
        val colon = topLevelColon(inner) ?: return null
        val left = evaluate(inner.substring(0, colon), parameters) ?: return null
        val right = evaluate(inner.substring(colon + 1), parameters) ?: return null
        if (kotlin.math.abs(left - right) >= MAX_BITS) return null
        return if (left >= right) (left downTo right).toList() else (left..right).toList()
    }

    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '[', '(', '{' -> depth++
                ']', ')', '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun topLevelColon(text: String): Int? {
        var depth = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '[', '(', '{' -> depth++
                ']', ')', '}' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return null
    }

    /**
     * The values of [module]'s parameters as far as they are constant integers: the default, or the value
     * of [overrides] for one an instance can override. Null for a parameter that is not.
     */
    fun parameters(module: Module, overrides: Map<String, String> = emptyMap()): (String) -> Long? {
        val texts = module.parameters.filter { !it.isType }
            .associate { it.name to (if (it.isLocal) it.default else overrides[it.name] ?: it.default) }
        val values = HashMap<String, Long?>()
        val visiting = HashSet<String>()
        fun value(name: String): Long? {
            if (name in values) return values[name]
            // A parameter defined through itself is not constant.
            if (!visiting.add(name)) return null
            val result = texts[name]?.let { evaluate(it, ::value) }
            visiting.remove(name)
            values[name] = result
            return result
        }
        return ::value
    }

    /**
     * The value of a constant integer expression as port ranges use them: literals, parameters given by
     * [lookup], arithmetic, shifts and `$clog2`. Null for anything else.
     */
    fun evaluate(text: String, lookup: (String) -> Long? = { null }): Long? =
        try {
            Expression(text, lookup).whole()
        } catch (_: NotConstant) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    private class NotConstant : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Expression(private val text: String, private val lookup: (String) -> Long?) {
        private var at = 0

        fun whole(): Long {
            val value = shift()
            skip()
            if (at != text.length) throw NotConstant()
            return value
        }

        private fun shift(): Long {
            var value = sum()
            while (true) {
                value = when {
                    take("<<<") || take("<<") -> value shl sum().toInt()
                    take(">>>") || take(">>") -> value shr sum().toInt()
                    else -> return value
                }
            }
        }

        private fun sum(): Long {
            var value = product()
            while (true) {
                value = when {
                    take("+") -> value + product()
                    take("-") -> value - product()
                    else -> return value
                }
            }
        }

        private fun product(): Long {
            var value = power()
            while (true) {
                value = when {
                    peek("**") -> return value
                    take("*") -> value * power()
                    take("/") -> value / power()
                    take("%") -> value % power()
                    else -> return value
                }
            }
        }

        private fun power(): Long {
            val base = unary()
            if (!take("**")) return base
            val exponent = power()
            if (exponent < 0 || exponent > 62) throw NotConstant()
            var result = 1L
            repeat(exponent.toInt()) { result *= base }
            return result
        }

        private fun unary(): Long = when {
            take("-") -> -unary()
            take("+") -> unary()
            else -> primary()
        }

        private fun primary(): Long {
            skip()
            if (take("(")) return shift().also { expect(")") }
            if (take("\$clog2")) {
                expect("(")
                val value = shift()
                expect(")")
                return if (value <= 1) 0 else 64L - java.lang.Long.numberOfLeadingZeros(value - 1)
            }
            val c = text.getOrNull(at) ?: throw NotConstant()
            return when {
                c.isDigit() || c == '\'' -> number()
                c.isLetter() || c == '_' -> {
                    val start = at
                    while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_' || text[at] == '$')) at++
                    lookup(text.substring(start, at)) ?: throw NotConstant()
                }
                else -> throw NotConstant()
            }
        }

        /** `12`, `50_000_000`, `8'd12`, `'hFF`, `4'sb1010`. */
        private fun number(): Long {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] == '_')) at++
            val decimal = text.substring(start, at).replace("_", "")
            if (at >= text.length || text[at] != '\'') return decimal.toLongOrNull() ?: throw NotConstant()
            at++
            if (at < text.length && text[at] in "sS") at++
            val radix = when (text.getOrNull(at)?.lowercaseChar()) {
                'b' -> 2
                'o' -> 8
                'd' -> 10
                'h' -> 16
                else -> throw NotConstant()
            }
            at++
            while (at < text.length && text[at].isWhitespace()) at++
            val digitsStart = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            return text.substring(digitsStart, at).replace("_", "").toLongOrNull(radix) ?: throw NotConstant()
        }

        private fun skip() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(token: String): Boolean {
            skip()
            return text.startsWith(token, at)
        }

        private fun take(token: String): Boolean {
            if (!peek(token)) return false
            at += token.length
            return true
        }

        private fun expect(token: String) {
            if (!take(token)) throw NotConstant()
        }
    }

    /**
     * The I/O standard of [node]: its own from [standards] (by node), else that of the bus it is a bit of
     * (`adc_sclk` for `adc_sclk[0]`), else the device-wide [default]. Names match regardless of case when
     * none matches exactly, as Quartus matches them.
     */
    fun ioStandard(node: String, standards: Map<String, String>, default: String? = null): Standard? {
        fun find(name: String): Map.Entry<String, String>? =
            standards.entries.firstOrNull { it.key == name }
                ?: standards.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        find(node)?.let { return Standard(it.value, StandardSource.OWN) }
        var bus = node
        while (true) {
            bus = BIT.matchEntire(bus)?.groupValues?.get(1) ?: break
            find(bus)?.let { return Standard(it.value, StandardSource.BUS, it.key) }
        }
        return default?.takeIf { it.isNotBlank() }?.let { Standard(it, StandardSource.DEFAULT) }
    }

    private val PIN_ROW_HEADER = Regex("""^\s*Pin Name/Usage\s*:""")
    private val BANK_VOLTAGE = Regex("""^\s*--\s+Bank\s+(\S+):\s*(\S+)\s*$""")
    private val CHIP = Regex("""^\s*CHIP\s+"[^"]*"\s+ASSIGNED TO AN:\s*(\S+)""")

    /** Reads the Fitter's `<revision>.pin` file. */
    fun pinReport(text: String): PinReport {
        val pins = mutableListOf<PackagePin>()
        val voltages = LinkedHashMap<String, String>()
        var device: String? = null
        var inTable = false
        for (line in text.lineSequence()) {
            if (!inTable) {
                BANK_VOLTAGE.find(line)?.let { voltages[it.groupValues[1]] = it.groupValues[2] }
                CHIP.find(line)?.let { device = it.groupValues[1] }
                if (PIN_ROW_HEADER.containsMatchIn(line)) inTable = true
                continue
            }
            val parts = line.split(':')
            if (parts.size < 7) continue
            // The name is all but the last six columns, in case one ever has a colon of its own.
            val usage = parts.dropLast(6).joinToString(":").trim()
            val (location, direction, standard, _, bank, user) = parts.takeLast(6).map { it.trim() }
            if (usage.isEmpty() || location.isEmpty()) continue
            if (bank.isEmpty() || direction == "power" || direction == "gnd") continue
            if (usage.startsWith("~") || usage in UNUSABLE) continue
            pins += PackagePin(QuartusSettingsText.pinLocation(location) ?: continue, usage, direction, standard, bank, user)
        }
        return PinReport(device, pins, voltages)
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private val UNUSABLE = setOf("NC", "DNU")

    /** Whether [location] is one a location assignment takes, such as `PIN_P11` or, for a quad flat pack, `PIN_12`. */
    fun isPinLocation(location: String): Boolean = LOCATION.matches(location)

    /** The same pin, however each is written: `p11`, `P11` and `PIN_P11` are one. */
    fun sameLocation(a: String?, b: String?): Boolean {
        val first = a?.let(QuartusSettingsText::pinLocation) ?: return false
        return first == b?.let(QuartusSettingsText::pinLocation)
    }

    /** For each node of [locations] that shares its pin with others, those others, in their order. */
    fun conflicts(locations: Map<String, String?>): Map<String, List<String>> {
        val byPin = LinkedHashMap<String, MutableList<String>>()
        for ((node, location) in locations) {
            val pin = location?.let(QuartusSettingsText::pinLocation) ?: continue
            byPin.getOrPut(pin) { mutableListOf() } += node
        }
        val result = LinkedHashMap<String, List<String>>()
        for (nodes in byPin.values) {
            if (nodes.size < 2) continue
            for (node in nodes) result[node] = nodes - node
        }
        return result
    }

    /** The rows for [nodes]: their assignments in [pins], effective I/O standards, Fitter locations and clashes. */
    fun rows(nodes: List<Node>, pins: List<QuartusPin>, defaultStandard: String? = null, report: PinReport? = null): List<Row> {
        val locations = HashMap<String, String>()
        val standards = LinkedHashMap<String, String>()
        for (pin in pins) {
            pin.location?.let { locations[pin.node] = it }
            pin.ioStandard?.let { standards[pin.node] = it }
        }
        val clashes = conflicts(nodes.associate { it.name to locations[it.name] })
        return nodes.map { node ->
            Row(
                node,
                locations[node.name],
                ioStandard(node.name, standards, defaultStandard),
                report?.placement(node.name)?.location,
                clashes[node.name].orEmpty(),
            )
        }
    }

    /** The pins of [report] that no row other than [node] has, which are those [node] can be moved to. */
    fun freePins(report: PinReport, rows: List<Row>, node: String? = null): List<PackagePin> {
        val taken = rows.filter { it.name != node }.mapNotNullTo(HashSet()) { it.location?.let(QuartusSettingsText::pinLocation) }
        return report.pins.filter { it.location !in taken }
    }

    fun directionLabel(direction: Direction?): String = when (direction) {
        Direction.INPUT -> "input"
        Direction.OUTPUT -> "output"
        Direction.INOUT -> "inout"
        Direction.REF -> "ref"
        Direction.INTERFACE -> "interface"
        null -> "—"
    }
}
