package com.jotrorox.verilogtoolbox.quartus.tcl

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.TokenType
import com.jotrorox.verilogtoolbox.quartus.Quartus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Completes command names, option flags, assignment names after `-name` and the values of some assignments.
 * There is no syntax tree to ask, so it lexes the command around the caret.
 */
class QuartusTclCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val text = parameters.editor.document.immutableCharSequence
        val context = QuartusTclCompletionContext.at(text, parameters.offset) ?: return
        val sdc = parameters.originalFile.fileType == QuartusTclSdcFileType
        val words = context.words
        val command = words.firstOrNull()
        val prefix = context.prefix
        when {
            context.atCommand -> {
                val specific = if (sdc) QuartusTclCommands.SDC else QuartusTclCommands.QUARTUS
                val source = if (sdc) "SDC" else "Quartus"
                val names = result.withPrefixMatcher(prefix)
                specific.forEach { names.addElement(commandElement(it, source, bold = true)) }
                val builtIns = QuartusTclCommands.TCL - specific
                builtIns.forEach { names.addElement(commandElement(it, "Tcl", bold = false)) }
            }
            command in QuartusTclCommands.ASSIGNMENT && words.last() == "-name" -> {
                val names = result.withPrefixMatcher(prefix)
                QuartusTclAssignments.names().forEach { (name, builtIn) ->
                    val element = LookupElementBuilder.create(name).withTypeText(if (builtIn) "assignment" else "qdf")
                    names.addElement(PrioritizedLookupElement.withPriority(element, if (builtIn) 1.0 else 0.0))
                }
            }
            command in QuartusTclCommands.ASSIGNMENT && words.size >= 3 && words[words.size - 2] == "-name" &&
                !prefix.startsWith("-") -> {
                val values = result.withPrefixMatcher(PlainPrefixMatcher(prefix))
                QuartusTclAssignments.values(words.last()).forEach { values.addElement(value(it)) }
            }
            prefix.startsWith("-") || prefix.isEmpty() && words.lastOrNull()?.startsWith("-") == false -> {
                val options = QuartusTclOptions.of(command ?: return) ?: return
                val flags = result.withPrefixMatcher(PlainPrefixMatcher(prefix))
                options.forEach { flags.addElement(LookupElementBuilder.create(it)) }
            }
        }
    }

    private fun commandElement(name: String, source: String, bold: Boolean): LookupElement =
        LookupElementBuilder.create(name).withTypeText(source).withBoldness(bold)

    /** A value with spaces goes in quotes, but may be found by what it says without them. */
    private fun value(text: String): LookupElement {
        if (!text.contains(' ')) return LookupElementBuilder.create(text)
        return LookupElementBuilder.create("\"$text\"").withLookupString(text).withPresentableText(text)
            .withInsertHandler(SKIP_CLOSING_QUOTE)
    }

    private companion object {
        /** When the editor has already closed the quote the user opened, the inserted value brings its own. */
        val SKIP_CLOSING_QUOTE = InsertHandler<LookupElement> { context, _ ->
            val tail = context.tailOffset
            if (context.document.charsSequence.getOrNull(tail) == '"') context.document.deleteString(tail, tail + 1)
        }
    }
}

/**
 * The command the caret is in: its finished [words], and the [prefix] of the word being typed.
 * [atCommand] says the word being typed is a command name.
 */
