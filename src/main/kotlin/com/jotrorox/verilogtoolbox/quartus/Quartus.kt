package com.jotrorox.verilogtoolbox.quartus

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.findExecutableOnPath
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

object QuartusIcons {
    @JvmField
    val Quartus: Icon = IconLoader.getIcon("/icons/quartus.svg", QuartusIcons::class.java)

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/quartusToolWindow.svg", QuartusIcons::class.java)
}

/**
 * A Quartus Prime installation, known by the directory that holds its command-line tools: `quartus/bin64`
 * on Windows, `quartus/bin` on Linux.
 */
class QuartusInstallation(val binDirectory: Path) {
    /** The `quartus` directory of the installation. */
    val quartusDirectory: Path? get() = binDirectory.parent

    /** Such as `25.1std.0`, from the installation's `version.txt`; null when that is missing. */
    val version: String?

    /** `Lite`, `Standard` or `Pro`, as far as the installation says. */
    val edition: String?

    init {
        val properties = quartusDirectory?.resolve("version.txt")
            ?.let { runCatching { Files.readAllLines(it) }.getOrNull() }.orEmpty()
        // The last Version line is the one of the [ACDS] section, which carries the "std" of a Standard release.
        version = properties.lastOrNull { it.startsWith("Version=") }?.substringAfter('=')?.trim()
            ?.split('.')?.take(3)?.joinToString(".")?.takeIf { it.isNotEmpty() }
        edition = properties.firstOrNull { it.startsWith("Edition=") }?.substringAfter('=')?.trim()
            ?.lowercase()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotEmpty() }
    }

    /**
     * Whether this is the Pro Edition, whose synthesis is `quartus_syn`; the other editions have
     * `quartus_map`.
     */
    val isPro: Boolean get() = edition == "Pro" || tool("quartus_map") == null && tool("quartus_syn") != null

    /** Such as `Quartus Prime 25.1std.0 Lite Edition`. */
    val displayName: String
        get() = listOfNotNull("Quartus Prime", version, edition?.let { "$it Edition" }).joinToString(" ")

    /** The executable called [name], given without `.exe`; null when the installation has none. */
    fun tool(name: String): Path? =
        binDirectory.resolve(Quartus.binary(name)).takeIf { Files.isRegularFile(it) }

    /** A command line that runs [tool] in [directory]. */
    fun commandLine(tool: Path, directory: Path): GeneralCommandLine =
        GeneralCommandLine(tool.toString()).withWorkingDirectory(directory).withCharset(Charsets.UTF_8)

    /** The Verilog simulation models of Altera's primitives and megafunctions, such as `altera_mf.v`. */
    val simulationLibraries: List<Path>
        get() {
            val directory = quartusDirectory?.resolve("eda")?.resolve("sim_lib") ?: return emptyList()
            return SIMULATION_LIBRARIES.map { directory.resolve(it) }.filter { Files.isRegularFile(it) }
        }

    override fun equals(other: Any?): Boolean = other is QuartusInstallation && other.binDirectory == binDirectory
    override fun hashCode(): Int = binDirectory.hashCode()
    override fun toString(): String = binDirectory.toString()

    private companion object {
        /** What a design instantiates by name: primitives, the LPM functions and the megafunctions. */
        val SIMULATION_LIBRARIES = listOf("altera_primitives.v", "220model.v", "sgate.v", "altera_mf.v", "altera_lnsim.sv")
    }
}

/** Finds Quartus Prime: where the settings say, where `QUARTUS_ROOTDIR` points, on `PATH`, or where it installs by default. */
object Quartus {
    const val SHELL = "quartus_sh"

    /** The names the installer has given its directory over the years, each holding one directory per version. */
    private val INSTALL_DIRECTORIES =
        listOf("altera_pro", "altera", "altera_lite", "intelFPGA_pro", "intelFPGA", "intelFPGA_lite")

    @Volatile
    private var cached: Cached? = null

    private class Cached(val key: List<String?>, val installation: QuartusInstallation?, val time: Long)

    fun binary(name: String): String = if (SystemInfo.isWindows) "$name.exe" else name

    /** The installation to use, or null when there is none. Looked up again when the settings or the environment change. */
    fun find(): QuartusInstallation? {
        val configured = ToolchainSettings.getInstance().quartusPath
        val key = listOf(configured, EnvironmentUtil.getValue("QUARTUS_ROOTDIR"))
        val now = System.currentTimeMillis()
        cached?.let { known ->
            // A missing installation is looked for again after a while: it may just have been installed.
            val fresh = if (known.installation == null) now - known.time < 10_000
            else Files.isDirectory(known.installation.binDirectory)
            if (known.key == key && fresh) return known.installation
        }
        return find(configured).also { cached = Cached(key, it, now) }
    }

    internal fun find(
        configured: String,
        environment: (String) -> String? = EnvironmentUtil::getValue,
        installRoots: List<Path> = defaultInstallRoots(),
    ): QuartusInstallation? {
        if (configured.isNotBlank()) {
            return runCatching { Path.of(configured.trim()) }.getOrNull()?.let(::binDirectory)?.let(::QuartusInstallation)
        }
        val fromEnvironment = environment("QUARTUS_ROOTDIR")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it) }.getOrNull() }?.let(::binDirectory)
        val directory = fromEnvironment
            ?: findExecutableOnPath(binary(SHELL), environment("PATH"))?.let { binDirectory(it) }
            ?: newestInstalled(installRoots)
        return directory?.let(::QuartusInstallation)
    }

    /**
     * The tool directory that [path] stands for. It may be that directory, a tool in it, the `quartus`
     * directory above it or the version directory above that, such as `C:\altera_lite\25.1std`.
     */
    internal fun binDirectory(path: Path): Path? {
        val start = if (Files.isRegularFile(path)) path.parent ?: return null else path
        return listOf(
            start, start.resolve("bin64"), start.resolve("bin"),
            start.resolve("quartus").resolve("bin64"), start.resolve("quartus").resolve("bin"),
        ).firstOrNull { Files.isRegularFile(it.resolve(binary(SHELL))) }?.toAbsolutePath()?.normalize()
    }

    /** The newest version below [installRoots], comparing version directories such as `23.1std` and `25.1`. */
    internal fun newestInstalled(installRoots: List<Path>): Path? =
        installRoots.asSequence()
            .filter { it.isDirectory() }
            .flatMap { root -> runCatching { root.listDirectoryEntries() }.getOrDefault(emptyList()) }
            .mapNotNull { version -> binDirectory(version)?.let { version.name to it } }
            .maxWithOrNull(compareBy<Pair<String, Path>>({ versionKey(it.first) }, { it.second.toString() }))
            ?.second

    /** `25.1std` as 25001: versions compare by their numbers, whatever follows them. */
    private fun versionKey(name: String): Long {
        val numbers = Regex("""\d+""").findAll(name).map { it.value.toLongOrNull() ?: 0 }.toList()
        return numbers.getOrElse(0) { 0 } * 1000 + numbers.getOrElse(1) { 0 }
    }

    private fun defaultInstallRoots(): List<Path> {
        val bases = if (SystemInfo.isWindows) {
            listOf(System.getenv("SystemDrive")?.takeIf { it.isNotBlank() } ?: "C:").map { "$it\\" }
        } else {
            listOfNotNull(System.getProperty("user.home"), "/opt", "/tools")
        }
        return bases.flatMap { base -> INSTALL_DIRECTORIES.mapNotNull { runCatching { Path.of(base, it) }.getOrNull() } }
    }
}
