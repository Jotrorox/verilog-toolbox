package com.jotrorox.verilogtoolbox.common

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import com.intellij.util.system.CpuArch
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name

/**
 * Installs the [OSS CAD Suite](https://github.com/YosysHQ/oss-cad-suite-build), the nightly bundle of the
 * open source hardware tools. It is the only prebuilt Icarus Verilog for every platform, and the same
 * download carries Verilator, Yosys (with the yosys-slang front end) and GTKWave, so all of it is kept:
 * the plugin uses those tools from here when they are not set in the settings.
 */
object OssCadSuite {
    private const val REPOSITORY = "YosysHQ/oss-cad-suite-build"
    private const val ARCHIVE_ROOT = "oss-cad-suite"
    private const val STAGING_SUFFIX = ".partial"

    /** What the settings and notifications say the download costs. */
    const val DOWNLOAD_SIZE = "500–750 MB, 2–2.5 GB unpacked"

    private val downloading = AtomicBoolean()

    val isDownloading: Boolean get() = downloading.get()

    private val pluginRoot: Path get() = PathManager.getSystemDir().resolve("verilog-toolbox")

    /** Suite builds are unpacked into `<IDE system dir>/verilog-toolbox/oss-cad-suite/<tag>`. */
    private val installRoot: Path get() = pluginRoot.resolve("oss-cad-suite")

    /** Where versions before 0.3 unpacked the Icarus part of the suite. */
    private val legacyIcarusRoot: Path get() = pluginRoot.resolve("icarus")

    /** The part of the suite's archive name that identifies this OS and CPU. */
    private fun platform(): String = when {
        SystemInfo.isMac && CpuArch.isArm64() -> "darwin-arm64"
        SystemInfo.isMac && CpuArch.isIntel64() -> "darwin-x64"
        SystemInfo.isLinux && CpuArch.isArm64() -> "linux-arm64"
        SystemInfo.isLinux && CpuArch.isIntel64() -> "linux-x64"
        SystemInfo.isWindows && CpuArch.isIntel64() -> "windows-x64"
        else -> throw IOException(
            "The OSS CAD Suite has no build for ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}. " +
                "Install the tools yourself and set their paths in the settings."
        )
    }

    /** The root of the newest suite this plugin has unpacked, if any. */
    fun installed(): Path? = installed(installRoot)

    /** Builds are tagged by date, such as `2026-11-18`, so the newest tag sorts last. */
    internal fun installed(root: Path): Path? {
        if (!Files.isDirectory(root)) return null
        return try {
            Files.list(root).use { versions ->
                versions.filter { !it.name.endsWith(STAGING_SUFFIX) && Files.isDirectory(it.resolve("bin")) }
                    .max(Comparator.comparing { it.name })
                    .orElse(null)
            }
        } catch (_: IOException) {
            null
        }
    }

    /** The build date of the installed suite, such as `2026-11-18`. */
    fun installedVersion(): String? = installed()?.name

    /**
     * The suite's launcher for [binary], given without `.exe`. On Linux and macOS the launchers in `bin`
     * are scripts that set up the environment and start the real program in `libexec`.
     */
    fun tool(binary: String): Path? = installed()?.let { tool(it, binary) }