internal class QuartusTclCompletionContext(val words: List<String>, val prefix: String, val atCommand: Boolean) {
    companion object {
        /** Null where nothing is to be completed, such as in a comment. */
        fun at(text: CharSequence, offset: Int): QuartusTclCompletionContext? {
            val start = logicalLineStart(text, offset)
            // Each nested [ or { holds its own command; the innermost one is the caret's.
            class Frame(val opener: Char?) {
                val words = mutableListOf<String>()
                /** Whether the last word may still grow: no whitespace or separator came after it. */
                var inWord = false
                /** Where the last word starts, or -1 when it holds a nested command and so is not being typed. */
                var wordStart = -1

                /** Tokens without whitespace between them, such as led_$i or led[0], make up one word. */
                fun add(piece: String, start: Int) {
                    if (inWord) {
                        words[words.size - 1] += piece
                    } else {
                        words += piece
                        wordStart = start
                    }
                    inWord = true
                }
            }
            val frames = ArrayDeque(listOf(Frame(null)))
            val lexer = QuartusTclLexer()
            lexer.start(text, start, offset, 0)
            while (true) {
                val type = lexer.tokenType ?: break
                val frame = frames.last()
                val piece = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                when (type) {
                    TokenType.WHITE_SPACE -> frame.inWord = false
                    QuartusTclTokenTypes.COMMENT -> if (lexer.tokenEnd >= offset) return null
                    QuartusTclTokenTypes.SEMICOLON -> {
                        frame.words.clear()
                        frame.inWord = false
                    }
                    QuartusTclTokenTypes.LBRACKET, QuartusTclTokenTypes.LBRACE -> {
                        frame.add(piece, lexer.tokenStart)
                        frame.wordStart = -1
                        frames.addLast(Frame(piece[0]))
                    }
                    QuartusTclTokenTypes.RBRACKET, QuartusTclTokenTypes.RBRACE -> {
                        if (frames.size > 1) frames.removeLast()
                        frames.last().add(piece, lexer.tokenStart)
                        frames.last().wordStart = -1
                    }
                    else -> frame.add(piece, lexer.tokenStart)
                }
                lexer.advance()
            }
            val frame = frames.last()
            val typing = frame.inWord && frame.wordStart >= 0
            val words = if (frame.inWord) frame.words.dropLast(1) else frame.words.toList()
            val prefix = if (typing) text.subSequence(frame.wordStart, offset).toString() else ""
            if (prefix.startsWith("$")) return null
            // A brace word opened on this line is a list, as in [get_ports {clk}], not a script.
            return QuartusTclCompletionContext(words, prefix, atCommand = words.isEmpty() && frame.opener != '{')
        }

        /** Where the line holding [offset] starts, counting lines that a backslash continues as one. */
        private fun logicalLineStart(text: CharSequence, offset: Int): Int {
            var start = offset
            while (true) {
                while (start > 0 && text[start - 1] != '\n') start--
                if (start == 0) return 0
                var end = start - 1
                if (end > 0 && text[end - 1] == '\r') end--
                if (end > 0 && text[end - 1] == '\\') start = end - 1 else return start
            }
        }
    }
}

/** Option flags of the commands that take them. */
internal object QuartusTclOptions {
    private val ASSIGNMENT = listOf(
        "-name", "-to", "-from", "-section_id", "-entity", "-library", "-remove", "-disable", "-comment", "-tag",
    )
    private val COLLECTION = listOf("-nocase", "-nowarn", "-compatibility_mode")
    private val RISE_FALL = listOf("-rise_from", "-fall_from", "-rise_to", "-fall_to")
    private val PATH = listOf("-from", "-to", "-through") + RISE_FALL

    private val OPTIONS: Map<String, List<String>> = mapOf(
        "set_global_assignment" to ASSIGNMENT,
        "set_instance_assignment" to ASSIGNMENT,
        "set_io_assignment" to ASSIGNMENT,
        "set_parameter" to ASSIGNMENT,
        "set_location_assignment" to ASSIGNMENT - "-name" - "-library",
        "create_clock" to listOf("-name", "-period", "-waveform", "-add"),
        "create_generated_clock" to listOf(
            "-name", "-source", "-master_clock", "-divide_by", "-multiply_by", "-duty_cycle", "-invert", "-phase",
            "-offset", "-edges", "-edge_shift", "-add",
        ),
        "derive_pll_clocks" to listOf("-create_base_clocks", "-use_net_name"),
        "derive_clock_uncertainty" to listOf("-add", "-overwrite"),
        "set_input_delay" to listOf(
            "-clock", "-clock_fall", "-max", "-min", "-rise", "-fall", "-add_delay", "-reference_pin",
            "-source_latency_included",
        ),
        "set_output_delay" to listOf(
            "-clock", "-clock_fall", "-max", "-min", "-rise", "-fall", "-add_delay", "-reference_pin",
            "-source_latency_included",
        ),
        "set_false_path" to PATH + listOf("-setup", "-hold"),
        "set_multicycle_path" to PATH + listOf("-setup", "-hold", "-start", "-end"),
        "set_max_delay" to PATH,
        "set_min_delay" to PATH,
        "set_clock_groups" to listOf(
            "-group", "-asynchronous", "-exclusive", "-logically_exclusive", "-physically_exclusive",
        ),
        "set_clock_uncertainty" to listOf("-from", "-to", "-setup", "-hold", "-add") + RISE_FALL,
        "set_clock_latency" to listOf("-source", "-clock", "-late", "-early", "-rise", "-fall"),
        "set_max_skew" to listOf("-from", "-to", "-exclude", "-include", "-get_skew_value_from_clock_period"),
        "get_ports" to COLLECTION,
        "get_pins" to COLLECTION + listOf("-hierarchical", "-no_duplicates"),
        "get_cells" to COLLECTION + "-hierarchical",
        "get_nets" to COLLECTION + "-hierarchical",
        "get_registers" to COLLECTION + listOf("-hierarchical", "-no_duplicates"),
        "get_keepers" to COLLECTION + listOf("-hierarchical", "-no_duplicates"),
        "get_nodes" to COLLECTION + listOf("-hierarchical", "-no_duplicates"),
        "get_clocks" to listOf("-nocase", "-nowarn", "-include_generated_clocks", "-of_objects"),
    )

