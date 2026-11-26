package com.jotrorox.verilogtoolbox.gowin.eda

import com.google.gson.JsonParser
import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import com.jotrorox.verilogtoolbox.gowin.GowinProject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/** The kinds of files a project lists, by the type its `.gprj` gives them, in the order GOWIN EDA lists them. */
enum class GowinEdaFileKind(val type: String, val extensions: Set<String>) {
    // GOWIN EDA lists SystemVerilog and Verilog headers as Verilog too; the standard is a project option.
    VERILOG("file.verilog", setOf("v", "sv", "vh")),
    VHDL("file.vhdl", setOf("vhd", "vhdl")),
    NETLIST("file.netlist", setOf("vo", "vg")),
    CST("file.cst", setOf("cst")),
    SDC("file.sdc", setOf("sdc")),
    FDC("file.fdc", setOf("fdc")),
    GAO("file.gao", setOf("gao")),
    GPA("file.gpa", setOf("gpa")),
    GVIO("file.gvio", setOf("gvio")),
    OTHER("file.other", emptySet());

    companion object {
        fun ofType(type: String?): GowinEdaFileKind = entries.firstOrNull { it.type == type } ?: OTHER

        /** The kind a file is listed as, by its extension; null for files that are only listed as "other". */
        fun ofFile(file: Path): GowinEdaFileKind? = file.extension.lowercase().let { extension ->
            entries.firstOrNull { extension in it.extensions }
        }
    }
}

/** A file of a project; one that is not [enabled] is listed but left out of the flow. */
data class GowinEdaFile(val path: Path, val kind: GowinEdaFileKind, val enabled: Boolean)

/**
 * The device of a project as its `.gprj` names it: [name] is the device with its version, such as `GW1NR-9C`,
 * which is also what the programmer is told; [partNumber] the part with package and speed grade, such as
 * `GW1NR-LV9QN88PC6/I5`; [id] the key of the device catalog, such as `gw1nr9c-004`.
 */
