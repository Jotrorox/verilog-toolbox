package com.jotrorox.verilogtoolbox

import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Direction
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Module
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Parameter
import com.jotrorox.verilogtoolbox.VerilogModuleHeaders.Port

/**
 * Writes the testbench of a module: a module without ports that declares a signal for every port, instantiates
 * the design, and dumps a waveform. It sticks to what Icarus Verilog compiles with `-g2012`; what cannot be
 * written faithfully becomes a `// TODO` line rather than a guess.
 */
object VerilogTestbenchGenerator {
    /** The line the user's own code goes below; the editor opens there. */
    const val STIMULUS_MARKER = "// TODO: add stimulus and checks here."

    fun testbenchName(module: Module): String = plain(module.name) + "_tb"

    fun fileName(module: Module): String = testbenchName(module) + ".sv"

    fun generate(module: Module): String {
        val testbench = testbenchName(module)
        val names = (module.ports.map { it.name } + module.parameters.map { it.name } + module.name).toMutableSet()
        fun unique(base: String): String = generateSequence(base) { it + "_" }.first { names.add(it) }
        val instance = unique("dut")

        val clocks = module.ports.filter { it.isScalarInput() && isClock(it.name) }
        val resets = module.ports.filter { it.isScalarInput() && it !in clocks && resetIsActiveLow(it.name) != null }
        val clock = clocks.firstOrNull()?.let { id(it.name) }

        val lines = mutableListOf<String>()
        // Statements for the start of the initial block: what a declaration cannot initialise.
        val setup = mutableListOf<String>()

        lines += "`timescale 1ns / 1ps"
        val macros = module.macros()
        if (macros.isNotEmpty()) {
            // The macros a width or default refers to come from the headers the design includes or from its
            // own file, which may well be compiled after this one.
            lines += module.includes
            for ((name, define) in module.defines) if (name in macros) lines += listOf("`ifndef $name", define, "`endif")
        }
        lines += ""
        lines += "// Testbench for ${module.name}. Simulate it with the run icon next to the module name."
        lines += "module $testbench;"
        if (module.imports.isNotEmpty() || module.ports.any { "::" in it.dataType.orEmpty() }) {
            lines += "    // The packages used here have to be compiled before this file."
        }
        module.imports.forEach { lines += "    $it" }
        module.notes.forEach { lines += "    // TODO: $it." }

        val overrides = mutableListOf<String>()
        val defaulted = mutableListOf<String>()
        for (parameter in module.parameters) {
            val declaration = declaration(parameter)
            if (declaration != null) lines += "    $declaration"
            when {
                parameter.isLocal -> {}
                declaration == null || parameter.isType -> defaulted += parameter.name
                else -> overrides += ".${id(parameter.name)}(${id(parameter.name)})"
            }
        }
        if (defaulted.isNotEmpty()) {
            // Icarus Verilog overrides neither a type nor a string parameter from a named constant.
            lines += "    // TODO: ${defaulted.joinToString()} of ${module.name} cannot be set from here and " +
                "${if (defaulted.size == 1) "keeps its default" else "keep their defaults"}."
        }
        if (module.parameters.isNotEmpty()) lines += ""

        val connections = mutableListOf<String>()
        for (port in module.ports) {
            val name = id(port.name)
            if (port.direction == Direction.INTERFACE) {
                val type = port.dataType.orEmpty()
                lines += "    // TODO: ${port.name} is read as an interface port ($type). Instantiate the interface as"
                lines += "    // '${port.name}' and connect it below; if it is a signal of a data type, declare that instead."
                connections += "// .$name($name)"
                continue
            }
            connections += ".$name($name)"
            when {
                port.direction == Direction.INOUT -> {
                    // A bidirectional port needs a net; the testbench drives it through a variable.
                    lines += "    ${signal(port, "wire", name)};"
                    if (port.dataType == null && port.unpacked.isEmpty()) {
                        val driver = unique(plain(port.name) + "_drv")
                        lines += "    ${signal(port, "logic", driver)} = 'z;  // 'z releases ${port.name}"
                        lines += "    assign $name = $driver;"
                    }
                }
                port.direction != Direction.INPUT -> lines += "    ${signal(port, "logic", name)};"
                port in clocks -> lines += "    logic $name = 0;"
                port in resets -> lines += "    logic $name = ${if (resetIsActiveLow(port.name) == true) 0 else 1};"
                port.unpacked.isEmpty() && port.default != null ->
                    lines += "    ${signal(port, "logic", name)} = ${port.default};"
                else -> {
                    val zero = zero(port)
                    when {
                        zero == null -> lines += "    ${signal(port, "logic", name)};  // TODO: give ${port.name} an initial value"
                        port.unpacked.isEmpty() -> lines += "    ${signal(port, "logic", name)} = $zero;"
                        else -> {
                            lines += "    ${signal(port, "logic", name)};"
                            val indices = List(dimensions(port.unpacked)) { "i$it" }
                            setup += "foreach ($name[${indices.joinToString()}]) " +
                                "$name${indices.joinToString("") { "[$it]" }} = $zero;"
                        }
                    }
                }
            }
        }
        lines += ""

        if (overrides.isEmpty()) {
            lines += "    ${id(module.name)} $instance ("
        } else {
            lines += "    ${id(module.name)} #("
            lines += overrides.mapIndexed { i, it -> "        $it" + if (i < overrides.lastIndex) "," else "" }
            lines += "    ) $instance ("
        }
        // A connection that is commented out keeps its comma, so that it only has to be uncommented.
        val lastConnection = connections.indexOfLast { !it.startsWith("//") }
        lines += connections.mapIndexed { i, it -> "        $it" + if (i < lastConnection) "," else "" }
        lines += "    );"
        lines += ""

        for (port in clocks) lines += "    always #5 ${id(port.name)} = ~${id(port.name)};"
        if (clocks.isNotEmpty()) lines += ""

        lines += "    initial begin"
        lines += "        \$dumpfile(\"$testbench.vcd\");"
        lines += "        \$dumpvars(0, $testbench);"
        setup.forEach { lines += "        $it" }
        lines += ""
        if (resets.isNotEmpty()) {
            // Released on the falling edge, so that the design never sees it change on the edge it samples.
            lines += if (clock != null) "        repeat (4) @(negedge $clock);" else "        #20;"
            for (port in resets) lines += "        ${id(port.name)} = ${if (resetIsActiveLow(port.name) == true) 1 else 0};"
            lines += ""
        }
        lines += "        $STIMULUS_MARKER"
        if (clock != null) {
            lines += "        // Change inputs on the falling edge of $clock and check outputs after the rising edge:"
            lines += "        //     @(negedge $clock); <input> = <value>;"
            lines += "        //     @(posedge $clock); #1; if (<output> !== <expected>) \$error(\"...\");"
            lines += ""
            lines += "        repeat (20) @(posedge $clock);"
        } else {
            lines += "        //     <input> = <value>; #10;"
            lines += "        //     if (<output> !== <expected>) \$error(\"...\");"
            lines += ""
            lines += "        #100;"
        }
        lines += "        \$finish;"
        lines += "    end"
        lines += "endmodule"
        return lines.joinToString("\n", postfix = "\n")
    }

