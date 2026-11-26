package com.jotrorox.verilogtoolbox.verilator

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/**
 * What Verilator generated for a model and what compiling it takes, as it lists it with `--make json` (or
 * `--make cmake` before 5.030). Paths are absolute.
 */
data class VerilatorManifest(
    /** The model's classes and support files, which Verilator writes again when the design changes. */
    val generated: List<Path>,
    /** The runtime files every executable needs once, from `VERILATOR_ROOT/include`. */
    val global: List<Path>,
    /** C++ files that were given to Verilator, such as the `main` that `--main` writes. */
    val userClasses: List<Path>,
    /** The Verilog files the model was made from: when one changes, it has to be verilated again. */
    val dependencies: List<Path>,
    val timing: Boolean,
    val traceVcd: Boolean,
    val traceFst: Boolean,
    val traceSaif: Boolean,
    val coverage: Boolean,
    val dpi: Boolean,
    val systemC: Boolean,
    val threads: Int,
    /** From `-CFLAGS` and `-LDFLAGS`. */
    val cflags: List<String>,
    val ldflags: List<String>,
    /** Whether `--hierarchical` split the design into blocks, which this plugin does not build. */
    val hierarchical: Boolean = false,
) {
    val trace: Boolean get() = traceVcd || traceFst || traceSaif

    companion object {
        /** Reads `<prefix>.json`. Relative paths start at [directory], where Verilator ran. */
        fun parseJson(text: String, directory: Path): VerilatorManifest {
            val manifest = JsonParser.parseString(text).asJsonObject
            val options = manifest.objectAt("options")
            val sources = manifest.objectAt("sources")
            fun paths(name: String) = sources.strings(name).map { directory.resolve(it).normalize() }
            val submodules = manifest.get("submodules")
            return VerilatorManifest(
                generated = (paths("classes_fast") + paths("classes_slow") + paths("support_fast") + paths("support_slow")).distinct(),
                global = paths("global"),
                userClasses = paths("user_classes"),
                dependencies = paths("deps"),
                timing = options.flag("use_timing"),
                traceVcd = options.flag("trace_vcd"),
                traceFst = options.flag("trace_fst"),
                traceSaif = options.flag("trace_saif"),
                coverage = options.flag("coverage"),
                dpi = options.flag("dpi"),
                systemC = options.flag("system_c"),
                threads = options.get("threads")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
                cflags = options.strings("cflags"),
                ldflags = options.strings("ldflags"),
                hierarchical = submodules is JsonArray && submodules.size() > 0,
            )
        }

        /**
         * Reads `<prefix>.cmake` of a Verilator before 5.030: `set(<prefix>_<NAME> <values>)` lines, in
         * which `${VERILATOR_ROOT}` stands for [root].
         */
        fun parseCmake(text: String, prefix: String, directory: Path, root: Path): VerilatorManifest {
            val values = HashMap<String, List<String>>()
            for (line in text.lineSequence()) {
                val match = SET.matchEntire(line.trim()) ?: continue
                val name = match.groupValues[1].removePrefix("${prefix}_").takeIf { it != match.groupValues[1] } ?: continue
                values[name] = words(match.groupValues[2]).map { it.replace("\${VERILATOR_ROOT}", root.toString()) }
            }
            fun paths(name: String) = values[name].orEmpty().filter { it.isNotEmpty() }.map { directory.resolve(it).normalize() }
            fun flag(vararg names: String) = names.any { values[it]?.firstOrNull() == "1" }
            return VerilatorManifest(
                generated = (paths("CLASSES_FAST") + paths("CLASSES_SLOW") + paths("SUPPORT_FAST") + paths("SUPPORT_SLOW")).distinct(),
                global = paths("GLOBAL"),
                userClasses = paths("USER_CLASSES"),
                dependencies = paths("DEPS"),
                timing = flag("TIMING"),
                // Before TRACE_VCD there was TRACE, which meant VCD.
                traceVcd = flag("TRACE_VCD") || flag("TRACE") && !flag("TRACE_FST"),
                traceFst = flag("TRACE_FST"),
                traceSaif = flag("TRACE_SAIF"),
                coverage = flag("COVERAGE"),
                dpi = flag("DPI"),
                systemC = flag("SC"),
                threads = values["THREADS"]?.firstOrNull()?.toIntOrNull() ?: 1,
                cflags = values["USER_CFLAGS"].orEmpty().filter { it.isNotEmpty() },
                ldflags = values["USER_LDLIBS"].orEmpty().filter { it.isNotEmpty() },
            )
        }

        /** `set(NAME value value)`, possibly with `CACHE <type> "doc"` after the values. */
        private val SET = Regex("""^set\((\w+)\s*(.*)\)$""")

        /** The values of a `set`: quoted or not, up to a `CACHE` keyword. */
        private fun words(text: String): List<String> {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var quoted = false
            var pending = false
            var index = 0
            while (index < text.length) {
                val c = text[index++]
                when {
                    c == '"' -> {
                        quoted = !quoted
                        pending = true
                    }
                    c == '\\' && quoted && index < text.length -> word.append(text[index++])
                    c.isWhitespace() && !quoted -> {
                        if (pending) words += word.toString()
                        word.setLength(0)
                        pending = false
                    }
                    else -> {
                        word.append(c)
                        pending = true
                    }
                }
            }
            if (pending) words += word.toString()
            val cache = words.indexOf("CACHE")
            return if (cache >= 0) words.subList(0, cache) else words
        }

        private fun JsonObject.objectAt(name: String): JsonObject = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        private fun JsonObject.flag(name: String): Boolean =
            get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { if (it.isBoolean) it.asBoolean else it.asString == "1" } ?: false

        /** A list of strings; Verilator writes some lists as one string of words. */
        private fun JsonObject.strings(name: String): List<String> {
            val element: JsonElement = get(name) ?: return emptyList()
            return when {
                element.isJsonArray -> element.asJsonArray.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString }
                element.isJsonPrimitive -> element.asString.split(' ').filter { it.isNotBlank() }
                else -> emptyList()
            }.filter { it.isNotBlank() }
        }
    }
}
