package com.jotrorox.verilogtoolbox.quartus

import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** The kinds of files a project lists, by the assignment that lists them. */
enum class QuartusFileKind(val assignment: String, val extensions: Set<String>) {
    SYSTEMVERILOG("SYSTEMVERILOG_FILE", setOf("sv", "svh")),
    VERILOG("VERILOG_FILE", setOf("v", "vh", "vo")),
    VHDL("VHDL_FILE", setOf("vhd", "vhdl")),
    SDC("SDC_FILE", setOf("sdc")),
    QIP("QIP_FILE", setOf("qip")),
    QSYS("QSYS_FILE", setOf("qsys")),
    IP("IP_FILE", setOf("ip")),
    BLOCK_DESIGN("BDF_FILE", setOf("bdf")),
    MEMORY_INITIALIZATION("MIF_FILE", setOf("mif")),
    HEX("HEX_FILE", setOf("hex")),
    TCL_SCRIPT("TCL_SCRIPT_FILE", setOf("tcl")),
    SIGNAL_TAP("SIGNALTAP_FILE", setOf("stp"));

    companion object {
        private val BY_ASSIGNMENT = entries.associateBy { it.assignment }

        fun ofAssignment(name: String?): QuartusFileKind? = BY_ASSIGNMENT[name]

        /** The assignment a file is added to a project with, by its extension; null for files a project does not list. */
        fun ofFile(file: Path): QuartusFileKind? = file.extension.lowercase().let { extension ->
            entries.firstOrNull { extension in it.extensions }
        }
    }
}

/** A file of a revision. [library] is the one a `.qip` compiles it into, if any. */
data class QuartusFile(val path: Path, val kind: QuartusFileKind, val library: String? = null)

/** What the settings say about a top-level port or one of its bits. */
data class QuartusPin(val node: String, val location: String?, val ioStandard: String?)

/**
 * A Quartus project: a `.qpf` with the revisions it names, each of which has its settings in
 * `<revision>.qsf` next to it. A `.qsf` without a `.qpf` is a project of its one revision, which is how
 * the command-line tools treat it too.
 */
class QuartusProject private constructor(
    /** The `.qpf`, or the `.qsf` of a project that has none. */
    val file: Path,
    val revisions: List<String>,
) {
    val directory: Path get() = file.parent

    /** What the tools are given as the project: the file's name without its extension. */
    val name: String get() = file.nameWithoutExtension

    val defaultRevision: String get() = revisions.firstOrNull() ?: name

    fun settingsFile(revision: String = defaultRevision): Path = directory.resolve("$revision.qsf")

    /** The settings of [revision] as they are on disk, or as [text] when that is given, for unsaved changes. */
    fun revision(revision: String = defaultRevision, text: String? = null): QuartusRevision {
        val file = settingsFile(revision)
        val variables = mapOf("quartus(project)" to name, "quartus(settings)" to revision)
        val commands = if (text != null) QuartusScript.read(text, file, directory, variables)
        else QuartusScript.read(file, directory, variables)
        return QuartusRevision(this, revision, commands)
    }

    override fun equals(other: Any?): Boolean = other is QuartusProject && other.file == file && other.revisions == revisions
    override fun hashCode(): Int = file.hashCode()
    override fun toString(): String = file.toString()

    companion object {
        private val REVISION = Regex("""^\s*PROJECT_REVISION\s*=\s*"?([^"\r\n]+?)"?\s*$""", RegexOption.MULTILINE)

        /** The revisions a `.qpf` with this [text] names, in its order. */
        internal fun revisions(text: String): List<String> = REVISION.findAll(text).map { it.groupValues[1] }.toList()

        /**
         * The project [file] belongs to: itself for a `.qpf`; for a `.qsf` the `.qpf` next to it that
         * names it as a revision, or else the `.qsf` alone. Null for other files and ones that do not exist.
         */
        fun of(file: Path): QuartusProject? {
            val absolute = file.toAbsolutePath().normalize()
            if (!absolute.isRegularFile()) return null
            return when (absolute.extension.lowercase()) {
                "qpf" -> {
                    val named = runCatching { revisions(Files.readString(absolute)) }.getOrDefault(emptyList())
                    QuartusProject(absolute, named.ifEmpty { listOf(absolute.nameWithoutExtension) })
                }
                "qsf" -> {
                    val revision = absolute.nameWithoutExtension
                    val projects = runCatching { absolute.parent.listDirectoryEntries("*.qpf") }.getOrDefault(emptyList())
                        .sorted().mapNotNull { of(it) }
                    projects.firstOrNull { revision in it.revisions } ?: QuartusProject(absolute, listOf(revision))
                }
                else -> null
            }
        }

        /** Whether [file] is one a project is opened from. */
        fun isProjectFile(file: Path): Boolean = file.extension.lowercase().let { it == "qpf" || it == "qsf" }
    }
}