    /** `clk`, `clock`, `aclk`, `sys_clk`, with the `i_` and `_i` of port naming conventions. */
    internal fun isClock(name: String): Boolean = CLOCK.matches(conventional(name))

    /** Null if [name] is not a reset. Active low are `rst_n`, `resetn`, `nrst`, `rst_b`, `rst_ni` and the like. */
    internal fun resetIsActiveLow(name: String): Boolean? =
        RESET.matchEntire(conventional(name))?.let { match -> match.groupValues.drop(1).any { it.isNotEmpty() } }

    private fun conventional(name: String): String {
        val lower = name.lowercase().removePrefix("i_")
        return if (lower.endsWith("_ni")) lower.dropLast(1) else lower.removeSuffix("_i").removeSuffix("_in")
    }

    private fun Port.isScalarInput(): Boolean =
        direction == Direction.INPUT && dataType == null && packed.isEmpty() && unpacked.isEmpty()

    /** The macros the parameters and ports use, and those the definitions of these use in turn. */
    private fun Module.macros(): Set<String> {
        val pending = ArrayDeque<String>()
        parameters.forEach { pending += it.type + it.unpacked + it.default.orEmpty() }
        ports.forEach { pending += it.dataType.orEmpty() + it.packed + it.unpacked + it.default.orEmpty() }
        val macros = HashSet<String>()
        while (pending.isNotEmpty()) {
            for (macro in MACRO.findAll(pending.removeFirst())) {
                if (macros.add(macro.groupValues[1])) defines[macro.groupValues[1]]?.let { pending += it.substringAfter("`define") }
            }
        }
        return macros
    }

    /** The declaration of the signal [name] for [port]; [vector] is the keyword for a plain bit vector. */
    private fun signal(port: Port, vector: String, name: String): String = listOfNotNull(
        vector.takeIf { port.dataType == null || it == "wire" }, port.dataType, "signed".takeIf { port.signed },
        port.packed.ifEmpty { null }, name, port.unpacked.ifEmpty { null },
    ).joinToString(" ")

    /** The `localparam` that mirrors [parameter], so widths that depend on it resolve; null if there is none. */
    private fun declaration(parameter: Parameter): String? {
        val keyword = if (parameter.isType) "localparam type" else "localparam"
        val declared = listOf(keyword, parameter.type, id(parameter.name), parameter.unpacked)
            .filter { it.isNotEmpty() }.joinToString(" ")
        return when {
            parameter.isType -> parameter.default?.let { "$declared = $it;" }
            parameter.type.startsWith("string") -> null
            parameter.default == null -> "$declared = 0;  // TODO: ${parameter.name} has no default"
            else -> "$declared = ${parameter.default};"
        }
    }

    /** What an input of this type is driven with at time zero, or null for a type only its author knows. */
    private fun zero(port: Port): String? = when (port.dataType?.substringBefore(' ')?.substringBefore('[')) {
        null -> "'0"
        "bit", "byte", "shortint", "int", "longint", "integer", "time" -> "0"
        "real", "shortreal", "realtime" -> "0.0"
        "string" -> "\"\""
        else -> null
    }

    /** The number of dimensions in [unpacked], such as 2 for `[0:3][W]`. */
    private fun dimensions(unpacked: String): Int {
        var depth = 0
        var count = 0
        for (c in unpacked) {
            when (c) {
                '[' -> if (depth++ == 0) count++
                ']' -> depth--
            }
        }
        return count
    }

    /** An escaped identifier ends at whitespace, which therefore has to follow it. */
    private fun id(name: String): String = if (name.startsWith("\\")) "$name " else name

    /** [name] as a simple identifier, for names derived from an escaped one. */
    private fun plain(name: String): String = name.removePrefix("\\").replace(Regex("[^A-Za-z0-9_$]"), "_")

    private val MACRO = Regex("`(\\w+)")
    private val CLOCK = Regex("""(?:\w*_)?[a-z]?(?:clk|clock)""")
    private val RESET = Regex("""(?:\w*_)?(?:(n)?[as]?(?:rst|reset)(n)?|[ph]reset(n))(_n|_b|_l)?""")
}