    fun of(command: String): List<String>? = OPTIONS[command]
}

/**
 * Assignment names and values: a curated list of the common ones, plus every global assignment that the
 * Quartus installation's `assignment_defaults.qdf` names.
 */
internal object QuartusTclAssignments {
    /** The assignment names a `.qdf` sets, and those of them whose default is On or Off. */
    internal class Defaults(val names: List<String>, val booleans: Set<String>)

    private val installed = ConcurrentHashMap<Path, Defaults>()

    private val BUILT_IN = """
        FAMILY DEVICE TOP_LEVEL_ENTITY ORIGINAL_QUARTUS_VERSION LAST_QUARTUS_VERSION PROJECT_CREATION_TIME_DATE
        PROJECT_OUTPUT_DIRECTORY NUM_PARALLEL_PROCESSORS VERILOG_FILE SYSTEMVERILOG_FILE VHDL_FILE QIP_FILE
        QSYS_FILE IP_FILE SIP_FILE SDC_FILE TCL_SCRIPT_FILE BDF_FILE MIF_FILE HEX_FILE SOURCE_FILE
        SIGNALTAP_FILE USE_SIGNALTAP_FILE ENABLE_SIGNALTAP SEARCH_PATH USER_LIBRARIES VERILOG_MACRO
        VERILOG_INPUT_VERSION VERILOG_SHOW_LMF_MAPPING_MESSAGES VHDL_INPUT_VERSION IO_STANDARD
        STRATIX_DEVICE_IO_STANDARD WEAK_PULL_UP_RESISTOR CURRENT_STRENGTH_NEW SLEW_RATE FAST_INPUT_REGISTER
        FAST_OUTPUT_REGISTER FAST_OUTPUT_ENABLE_REGISTER VIRTUAL_PIN OUTPUT_TERMINATION INPUT_TERMINATION
        OPTIMIZATION_MODE OPTIMIZATION_TECHNIQUE SEED FITTER_EFFORT PLACEMENT_EFFORT_MULTIPLIER
        ROUTER_TIMING_OPTIMIZATION_LEVEL PHYSICAL_SYNTHESIS_COMBO_LOGIC PHYSICAL_SYNTHESIS_REGISTER_DUPLICATION
        PHYSICAL_SYNTHESIS_REGISTER_RETIMING PHYSICAL_SYNTHESIS_EFFORT OPTIMIZE_HOLD_TIMING
        OPTIMIZE_MULTI_CORNER_TIMING TIMING_ANALYZER_MULTICORNER_ANALYSIS RESERVE_ALL_UNUSED_PINS
        RESERVE_ALL_UNUSED_PINS_WEAK_PULLUP CYCLONEII_RESERVE_NCEO_AFTER_CONFIGURATION
        CYCLONEIII_CONFIGURATION_SCHEME STRATIXV_CONFIGURATION_SCHEME USE_CONFIGURATION_DEVICE GENERATE_RBF_FILE
        ON_CHIP_BITSTREAM_DECOMPRESSION ENABLE_INIT_DONE_OUTPUT ENABLE_DEVICE_WIDE_RESET ENABLE_DEVICE_WIDE_OE
        MIN_CORE_JUNCTION_TEMP MAX_CORE_JUNCTION_TEMP NOMINAL_CORE_SUPPLY_VOLTAGE DEVICE_FILTER_PACKAGE
        DEVICE_FILTER_PIN_COUNT DEVICE_FILTER_SPEED_GRADE ERROR_CHECK_FREQUENCY_DIVISOR INTERNAL_FLASH_UPDATE_MODE
        POWER_PRESET_COOLING_SOLUTION POWER_BOARD_THERMAL_MODEL FLOW_ENABLE_POWER_ANALYZER PARTITION_NETLIST_TYPE
        PARTITION_FITTER_PRESERVATION_LEVEL PARTITION_COLOR EDA_SIMULATION_TOOL EDA_OUTPUT_DATA_FORMAT
        EDA_TIME_SCALE EDA_GENERATE_FUNCTIONAL_NETLIST EDA_TEST_BENCH_ENABLE_STATUS
        EDA_NATIVELINK_SIMULATION_TEST_BENCH EDA_TEST_BENCH_NAME EDA_TEST_BENCH_FILE EDA_TEST_BENCH_MODULE_NAME
        EDA_DESIGN_INSTANCE_NAME AUTO_RAM_RECOGNITION AUTO_ROM_RECOGNITION AUTO_SHIFT_REGISTER_RECOGNITION
        ALLOW_ANY_RAM_SIZE_FOR_RECOGNITION SMART_RECOMPILE PARALLEL_SYNTHESIS SYNTH_TIMING_DRIVEN_SYNTHESIS
        PRESERVE_REGISTER DONT_MERGE_REGISTER MAX_FANOUT GLOBAL_SIGNAL SYNCHRONIZER_IDENTIFICATION
        MESSAGE_DISABLE PRE_FLOW_SCRIPT_FILE POST_FLOW_SCRIPT_FILE DEVICE_MIGRATION_LIST
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()

    private val BUILT_IN_BOOLEANS = """
        WEAK_PULL_UP_RESISTOR FAST_INPUT_REGISTER FAST_OUTPUT_REGISTER FAST_OUTPUT_ENABLE_REGISTER VIRTUAL_PIN
        ENABLE_SIGNALTAP PHYSICAL_SYNTHESIS_COMBO_LOGIC PHYSICAL_SYNTHESIS_REGISTER_DUPLICATION
        PHYSICAL_SYNTHESIS_REGISTER_RETIMING OPTIMIZE_MULTI_CORNER_TIMING TIMING_ANALYZER_MULTICORNER_ANALYSIS
        USE_CONFIGURATION_DEVICE GENERATE_RBF_FILE ON_CHIP_BITSTREAM_DECOMPRESSION ENABLE_INIT_DONE_OUTPUT
        ENABLE_DEVICE_WIDE_RESET ENABLE_DEVICE_WIDE_OE FLOW_ENABLE_POWER_ANALYZER EDA_GENERATE_FUNCTIONAL_NETLIST
        AUTO_RAM_RECOGNITION AUTO_ROM_RECOGNITION AUTO_SHIFT_REGISTER_RECOGNITION ALLOW_ANY_RAM_SIZE_FOR_RECOGNITION
        SMART_RECOMPILE PARALLEL_SYNTHESIS SYNTH_TIMING_DRIVEN_SYNTHESIS PRESERVE_REGISTER DONT_MERGE_REGISTER
        GLOBAL_SIGNAL VERILOG_SHOW_LMF_MAPPING_MESSAGES
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val IO_STANDARDS = listOf(
        "3.3-V LVTTL", "3.3-V LVCMOS", "3.0-V LVTTL", "3.0-V LVCMOS", "2.5 V", "1.8 V", "1.5 V", "1.2 V",
        "3.3 V Schmitt Trigger", "2.5 V Schmitt Trigger", "1.8 V Schmitt Trigger", "3.3-V PCI", "3.3-V PCI-X",
        "LVDS", "LVDS_E_3R", "BLVDS", "MINI-LVDS", "RSDS", "PPDS", "SSTL-2 Class I", "SSTL-2 Class II",
        "SSTL-18 Class I", "SSTL-18 Class II", "SSTL-15", "SSTL-15 Class I", "SSTL-15 Class II", "SSTL-135",
        "1.2-V HSTL", "1.5-V HSTL Class I", "1.8-V HSTL Class I", "Differential 1.5-V SSTL Class I",
        "Differential 1.8-V SSTL Class I", "Differential 2.5-V SSTL Class I", "HSUL-12",
    )