data class GowinEdaDevice(val name: String, val partNumber: String, val id: String) {
    override fun toString(): String = listOf(partNumber, name).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

/**
 * The options of a project's flow that the IDE needs, from `impl/project_process_config.json`, which GOWIN
 * EDA writes and `gw_sh` reads. Every option it leaves out has its default.
 */
data class GowinEdaOptions(
    val topModule: String? = null,
    val outputBaseName: String? = null,
    val includePaths: List<String> = emptyList(),
    /** Such as `Vlg_Std_2001` or `Vlg_Std_Sysv2017`. */
    val verilogStandard: String? = null,
) {
    companion object {
        /** Reads the options from the text of a process configuration; anything unreadable has its default. */
        fun parse(text: String?): GowinEdaOptions {
            if (text.isNullOrBlank()) return GowinEdaOptions()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return GowinEdaOptions()
            fun string(key: String): String? =
                json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val includes = json.get("IncludePath")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
            return GowinEdaOptions(string("TopModule"), string("OUTPUT_BASE_NAME"), includes, string("Verilog_Standard"))
        }
    }
}

/**
 * A GOWIN EDA project: a `.gprj` with the device and the files of the design, and the options of its flow
 * in `impl/project_process_config.json` beside it. The flow writes everything else into `impl` too:
 * `impl/gwsynthesis` for synthesis, `impl/pnr` for place and route and the bitstream.
 */
class GowinEdaProject private constructor(
    val file: Path,
    val device: GowinEdaDevice?,
    val files: List<GowinEdaFile>,
    val options: GowinEdaOptions,
) {
    val directory: Path get() = file.parent

    /** The name of the project, which is the file's name without `.gprj`. */
    val name: String get() = file.nameWithoutExtension

    val implDirectory: Path get() = directory.resolve(IMPL)
    val optionsFile: Path get() = implDirectory.resolve(OPTIONS_FILE)

    /** What the output files are called: the project's name unless the options say otherwise. */
    val outputBaseName: String get() = options.outputBaseName ?: name

    /** A file of synthesis, such as `log` for `impl/gwsynthesis/<name>.log`. */
    fun synthesisOutput(suffix: String): Path = implDirectory.resolve("gwsynthesis").resolve(outputName(suffix))

    /** A file of place and route, such as `fs` for the bitstream `impl/pnr/<name>.fs`. */
    fun pnrOutput(suffix: String): Path = implDirectory.resolve("pnr").resolve(outputName(suffix))

    /** `_syn.rpt.html` follows the name directly, `log` after a dot. */
    private fun outputName(suffix: String): String =
        if (suffix.startsWith("_") || suffix.startsWith(".")) "$outputBaseName$suffix" else "$outputBaseName.$suffix"

    /** The bitstream place and route writes, which the programmer loads. */
    val bitstream: Path get() = pnrOutput("fs")

    val enabledFiles: List<GowinEdaFile> get() = files.filter { it.enabled }

    /** The Verilog and SystemVerilog sources of the flow, headers left out: those are only included. */
    val verilogSources: List<Path>
        get() = enabledFiles.filter { it.kind == GowinEdaFileKind.VERILOG && it.path.extension.lowercase() in SOURCE_EXTENSIONS }
            .map { it.path }

    /** The physical constraints the flow places the design with: the first enabled `.cst`. */
    val constraintFile: Path? get() = enabledFiles.firstOrNull { it.kind == GowinEdaFileKind.CST }?.path

    val timingConstraintFiles: List<Path> get() = enabledFiles.filter { it.kind == GowinEdaFileKind.SDC }.map { it.path }

    /**
     * The top module of the flow: the one the options name, else the one the IDE works out from the sources
     * and the constraints. GOWIN EDA left alone takes the first module nobody instantiates, which may be
     * a leftover rather than the design. Reads the sources: not for the UI thread.
     */
    val topModule: String? by lazy { options.topModule ?: detectedTopModule }

    /** The top module the IDE works out: the module whose ports the constraints place the most of, see [GowinProject.topModule]. */
    val detectedTopModule: String? by lazy { GowinProject.topModule(verilogSources, constraintFile) }

    /** Whether [topModule] is the one the options name, rather than one worked out. */
    val isTopModuleSet: Boolean get() = options.topModule != null

    /** The directories searched for included files: the options' include paths, those of listed headers, and the project's. */
    val includeDirectories: List<Path>
        get() {
            val headers = enabledFiles.map { it.path }.filter { it.extension.lowercase() in HEADER_EXTENSIONS }.mapNotNull { it.parent }
            // A Path is itself a list of its names, so it is wrapped to be added as one.
            return (options.includePaths.map(::resolve) + headers + listOf(directory)).distinct()
        }

    /** The design as the other tools are given it: the Verilog sources, include directories and the top module. */
    fun fileList(): FileList = FileList(
        sources = verilogSources,
        includeDirectories = includeDirectories,
        defines = emptyList(),
        libraryDirectories = listOf(directory),
        libraryFiles = emptyList(),
        libraryExtensions = VerilogDesign.LIBRARY_EXTENSIONS,
        topModules = listOfNotNull(topModule),
        ignored = emptyList(),
        errors = emptyList(),
    )

    /** [path] as the `.gprj` names it, relative to the project's directory or absolute. */
    fun resolve(path: String): Path =
        runCatching { directory.resolve(path.replace('\\', '/')).normalize() }.getOrElse { directory.resolve("invalid") }

    override fun equals(other: Any?): Boolean = other is GowinEdaProject && other.file == file
    override fun hashCode(): Int = file.hashCode()
    override fun toString(): String = file.toString()

    companion object {
        const val EXTENSION = "gprj"
        const val IMPL = "impl"

        /** The options file `gw_sh` reads; it writes `<name>_process_config.json` beside it, which it does not read. */
        const val OPTIONS_FILE = "project_process_config.json"

        private val SOURCE_EXTENSIONS = setOf("v", "sv")
        private val HEADER_EXTENSIONS = setOf("vh", "svh")

        /** The project of the `.gprj` [file], with [text] for unsaved changes when given. Null for other files and ones that do not exist. */
        fun of(file: Path, text: String? = null): GowinEdaProject? {
            val absolute = file.toAbsolutePath().normalize()
            if (!isProjectFile(absolute) || !absolute.isRegularFile()) return null
            val content = text ?: runCatching { Files.readString(absolute) }.getOrNull() ?: return null
            val options = runCatching { Files.readString(absolute.resolveSibling(IMPL).resolve(OPTIONS_FILE)) }.getOrNull()
            return parse(absolute, content, options)
        }

        /** The project of [text] at [file], with the options of [options], without touching the disk. */
        fun parse(file: Path, text: String, options: String? = null): GowinEdaProject {
            val directory = file.parent
            val device = DEVICE.find(text)?.let { match ->
                val attributes = attributes(match.groupValues[1])
                GowinEdaDevice(attributes["name"].orEmpty(), attributes["pn"].orEmpty(), unescape(match.groupValues[2].trim()))
            }
            val files = FILE.findAll(text).mapNotNull { match ->
                val attributes = attributes(match.groupValues[1])
                val path = attributes["path"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val resolved = runCatching { directory.resolve(path.replace('\\', '/')).normalize() }.getOrNull() ?: return@mapNotNull null
                GowinEdaFile(resolved, GowinEdaFileKind.ofType(attributes["type"]), attributes["enable"] != "0")
            }.toList()
            return GowinEdaProject(file, device, files, GowinEdaOptions.parse(options))
        }

        fun isProjectFile(file: Path): Boolean = file.extension.lowercase() == EXTENSION

        internal val DEVICE = Regex("""<Device\b([^>]*?)(?:/>|>([^<]*)</Device>)""")
        internal val FILE = Regex("""<File\b([^>]*?)/?>""")
        private val ATTRIBUTE = Regex("""([\w.-]+)\s*=\s*"([^"]*)"""")

        internal fun attributes(text: String): Map<String, String> =
            ATTRIBUTE.findAll(text).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

        internal fun unescape(text: String): String =
            text.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}

/**
 * Changes to the text of a `.gprj`, each touching only the element it is about, so that what GOWIN EDA wrote
 * stays as it was. They are text edits rather than a rewrite of the XML because GOWIN EDA's own header,
 * `<?xml version="1" …?>`, is not XML a strict parser accepts.
 */
object GowinEdaProjectText {
    private const val FILE_INDENT = "        "

    /**
     * Lists the file called [listed] (as [path] names it) as [kind], among the files of its kind, which is the
     * order GOWIN EDA keeps. Nothing changes when [same] already holds for a listed path.
     */
    fun addFile(text: String, listed: String, kind: GowinEdaFileKind, same: (String) -> Boolean = { it == listed }): String {
        val files = GowinEdaProject.FILE.findAll(text).toList()
        if (files.any { match -> GowinEdaProject.attributes(match.groupValues[1])["path"]?.let(same) == true }) return text
        val element = """<File path="${escape(listed)}" type="${kind.type}" enable="1"/>"""
        val after = files.lastOrNull { GowinEdaFileKind.ofType(GowinEdaProject.attributes(it.groupValues[1])["type"]).ordinal <= kind.ordinal }
        if (after != null) return insertLine(text, lineEnd(text, after.range.last + 1), FILE_INDENT + element)
        files.firstOrNull()?.let { first -> return insertLine(text, lineStart(text, first.range.first) - 1, FILE_INDENT + element) }
        // No file listed yet: the list is `<FileList/>`, an empty pair of tags, or missing.
        EMPTY_LIST.find(text)?.let { match ->
            val indent = match.groupValues[1]
            return text.replaceRange(match.range, "$indent<FileList>\n$FILE_INDENT$element\n$indent</FileList>")
        }
        OPEN_LIST.find(text)?.let { match -> return insertLine(text, lineEnd(text, match.range.last + 1), FILE_INDENT + element) }
        val project = text.lastIndexOf("</Project>").takeIf { it >= 0 } ?: return text
        return insertLine(text, lineStart(text, project) - 1, "    <FileList>\n$FILE_INDENT$element\n    </FileList>")
    }

    /** Takes the files whose listed path [matches] out of the list. */
    fun removeFiles(text: String, matches: (String) -> Boolean): String {
        var result = text
        for (match in GowinEdaProject.FILE.findAll(text).toList().asReversed()) {
            val path = GowinEdaProject.attributes(match.groupValues[1])["path"] ?: continue
            if (matches(path)) result = removeLine(result, match.range)
        }
        // GOWIN EDA writes an empty list as `<FileList/>`.
        return OPEN_EMPTY_LIST.replace(result) { "${it.groupValues[1]}<FileList/>" }
    }

    /** Sets whether the files whose listed path [matches] take part in the flow. */
    fun setEnabled(text: String, enabled: Boolean, matches: (String) -> Boolean): String {
        var result = text
        for (match in GowinEdaProject.FILE.findAll(text).toList().asReversed()) {
            val path = GowinEdaProject.attributes(match.groupValues[1])["path"] ?: continue
            if (!matches(path)) continue
            val element = match.value
            val value = if (enabled) "1" else "0"
            val changed = if (ENABLE.containsMatchIn(element)) ENABLE.replace(element, """enable="$value"""")
            else element.replace(Regex("""\s*/?>$""")) { " enable=\"$value\"" + it.value.trim().let { end -> if (end.startsWith("/")) "/>" else ">" } }
            result = result.replaceRange(match.range, changed)
        }
        return result
    }

    /** Sets the project's device, replacing the one it names. */
    fun setDevice(text: String, device: GowinEdaDevice): String {
        val element = deviceElement(device)
        GowinEdaProject.DEVICE.find(text)?.let { return text.replaceRange(it.range, element) }
        val version = Regex("""</Version>""").find(text)
            ?: Regex("""<Project>""").find(text)
            ?: return text
        return insertLine(text, lineEnd(text, version.range.last + 1), "    $element")
    }

    /** The text of a new project for [device] that lists [files], each as [path] names it. */
    fun projectFile(device: GowinEdaDevice, files: List<Pair<GowinEdaFileKind, String>>): String = buildString {
        append("<?xml version=\"1\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE gowin-fpga-project>\n")
        append("<Project>\n")
        append("    <Template>FPGA</Template>\n")
        append("    <Version>5</Version>\n")
        append("    ").append(deviceElement(device)).append('\n')
        if (files.isEmpty()) {
            append("    <FileList/>\n")
        } else {
            append("    <FileList>\n")
            for ((kind, path) in files.sortedBy { it.first.ordinal }) {
                append(FILE_INDENT).append("""<File path="${escape(path)}" type="${kind.type}" enable="1"/>""").append('\n')
            }
            append("    </FileList>\n")
        }
        append("</Project>\n")
    }

    private fun deviceElement(device: GowinEdaDevice): String =
        """<Device name="${escape(device.name)}" pn="${escape(device.partNumber)}">${escape(device.id)}</Device>"""

    /** [file] as a `.gprj` in [directory] names it: relative with forward slashes when it lies below, else absolute. */
    fun path(file: Path, directory: Path): String {
        val absolute = file.toAbsolutePath().normalize()
        val base = directory.toAbsolutePath().normalize()
        return (if (absolute.startsWith(base)) base.relativize(absolute) else absolute).toString().replace('\\', '/')
    }

    /** Whether [listed], as a `.gprj` in [directory] names a file, is [file]. */
    fun sameFile(listed: String, file: Path, directory: Path): Boolean =
        runCatching { directory.resolve(listed.replace('\\', '/')).normalize() == file.toAbsolutePath().normalize() }
            .getOrDefault(false)

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun lineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1
    private fun lineEnd(text: String, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    /** [line] on a line of its own after the line break at [at], or at the end. */
    private fun insertLine(text: String, at: Int, line: String): String =
        if (at < 0) "$line\n$text" else text.substring(0, at) + "\n" + line + text.substring(at)

    /** Removes [range] and the line it leaves empty. */
    private fun removeLine(text: String, range: IntRange): String {
        val start = lineStart(text, range.first)
        val end = lineEnd(text, range.last + 1)
        return if (text.substring(start, range.first).isBlank() && text.substring(range.last + 1, end).isBlank()) {
            text.removeRange(start, minOf(end + 1, text.length))
        } else {
            text.removeRange(range)
        }
    }

    private val EMPTY_LIST = Regex("""([ \t]*)<FileList\s*/>""")
    private val OPEN_LIST = Regex("""<FileList\s*>""")
    private val OPEN_EMPTY_LIST = Regex("""([ \t]*)<FileList\s*>\s*</FileList>""")
    private val ENABLE = Regex("""enable\s*=\s*"[^"]*"""")
}

/**
 * Changes to the text of `impl/project_process_config.json` that keep the rest of it as GOWIN EDA wrote it:
 * its order, its `"key" : value` spacing and the options the IDE knows nothing of.
 */
object GowinEdaOptionsText {
    /** What `gw_sh` needs to accept a file that holds only some of the options; without it the file is ignored. */
    private const val VERSION_KEY = "Process_Configuration_Verion"

    /** Sets the string option [key] to [value] in [text], the file's content or null for a file that does not exist yet. */
    fun setString(text: String?, key: String, value: String): String {
        val quoted = quote(value)
        if (text.isNullOrBlank()) return "{\n \"$VERSION_KEY\" : \"1.0\",\n \"$key\" : $quoted\n}\n"
        val existing = Regex("""(?m)^(\s*)"${Regex.escape(key)}"(\s*:\s*)("(?:[^"\\]|\\.)*"|[^,\r\n}]*)""").find(text)
        if (existing != null) return text.replaceRange(existing.groups[3]!!.range, quoted)
        val members = MEMBER.findAll(text).toList()
        val indent = members.firstOrNull()?.groupValues?.get(1) ?: " "
        val separator = members.firstOrNull()?.groupValues?.get(3) ?: " : "
        val line = "$indent\"$key\"$separator$quoted"
        // GOWIN EDA keeps the options in the order of their names.
        val next = members.firstOrNull { it.groupValues[2] > key }
        if (next != null) return text.substring(0, next.range.first) + "$line,\n" + text.substring(next.range.first)
        val close = text.lastIndexOf('}').takeIf { it >= 0 } ?: return text
        val last = text.substring(0, close).indexOfLast { !it.isWhitespace() }
        val comma = if (last >= 0 && text[last] != '{') "," else ""
        return text.substring(0, last + 1) + comma + "\n" + line + "\n" + text.substring(close)
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** An option on a line of its own: its indentation, its name and the separator before its value. */
    private val MEMBER = Regex("""(?m)^([ \t]*)"([^"]+)"(\s*:\s*)""")
}