/** One revision of a project: what its settings file assigns. */
class QuartusRevision(val project: QuartusProject, val name: String, commands: List<QuartusCommand>) {
    val settingsFile: Path get() = project.settingsFile(name)

    /** The assignments in force, in the order they were made. */
    val assignments: List<QuartusCommand> = commands.filter { it.command in ASSIGNMENTS && !it.isInactive }

    /** The value of the last global assignment called [name] that belongs to no section or entity. */
    fun global(name: String): String? =
        assignments.lastOrNull { it.isPlainGlobal(name) }?.value

    private fun globals(name: String): List<String> =
        assignments.filter { it.command == QuartusCommand.GLOBAL && it.name == name }.mapNotNull { it.value }

    val family: String? get() = global("FAMILY")
    val device: String? get() = global("DEVICE")

    /** The top-level entity, which is called like the revision unless the settings name another. */
    val topLevelEntity: String get() = global("TOP_LEVEL_ENTITY")?.takeIf { it.isNotBlank() } ?: name

    /** Where reports and programming files are written; the project directory unless the settings name one. */
    val outputDirectory: Path
        get() = global("PROJECT_OUTPUT_DIRECTORY")?.takeIf { it.isNotBlank() }?.let(::resolve) ?: project.directory

    /** A file the tools write for this revision, such as `fit.rpt` or `sof`. */
    fun output(suffix: String): Path = outputDirectory.resolve("$name.$suffix")

    /** The files the settings list, without those of the `.qip` files among them. */
    val listedFiles: List<QuartusFile> by lazy { files(assignments) }

    /** Every file of the revision: the listed ones and, in the place of each `.qip`, the files that names. */
    val files: List<QuartusFile> by lazy {
        listedFiles.flatMap { file -> if (file.kind == QuartusFileKind.QIP) listOf(file) + ipFiles(file.path, mutableSetOf()) else listOf(file) }
            .distinctBy { it.path }
    }

    private fun files(assignments: List<QuartusCommand>, directory: Path = project.directory): List<QuartusFile> =
        assignments.filter { it.command == QuartusCommand.GLOBAL }.mapNotNull { assignment ->
            val kind = QuartusFileKind.ofAssignment(assignment.name) ?: return@mapNotNull null
            val path = assignment.value?.takeIf { it.isNotBlank() }?.let { resolve(it, directory) } ?: return@mapNotNull null
            QuartusFile(path, kind, assignment.option("-library"))
        }

    /** The files a `.qip` lists, relative to itself, and those of the `.qip` files it lists in turn. */
    private fun ipFiles(qip: Path, seen: MutableSet<Path>): List<QuartusFile> {
        if (!seen.add(qip)) return emptyList()
        val directory = qip.parent ?: return emptyList()
        val variables = mapOf("quartus(qip_path)" to directory.toString().replace('\\', '/'))
        val commands = QuartusScript.read(qip, directory, variables).filter { !it.isInactive }
        return files(commands, directory).flatMap { file ->
            if (file.kind == QuartusFileKind.QIP) listOf(file) + ipFiles(file.path, seen) else listOf(file)
        }
    }

    /** The Verilog and SystemVerilog sources, headers left out: those are only included. */
    val verilogSources: List<Path>
        get() = files.filter { it.kind == QuartusFileKind.VERILOG || it.kind == QuartusFileKind.SYSTEMVERILOG }
            .map { it.path }.filter { it.extension.lowercase() !in HEADER_EXTENSIONS }

    /** The directories Quartus searches for included files and for entities no listed file defines. */
    val searchPaths: List<Path>
        get() = (globals("SEARCH_PATH") + globals("USER_LIBRARIES").flatMap { it.split(';') })
            .filter { it.isNotBlank() }.map(::resolve).distinct()

    /** `NAME` or `NAME=value`, as `VERILOG_MACRO` assignments give them. */
    val macros: List<String> get() = globals("VERILOG_MACRO").filter { it.isNotBlank() }

    /** The pins the settings assign: a location, an I/O standard, or both, by the node they are assigned to. */
    val pins: List<QuartusPin>
        get() {
            val locations = LinkedHashMap<String, String>()
            val standards = LinkedHashMap<String, String>()
            for (assignment in assignments) {
                val node = assignment.to ?: continue
                val value = assignment.value ?: continue
                if (assignment.command == QuartusCommand.LOCATION && value.startsWith(PIN_PREFIX, ignoreCase = true)) {
                    locations[node] = value
                } else if (assignment.command == QuartusCommand.INSTANCE && assignment.name == "IO_STANDARD") {
                    standards[node] = value
                }
            }
            return (locations.keys + standards.keys).map { QuartusPin(it, locations[it], standards[it]) }
        }

