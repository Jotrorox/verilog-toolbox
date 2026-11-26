package com.jotrorox.verilogtoolbox.gowin.eda

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

object GowinEdaIcons {
    @JvmField
    val Gowin: Icon = IconLoader.getIcon("/icons/gowinEda.svg", GowinEdaIcons::class.java)

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/gowinEdaToolWindow.svg", GowinEdaIcons::class.java)
}

/**
 * A GOWIN EDA installation, known by its `IDE` directory, which holds the tools in `bin` (`gw_sh`, the Tcl
 * shell that runs the flow, and `gw_ide`, the IDE) and the device data in `data`. The programmer is beside
 * it, in `Programmer/bin`.
 */
class GowinEdaInstallation(val ideDirectory: Path) {
    val binDirectory: Path get() = ideDirectory.resolve("bin")

    /** The directory of the version, such as `C:\Gowin\Gowin_V1.9.12.04_x64`, which holds `IDE` and `Programmer`. */
    val rootDirectory: Path get() = ideDirectory.parent ?: ideDirectory

    /** The device catalog and the package and I/O tables, `IDE/data`. */
    val dataDirectory: Path get() = ideDirectory.resolve("data")

    /** Such as `1.9.12.04`, from the name of the version directory; null when it does not say. */
    val version: String? = VERSION.find(rootDirectory.name)?.groupValues?.get(1)

    /** Such as `GOWIN EDA 1.9.12.04`. */
    val displayName: String get() = listOfNotNull("GOWIN EDA", version).joinToString(" ")

    /** The executable called [name] in `IDE/bin`, given without `.exe`; null when the installation has none. */
    fun tool(name: String): Path? = binDirectory.resolve(GowinEda.binary(name)).takeIf { Files.isRegularFile(it) }

    /** `programmer_cli`, which programs the device over a cable; null when the programmer is not installed. */
    val programmer: Path?
        get() = rootDirectory.resolve("Programmer").resolve("bin").resolve(GowinEda.binary(GowinEda.PROGRAMMER))
            .takeIf { Files.isRegularFile(it) }

    /** A command line that runs [tool] in [directory]. */
    fun commandLine(tool: Path, directory: Path): GeneralCommandLine =
        GeneralCommandLine(tool.toString()).withWorkingDirectory(directory).withCharset(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is GowinEdaInstallation && other.ideDirectory == ideDirectory
    override fun hashCode(): Int = ideDirectory.hashCode()
    override fun toString(): String = ideDirectory.toString()

    private companion object {
        val VERSION = Regex("""(?i)V(\d+(?:\.\d+)+)""")
    }
}

/** Finds GOWIN EDA: where the settings say, on `PATH`, or where it installs by default. */
object GowinEda {
    const val SHELL = "gw_sh"
    const val IDE = "gw_ide"
    const val PROGRAMMER = "programmer_cli"

    @Volatile
    private var cached: Cached? = null

    private class Cached(val key: String, val installation: GowinEdaInstallation?, val time: Long)

    fun binary(name: String): String = if (SystemInfo.isWindows) "$name.exe" else name

    /** The installation to use, or null when there is none. Looked up again when the setting changes. */
    fun find(): GowinEdaInstallation? {
        val configured = ToolchainSettings.getInstance().gowinEdaPath
        val now = System.currentTimeMillis()
        cached?.let { known ->
            // A missing installation is looked for again after a while: it may just have been installed.
            val fresh = if (known.installation == null) now - known.time < 10_000
            else Files.isDirectory(known.installation.binDirectory)
            if (known.key == configured && fresh) return known.installation
        }
        return find(configured).also { cached = Cached(configured, it, now) }
    }

    internal fun find(
        configured: String,
        environment: (String) -> String? = EnvironmentUtil::getValue,
        installRoots: List<Path> = defaultInstallRoots(),
    ): GowinEdaInstallation? {
        if (configured.isNotBlank()) {
            return runCatching { Path.of(configured.trim()) }.getOrNull()?.let(::ideDirectory)?.let(::GowinEdaInstallation)
        }
        val directory = findExecutableOnPath(binary(SHELL), environment("PATH"))?.let { ideDirectory(it) }
            ?: newestInstalled(installRoots)
        return directory?.let(::GowinEdaInstallation)
    }

    /**
     * The `IDE` directory that [path] stands for. It may be that directory, its `bin` directory, a tool in it,
     * or the version directory above it, such as `C:\Gowin\Gowin_V1.9.12.04_x64`.
     */
    internal fun ideDirectory(path: Path): Path? {
        val start = if (Files.isRegularFile(path)) path.parent ?: return null else path
        val candidates = listOfNotNull(start.parent?.takeIf { start.name == "bin" }, start, start.resolve("IDE"))
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("bin").resolve(binary(SHELL))) }
            ?.toAbsolutePath()?.normalize()
    }

    /**
     * The newest installation below [installRoots]: each root may be an installation itself, as the Linux
     * archive unpacks, or hold one directory per version, as the Windows installer makes them (`Gowin_V1.9.12.04_x64`).
     */
    internal fun newestInstalled(installRoots: List<Path>): Path? =
        installRoots.asSequence()
            .filter { it.isDirectory() }
            .flatMap { root -> sequenceOf(root) + runCatching { root.listDirectoryEntries() }.getOrDefault(emptyList()) }
            .mapNotNull { directory -> ideDirectory(directory)?.let { directory.name to it } }
            .maxWithOrNull(compareBy<Pair<String, Path>>({ versionKey(it.first) }, { it.second.toString() }))
            ?.second

    /** `Gowin_V1.9.12.04_x64` as its numbers, so that 1.9.12 is newer than 1.9.9; the `64` of `x64` does not count. */
    private fun versionKey(name: String): Long {
        val version = Regex("""(?i)V(\d+(?:\.\d+)*)""").find(name)?.groupValues?.get(1) ?: return 0
        val parts = version.split('.').map { it.toLongOrNull() ?: 0 }
        return (0 until 4).fold(0L) { key, index -> key * 1000 + parts.getOrElse(index) { 0 } }
    }

    private fun defaultInstallRoots(): List<Path> {
        val names = if (SystemInfo.isWindows) {
            val drive = System.getenv("SystemDrive")?.takeIf { it.isNotBlank() } ?: "C:"
            listOf("$drive\\Gowin", "$drive\\Gowin_EDA")
        } else {
            val home = System.getProperty("user.home")
            listOfNotNull(home?.let { "$it/gowin" }, home?.let { "$it/Gowin" }, "/opt/gowin", "/opt/Gowin", "/tools/gowin")
        }
        return names.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
    }
}
