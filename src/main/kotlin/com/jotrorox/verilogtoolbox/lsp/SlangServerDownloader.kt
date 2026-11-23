package com.jotrorox.verilogtoolbox.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.ui.EditorNotifications
import com.intellij.util.io.Decompressor
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import com.jotrorox.verilogtoolbox.common.GitHubReleases
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.settings.VeribleConfigurable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name

/**
 * Downloads the prebuilt slang-server release for this machine from GitHub. slang-server is MIT licensed,
 * so it is fetched without asking the first time a Verilog file is opened and no server is installed.
 */
object SlangServerDownloader {
    private const val REPOSITORY = "hudson-trading/slang-server"
    private const val STAGING_SUFFIX = ".partial"
    private val BINARY get() = ToolchainSettings.SLANG_SERVER_BINARY

    private val downloading = AtomicBoolean()

    /** Set when a download failed or was cancelled, so it is not started again for every file that is opened. */
    @Volatile
    private var automaticDownloadGaveUp = false

    val isDownloading: Boolean get() = downloading.get()

    /** slang-server versions are unpacked into `<IDE system dir>/verilog-toolbox/slang-server/<tag>`. */
    private val installRoot: Path
        get() = PathManager.getSystemDir().resolve("verilog-toolbox").resolve("slang-server")

    /** Name of the release asset built for [os] and [arch], or `null` if slang-server ships none. */
    internal fun assetName(os: OS, arch: CpuArch): String? = when (os) {
        OS.macOS -> "slang-server-macos.tar.gz" // A universal binary.
        OS.Linux -> when (arch) {
            CpuArch.X86_64 -> "slang-server-linux-x64.tar.gz"
            CpuArch.ARM64 -> "slang-server-linux-arm64.tar.gz"
            else -> null
        }
        OS.Windows -> when (arch) {
            CpuArch.X86_64 -> "slang-server-windows-x64.zip"
            CpuArch.ARM64 -> "slang-server-windows-arm64.zip"
            else -> null
        }
        else -> null
    }

    /** Orders release tags such as `v0.3.1` and `v0.10.0` by their numbers. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = versionNumbers(a)
        val y = versionNumbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val difference = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun versionNumbers(tag: String): List<Int> =
        Regex("\\d+").findAll(tag).map { it.value.toIntOrNull() ?: Int.MAX_VALUE }.toList()

    /** The newest slang-server this plugin has downloaded, if any. */
    fun installed(): Path? = installed(installRoot)