    /**
     * The design as the other tools are given it: the Verilog sources, the directories Quartus would
     * search, the macros and the top-level entity. [libraries] are simulation models of what the design
     * instantiates without defining, such as the megafunctions.
     */
    fun fileList(libraries: List<Path> = emptyList()): FileList {
        val sources = verilogSources
        val headers = files.map { it.path }.filter { it.extension.lowercase() in HEADER_EXTENSIONS }.mapNotNull { it.parent }
        // A Path is itself a list of its names, so it is wrapped to be added as one.
        val searched = (searchPaths + listOf(project.directory)).distinct()
        return FileList(
            sources = sources,
            includeDirectories = (searched + headers).distinct(),
            defines = macros,
            libraryDirectories = searched,
            libraryFiles = libraries.filter { it !in sources },
            libraryExtensions = VerilogDesign.LIBRARY_EXTENSIONS,
            topModules = listOf(topLevelEntity),
            ignored = emptyList(),
            errors = emptyList(),
        )
    }

    private fun resolve(path: String, directory: Path = project.directory): Path =
        runCatching { directory.resolve(path.replace('\\', '/')).normalize() }.getOrElse { directory.resolve("invalid") }

    private fun QuartusCommand.isPlainGlobal(name: String): Boolean =
        command == QuartusCommand.GLOBAL && this.name == name && option("-section_id") == null && option("-entity") == null

    companion object {
        const val PIN_PREFIX = "PIN_"
        private val ASSIGNMENTS =
            setOf(QuartusCommand.GLOBAL, QuartusCommand.INSTANCE, QuartusCommand.LOCATION, QuartusCommand.PARAMETER)
        private val HEADER_EXTENSIONS = setOf("vh", "svh")
    }
}

/**
 * Changes to the text of a settings file, each of which touches only the lines of the assignment it is
 * about, so that comments and the order of everything else stay as the user or Quartus wrote them.
 */
object QuartusSettingsText {
    /**
     * Sets the global assignment [name] to [value], or removes it for null. The last plain assignment of
     * that name is replaced; a new one goes after the last global assignment.
     */
    fun setGlobal(text: String, name: String, value: String?): String {
        val commands = QuartusScript.commands(text)
        val existing = commands.filter {
            it.command == QuartusCommand.GLOBAL && it.name == name.uppercase() &&
                it.option("-section_id") == null && it.option("-entity") == null
        }
        if (value == null) return remove(text, existing)
        val line = "${QuartusCommand.GLOBAL} -name $name ${QuartusScript.quote(value)}"
        val last = existing.lastOrNull() ?: return insert(text, line, commands.lastOrNull { it.command == QuartusCommand.GLOBAL })
        return remove(text.replaceRange(last.start, last.end, line), existing.dropLast(1))
    }

    /**
     * Adds a global assignment of which a file may have several, such as a source file, after the last
     * one with the same name. Nothing changes when [same] already holds for the value of one.
     */
    fun addGlobal(text: String, name: String, value: String, same: (String) -> Boolean = { it == value }): String {
        val commands = QuartusScript.commands(text)
        val named = commands.filter { it.command == QuartusCommand.GLOBAL && it.name == name.uppercase() }
        if (named.any { !it.isInactive && it.value?.let(same) == true }) return text
        val line = "${QuartusCommand.GLOBAL} -name $name ${QuartusScript.quote(value)}"
        val after = named.lastOrNull()
            // A first source file goes next to the other files, which keeps the list together.
            ?: commands.lastOrNull { it.command == QuartusCommand.GLOBAL && QuartusFileKind.ofAssignment(it.name) != null }
                ?.takeIf { QuartusFileKind.ofAssignment(name.uppercase()) != null }
            ?: commands.lastOrNull { it.command == QuartusCommand.GLOBAL }
        return insert(text, line, after)
    }

    /** Removes the global assignments called [name] whose value [matches]. */
    fun removeGlobal(text: String, name: String, matches: (String) -> Boolean): String =
        remove(text, QuartusScript.commands(text).filter {
            it.command == QuartusCommand.GLOBAL && it.name == name.uppercase() && it.value?.let(matches) == true
        })