    internal fun tool(root: Path, binary: String): Path? {
        val name = if (SystemInfo.isWindows) "$binary.exe" else binary
        return root.resolve("bin").resolve(name).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    /** Whether [path] lies in a suite this plugin unpacked, or in a download of an earlier version. */
    fun isManaged(path: String): Boolean =
        path.isNotEmpty() && runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()
            ?.let { it.startsWith(installRoot) || it.startsWith(legacyIcarusRoot) } == true

    /**
     * Downloads and unpacks the latest build, returning its root. Does nothing when that build is
     * already installed. Blocking; call from a background thread.
     */
    @Throws(IOException::class)
    fun downloadLatest(indicator: ProgressIndicator): Path {
        if (!downloading.compareAndSet(false, true)) throw IOException("The OSS CAD Suite is already being downloaded.")
        try {
            indicator.isIndeterminate = true
            indicator.text = "Looking up the latest OSS CAD Suite build…"
            val release = GitHubReleases.latest(REPOSITORY, indicator)
            val platform = platform()
            val asset = release.assets.firstOrNull { "-$platform-" in it.name && it.name.endsWith(".tgz") }
                ?: throw IOException("OSS CAD Suite build ${release.tag} has no download for $platform.")

            val target = installRoot.resolve(release.tag)
            if (installed(installRoot) != target) {
                val archive = Files.createTempFile("oss-cad-suite", ".tgz")
                try {
                    indicator.text = "Downloading the OSS CAD Suite ${release.tag} (${asset.size / 1_000_000} MB)…"
                    asset.download(archive, indicator)
                    install(archive, target, indicator)
                } finally {
                    Files.deleteIfExists(archive)
                }
            }
            removeOtherVersions(target)
            return target
        } finally {
            downloading.set(false)
        }
    }

    /**
     * Unpacks the suite [archive] into [target]. The files only appear there once Icarus Verilog has been
     * seen to start, so an interrupted or unusable download is never picked up as an installation.
     */
    @Throws(IOException::class)
    internal fun install(archive: Path, target: Path, indicator: ProgressIndicator?) {
        val staging = target.resolveSibling(target.name + STAGING_SUFFIX)
        NioFiles.deleteRecursively(staging)
        Files.createDirectories(staging)
        try {
            indicator?.isIndeterminate = true
            indicator?.text = "Unpacking the OSS CAD Suite…"
            Decompressor.Tar(archive)
                .filter { entry ->
                    indicator?.checkCanceled()
                    indicator?.text2 = entry
                    true
                }
                .removePrefixPath(ARCHIVE_ROOT)
                .extract(staging)
            indicator?.text2 = null
            val iverilog = tool(staging, "iverilog")
                ?: throw IOException("The OSS CAD Suite download does not contain ${ToolchainSettings.IVERILOG_BINARY}.")
            verify(iverilog)
            NioFiles.deleteRecursively(target)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            NioFiles.deleteRecursively(staging)
        }
    }

    /** Makes sure the unpacked compiler starts, so a broken install is reported now and not on the first run. */
    @Throws(IOException::class)
    private fun verify(iverilog: Path) {
        val output = try {
            CapturingProcessHandler(ToolchainSettings.toolCommandLine(iverilog).withParameters("-V")).runProcess(30_000)
        } catch (e: ExecutionException) {
            throw IOException("The downloaded OSS CAD Suite does not start: ${e.message}", e)
        }
        if (output.isTimeout || output.exitCode != 0) {
            val reason = output.stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit code ${output.exitCode}"
            throw IOException("The downloaded OSS CAD Suite does not start: $reason")
        }
    }

    /**
     * A build takes 2–2.5 GB, so older ones go once a newer one works, as does the Icarus-only copy that
     * earlier versions downloaded, unless a path in the settings still points into it. A copy that is
     * still running may stay behind.
     */
    private fun removeOtherVersions(current: Path) {
        val settings = ToolchainSettings.getInstance()
        if (settings.iverilogPath.isNotEmpty() && isManaged(settings.iverilogPath) &&
            !Path.of(settings.iverilogPath).toAbsolutePath().normalize().startsWith(current)
        ) {
            // The suite provides Icarus now; an empty path stands for its copy.
            settings.iverilogPath = ""
        }
        val stale = buildList {
            try {
                Files.list(current.parent).use { versions -> versions.filter { it != current }.forEach(::add) }
            } catch (_: IOException) {
            }
            if (Files.exists(legacyIcarusRoot)) add(legacyIcarusRoot)
        }
        stale.forEach { runCatching { NioFiles.deleteRecursively(it) } }
    }

    /** Downloads in the background and reports the result in a balloon. */
    fun downloadInBackground(project: Project?) {
        object : Task.Backgroundable(project, "Downloading the OSS CAD Suite", true) {
            override fun run(indicator: ProgressIndicator) {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                try {
                    val root = downloadLatest(indicator)
                    group.createNotification(
                        "OSS CAD Suite ${root.name} installed",
                        "Icarus Verilog, Verilator, Yosys, GTKWave and openFPGALoader are ready to use.",
                        NotificationType.INFORMATION,
                    ).notify(project)
                } catch (e: IOException) {
                    group.createNotification(
                        "OSS CAD Suite download failed", e.message ?: e.toString(), NotificationType.ERROR
                    ).notify(project)
                }
            }
        }.queue()
    }

    /** Balloon shown when [tool] is needed but not installed, offering to download the suite. */
    fun notifyMissing(project: Project, tool: String, purpose: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                "$tool was not found",
                "$purpose The OSS CAD Suite has it, together with Icarus Verilog, Verilator, Yosys and GTKWave " +
                    "($DOWNLOAD_SIZE).",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Download OSS CAD Suite") { downloadInBackground(project) })
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            })
            .notify(project)
    }
}