    internal fun installed(root: Path): Path? {
        if (!Files.isDirectory(root)) return null
        return try {
            Files.list(root).use { versions ->
                versions.filter { !it.name.endsWith(STAGING_SUFFIX) && Files.isExecutable(it.resolve(BINARY)) }
                    .max { a, b -> compareVersions(a.name, b.name) }
                    .orElse(null)
            }?.resolve(BINARY)
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Downloads and unpacks the latest release, returning the path of `slang-server`.
     * Blocking; call from a background thread.
     */
    @Throws(IOException::class)
    fun downloadLatest(indicator: ProgressIndicator): Path {
        indicator.isIndeterminate = true
        indicator.text = "Looking up the latest slang-server release…"
        val name = assetName(OS.CURRENT, CpuArch.CURRENT) ?: throw IOException(
            "slang-server publishes no prebuilt binary for ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}. " +
                "Build it yourself and set the path in the settings."
        )
        val release = GitHubReleases.latest(REPOSITORY, indicator)
        val tag = release.tag
        val asset = release.assets.firstOrNull { it.name == name }
            ?: throw IOException("slang-server release $tag has no '$name' download.")

        val target = installRoot.resolve(tag)
        val binary = target.resolve(BINARY)
        if (!Files.isExecutable(binary)) {
            val archive = Files.createTempFile("slang-server", if (name.endsWith(".zip")) ".zip" else ".tar.gz")
            try {
                indicator.text = "Downloading slang-server $tag…"
                asset.download(archive, indicator)

                indicator.isIndeterminate = true
                indicator.text = "Unpacking slang-server $tag…"
                install(archive, target)
            } finally {
                Files.deleteIfExists(archive)
            }
        }
        removeOtherVersions(target)
        return binary
    }

    /**
     * Unpacks [archive] into [target]. The files only appear there once the server has been seen to start,
     * so an interrupted or unusable download is never picked up as an installation.
     */
    @Throws(IOException::class)
    internal fun install(archive: Path, target: Path) {
        val staging = target.resolveSibling(target.name + STAGING_SUFFIX)
        NioFiles.deleteRecursively(staging)
        Files.createDirectories(staging)
        try {
            if (archive.name.endsWith(".zip")) Decompressor.Zip(archive).extract(staging)
            else Decompressor.Tar(archive).extract(staging)
            val binary = staging.resolve(BINARY)
            if (!Files.isRegularFile(binary)) throw IOException("The slang-server download does not contain $BINARY.")
            if (!SystemInfo.isWindows) binary.toFile().setExecutable(true)
            version(binary)
            NioFiles.deleteRecursively(target)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            NioFiles.deleteRecursively(staging)
        }
    }

    /** Runs `slang-server --version` and returns the version it reports, such as `0.3.1+2ad996f`. */
    @Throws(IOException::class)
    fun version(binary: Path): String {
        val output = try {
            CapturingProcessHandler(GeneralCommandLine(binary.toString(), "--version").withCharset(Charsets.UTF_8))
                .runProcess(30_000)
        } catch (e: ExecutionException) {
            throw IOException("slang-server does not start: ${e.message}", e)
        }
        if (output.isTimeout || output.exitCode != 0) {
            val reason = output.stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit code ${output.exitCode}"
            throw IOException("slang-server does not start: $reason")
        }
        return output.stdout.trim().substringAfterLast(' ')
    }

    /** Older downloads are of no use once a newer one works. A copy that is still running may stay behind. */
    private fun removeOtherVersions(current: Path) {
        try {
            Files.list(current.parent).use { it.filter { version -> version != current }.toList() }
                .forEach { runCatching { NioFiles.deleteRecursively(it) } }
        } catch (_: IOException) {
        }
    }

    /** Starts the download a missing slang-server calls for, unless the user switched that off. */
    fun downloadAutomatically(project: Project) {
        val settings = ToolchainSettings.getInstance()
        if (!settings.slangServerEnabled || !settings.slangServerAutoDownload) return
        // A path that is set but wrong is the user's to fix, and the banner says so.
        if (settings.slangServerPath.isNotEmpty() || automaticDownloadGaveUp) return
        if (ApplicationManager.getApplication().isUnitTestMode) return
        downloadInBackground(project)
    }

    /** Downloads in the background, then starts the server in every open project and reports the result. */
    fun downloadInBackground(project: Project) {
        if (!downloading.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                downloading.set(false)
                return@invokeLater
            }
            EditorNotifications.getInstance(project).updateAllNotifications()
            DownloadTask(project).queue()
        }
    }

    private class DownloadTask(project: Project) : Task.Backgroundable(project, "Downloading slang-server", true) {
        override fun run(indicator: ProgressIndicator) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            var installed = false
            try {
                val binary = downloadLatest(indicator)
                installed = true
                group.createNotification(
                    "slang-server ${binary.parent.name} installed",
                    "The SystemVerilog language server was downloaded from github.com/$REPOSITORY (MIT license).",
                    NotificationType.INFORMATION,
                ).notify(project)
            } catch (e: IOException) {
                group.createNotification("slang-server download failed", e.message ?: e.toString(), NotificationType.ERROR)
                    .addAction(NotificationAction.createSimpleExpiring("Retry") { downloadInBackground(project) })
                    .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
                    })
                    .notify(project)
            } finally {
                automaticDownloadGaveUp = !installed
                downloading.set(false)
                ApplicationManager.getApplication().invokeLater { VeribleLspIntegrationProvider.restartEverywhere() }
            }
        }
    }
}