    private val UNUSED_PINS = listOf(
        "AS INPUT TRI-STATED", "AS INPUT TRI-STATED WITH WEAK PULL-UP", "AS INPUT TRI-STATED WITH BUS-HOLD",
        "AS OUTPUT DRIVING GROUND", "AS OUTPUT DRIVING AN UNSPECIFIED SIGNAL",
    )

    private val VALUES: Map<String, List<String>> = mapOf(
        "IO_STANDARD" to IO_STANDARDS,
        "STRATIX_DEVICE_IO_STANDARD" to IO_STANDARDS,
        "FAMILY" to listOf(
            "MAX II", "MAX V", "MAX 10", "Cyclone IV E", "Cyclone IV GX", "Cyclone V", "Cyclone 10 LP",
            "Cyclone 10 GX", "Arria II GX", "Arria V", "Arria 10", "Stratix IV", "Stratix V", "Stratix 10",
            "Agilex 5", "Agilex 7",
        ),
        "FITTER_EFFORT" to listOf("AUTO FIT", "STANDARD FIT", "FAST FIT"),
        "OPTIMIZATION_MODE" to listOf(
            "BALANCED", "HIGH PERFORMANCE EFFORT", "AGGRESSIVE PERFORMANCE", "HIGH POWER EFFORT",
            "AGGRESSIVE POWER", "AGGRESSIVE AREA", "AGGRESSIVE COMPILE TIME",
        ),
        "OPTIMIZATION_TECHNIQUE" to listOf("SPEED", "AREA", "BALANCED"),
        "VERILOG_INPUT_VERSION" to listOf("VERILOG_1995", "VERILOG_2001", "SYSTEMVERILOG_2005"),
        "VHDL_INPUT_VERSION" to listOf("VHDL_1987", "VHDL_1993", "VHDL_2008"),
        "CURRENT_STRENGTH_NEW" to listOf("MINIMUM CURRENT", "MAXIMUM CURRENT", "4MA", "8MA", "12MA", "16MA"),
        "SLEW_RATE" to listOf("0", "1", "2"),
        "RESERVE_ALL_UNUSED_PINS" to UNUSED_PINS,
        "RESERVE_ALL_UNUSED_PINS_WEAK_PULLUP" to UNUSED_PINS,
        "CYCLONEII_RESERVE_NCEO_AFTER_CONFIGURATION" to listOf("USE AS REGULAR IO", "USE AS PROGRAMMING PIN"),
        "PARTITION_NETLIST_TYPE" to listOf("SOURCE", "POST_SYNTH", "POST_FIT", "EMPTY"),
        "PARTITION_FITTER_PRESERVATION_LEVEL" to listOf("PLACEMENT_AND_ROUTING", "PLACEMENT"),
        "EDA_OUTPUT_DATA_FORMAT" to listOf("VERILOG HDL", "SYSTEMVERILOG HDL", "VHDL"),
        "OPTIMIZE_HOLD_TIMING" to listOf("ALL PATHS", "IO PATHS AND MINIMUM TPD PATHS", "OFF"),
    )