    /** Assigns [node] the pin [location], such as `PIN_P11`, or takes its pin away for null. */
    fun setLocation(text: String, node: String, location: String?): String {
        val commands = QuartusScript.commands(text)
        val existing = commands.filter {
            it.command == QuartusCommand.LOCATION && it.to == node &&
                it.value?.startsWith(QuartusRevision.PIN_PREFIX, ignoreCase = true) == true
        }
        if (location == null) return remove(text, existing)
        val line = "${QuartusCommand.LOCATION} $location -to ${QuartusScript.quote(node)}"
        val last = existing.lastOrNull()
            ?: return insert(text, line, commands.lastOrNull { it.command == QuartusCommand.LOCATION })
        return remove(text.replaceRange(last.start, last.end, line), existing.dropLast(1))
    }

    /** Sets the instance assignment [name] of [node], such as its `IO_STANDARD`, or removes it for null. */
    fun setInstance(text: String, name: String, node: String, value: String?): String {
        val commands = QuartusScript.commands(text)
        val existing = commands.filter {
            it.command == QuartusCommand.INSTANCE && it.name == name.uppercase() && it.to == node && it.option("-from") == null
        }
        if (value == null) return remove(text, existing)
        val line = "${QuartusCommand.INSTANCE} -name $name ${QuartusScript.quote(value)} -to ${QuartusScript.quote(node)}"
        val last = existing.lastOrNull() ?: return insert(
            text, line,
            commands.lastOrNull { it.command == QuartusCommand.INSTANCE && it.name == name.uppercase() }
                ?: commands.lastOrNull { it.command == QuartusCommand.INSTANCE }
                ?: commands.lastOrNull { it.command == QuartusCommand.LOCATION },
        )
        return remove(text.replaceRange(last.start, last.end, line), existing.dropLast(1))
    }

    /** [line] on a line of its own below [after], or at the end of the text. */
    private fun insert(text: String, line: String, after: QuartusCommand?): String {
        if (after == null) {
            val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
            return text + separator + line + "\n"
        }
        val lineEnd = text.indexOf('\n', after.end).let { if (it < 0) text.length else it }
        return text.substring(0, lineEnd) + "\n" + line + text.substring(lineEnd)
    }

    /** Removes [commands] and the lines they leave empty. */
    private fun remove(text: String, commands: List<QuartusCommand>): String {
        var result = text
        for (command in commands.sortedByDescending { it.start }) {
            var start = command.start
            var end = command.end
            val lineStart = result.lastIndexOf('\n', start - 1) + 1
            val lineEnd = result.indexOf('\n', end).let { if (it < 0) result.length else it }
            // Only a line the command has to itself goes with it.
            if (result.substring(lineStart, start).isBlank() && result.substring(end, lineEnd).isBlank()) {
                start = lineStart
                end = minOf(lineEnd + 1, result.length)
            }
            result = result.removeRange(start, end)
        }
        return result
    }

    /** [file] as a settings file in [directory] names it: relative with forward slashes when it lies below, else absolute. */
    fun path(file: Path, directory: Path): String {
        val absolute = file.toAbsolutePath().normalize()
        val base = directory.toAbsolutePath().normalize()
        return (if (absolute.startsWith(base)) base.relativize(absolute) else absolute).toString().replace('\\', '/')
    }

    /** The first lines of a new project file. */
    fun projectFile(revision: String): String =
        "# Quartus Prime project, created by Verilog Toolbox.\n\nPROJECT_REVISION = \"$revision\"\n"

    /** The settings of a new revision. [files] are named as [path] gives them. */
    fun settingsFile(family: String, device: String, topLevelEntity: String, files: List<Pair<QuartusFileKind, String>>): String =
        buildString {
            append("${QuartusCommand.GLOBAL} -name FAMILY ${QuartusScript.quote(family)}\n")
            append("${QuartusCommand.GLOBAL} -name DEVICE ${QuartusScript.quote(device)}\n")
            append("${QuartusCommand.GLOBAL} -name TOP_LEVEL_ENTITY ${QuartusScript.quote(topLevelEntity)}\n")
            append("${QuartusCommand.GLOBAL} -name PROJECT_OUTPUT_DIRECTORY output_files\n")
            for ((kind, file) in files) append("${QuartusCommand.GLOBAL} -name ${kind.assignment} ${QuartusScript.quote(file)}\n")
        }

    /** Whether [listed], as a settings file in [directory] names a file, is [file]. */
    fun sameFile(listed: String, file: Path, directory: Path): Boolean =
        runCatching { directory.resolve(listed.replace('\\', '/')).normalize() == file.toAbsolutePath().normalize() }
            .getOrDefault(false)

    /** A pin as a location assignment names it: `p11` and `PIN_P11` both give `PIN_P11`; null for blank text. */
    fun pinLocation(text: String): String? {
        val trimmed = text.trim().uppercase()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith(QuartusRevision.PIN_PREFIX)) trimmed else QuartusRevision.PIN_PREFIX + trimmed
    }
}