    /** Every name to offer, each with whether it is one of the curated built-in names. */
    fun names(): List<Pair<String, Boolean>> {
        val fromInstallation = installedDefaults()?.names.orEmpty()
        return BUILT_IN.map { it to true } + (fromInstallation - BUILT_IN.toSet()).map { it to false }
    }

    /** The values of assignment [name]: known lists, or ON and OFF for a switch. */
    fun values(name: String): List<String> {
        val upper = name.uppercase()
        VALUES[upper]?.let { return it }
        val boolean = upper in BUILT_IN_BOOLEANS || installedDefaults()?.booleans?.contains(upper) == true
        return if (boolean) listOf("ON", "OFF") else emptyList()
    }

    /** The installation's defaults, read once per installation; completion runs off the EDT, so reading is fine. */
    private fun installedDefaults(): Defaults? {
        val binDirectory = Quartus.find()?.binDirectory ?: return null
        return installed.computeIfAbsent(binDirectory) { directory ->
            val file = directory.resolve("assignment_defaults.qdf")
            val lines = runCatching { Files.readAllLines(file, Charsets.ISO_8859_1) }.getOrDefault(emptyList())
            parseDefaults(lines)
        }
    }

    /** Reads lines such as `set_global_assignment -name SEED 1`; one name may appear once per family. */
    internal fun parseDefaults(lines: List<String>): Defaults {
        val names = LinkedHashSet<String>()
        val booleans = HashSet<String>()
        for (line in lines) {
            val match = DEFAULT_LINE.find(line) ?: continue
            val name = match.groupValues[1].uppercase()
            names += name
            if (match.groupValues[2].equals("On", ignoreCase = true) ||
                match.groupValues[2].equals("Off", ignoreCase = true)
            ) {
                booleans += name
            }
        }
        return Defaults(names.toList(), booleans)
    }

    private val DEFAULT_LINE = Regex("""^\s*set_global_assignment\s+-name\s+(\w+)\s+(\S+)""")
}
