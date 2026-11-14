package com.jotrorox.verilogtoolbox

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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs Icarus Verilog. The project publishes no binaries of its own, so this downloads the nightly
 * OSS CAD Suite, which bundles a relocatable build, and unpacks only the files Icarus needs.
 */
object IcarusDownloader {
    private const val ARCHIVE_ROOT = "oss-cad-suite/"
    private val TOOLS = setOf("iverilog", "vvp", "iverilog-vpi")
    private val HELPERS = setOf("iverilog", "ivl", "ivlpp", "vhdlpp", "vvp", "realpath")

    /** Big shared libraries of the suite's other tools, which Icarus never loads. */
    private val UNRELATED_LIBRARIES =
        listOf("libLLVM", "libgallium", "libicu", "icu", "libpython", "libghdl", "libgnat", "libgtk", "libQt", "Qt")

    /** Icarus versions are unpacked into `<IDE system dir>/verilog-toolbox/icarus/<tag>`. */
    private val installRoot: Path
        get() = PathManager.getSystemDir().resolve("verilog-toolbox").resolve("icarus")

    /** The part of the suite's archive name that identifies this OS and CPU. */
    private fun platform(): String = when {
        SystemInfo.isMac && CpuArch.isArm64() -> "darwin-arm64"
        SystemInfo.isMac && CpuArch.isIntel64() -> "darwin-x64"
        SystemInfo.isLinux && CpuArch.isArm64() -> "linux-arm64"
        SystemInfo.isLinux && CpuArch.isIntel64() -> "linux-x64"
        SystemInfo.isWindows && CpuArch.isIntel64() -> "windows-x64"
        else -> throw IOException(
            "There is no prebuilt Icarus Verilog for ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}. " +
                "Install it yourself and set the path in the settings."
        )
    }

    /**
     * Whether [entry] of the suite archive belongs to Icarus: its launchers, the `ivl` compiler back ends
     * and the shared libraries next to them.
     */
    internal fun isIcarusFile(entry: String): Boolean {
        val path = entry.replace('\\', '/').removePrefix("./").removePrefix(ARCHIVE_ROOT).trimEnd('/')
        val name = path.substringAfterLast('/')
        return when (path.substringBeforeLast('/', "")) {
            "bin" -> name.removeSuffix(".exe") in TOOLS || name.endsWith(".dll")
            "libexec" -> name in HELPERS
            "lib" -> (".so" in name || name.endsWith(".dylib") || name.endsWith(".dll")) &&
                UNRELATED_LIBRARIES.none { name.startsWith(it) }
            "license" -> name == "LICENSE.iverilog"
            else -> path.startsWith("lib/ivl/")
        }
    }

    /**
     * Downloads and unpacks the latest build, returning the path of `iverilog`.
     * Blocking; call from a background thread.
     */
    @Throws(IOException::class)
    fun downloadLatest(indicator: ProgressIndicator): Path {
        indicator.isIndeterminate = true
        indicator.text = "Looking up the latest OSS CAD Suite build…"
        val release = GitHubReleases.latest("YosysHQ/oss-cad-suite-build", indicator)
        val platform = platform()
        val asset = release.assets.firstOrNull { "-$platform-" in it.name && it.name.endsWith(".tgz") }
            ?: throw IOException("OSS CAD Suite build ${release.tag} has no download for $platform.")

        val target = installRoot.resolve(release.tag)
        val installed = target.resolve("bin").resolve(ToolchainSettings.IVERILOG_BINARY)
        if (Files.isExecutable(installed)) return installed

        val archive = Files.createTempFile("oss-cad-suite", ".tgz")
        try {
            indicator.text = "Downloading Icarus Verilog (${asset.size / 1_000_000} MB, OSS CAD Suite ${release.tag})…"
            asset.download(archive, indicator)

            indicator.isIndeterminate = true
            indicator.text = "Unpacking Icarus Verilog…"
            unpack(archive, target)
        } finally {
            Files.deleteIfExists(archive)
        }
        if (!Files.isExecutable(installed)) {
            throw IOException("OSS CAD Suite build ${release.tag} does not contain ${ToolchainSettings.IVERILOG_BINARY}.")
        }
        try {
            verify(installed)
        } catch (e: IOException) {
            NioFiles.deleteRecursively(target)
            throw e
        }
        return installed
    }

    /** Unpacks the Icarus files of the suite [archive] into [target], replacing what is there. */
    @Throws(IOException::class)
    internal fun unpack(archive: Path, target: Path) {
        NioFiles.deleteRecursively(target)
        Files.createDirectories(target)
        Decompressor.Tar(archive).filter(::isIcarusFile).removePrefixPath(ARCHIVE_ROOT.trimEnd('/')).extract(target)
    }

    /** Makes sure the unpacked compiler starts, so a broken install is reported now and not on the first run. */
    @Throws(IOException::class)
    internal fun verify(iverilog: Path) {
        val output = try {
            CapturingProcessHandler(ToolchainSettings.icarusCommandLine(iverilog).withParameters("-V"))
                .runProcess(30_000)
        } catch (e: ExecutionException) {
            throw IOException("The downloaded Icarus Verilog does not start: ${e.message}", e)
        }
        if (output.isTimeout || output.exitCode != 0) {
            val reason = output.stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit code ${output.exitCode}"
            throw IOException("The downloaded Icarus Verilog does not start: $reason")
        }
    }

    /** Downloads in the background, stores the path in the settings and reports the result in a balloon. */
    fun downloadInBackground(project: Project) {
        object : Task.Backgroundable(project, "Downloading Icarus Verilog", true) {
            override fun run(indicator: ProgressIndicator) {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                try {
                    val iverilog = downloadLatest(indicator)
                    ToolchainSettings.getInstance().iverilogPath = iverilog.toString()
                    group.createNotification(
                        "Icarus Verilog installed", "Run the testbench again to simulate it.", NotificationType.INFORMATION
                    ).notify(project)
                } catch (e: IOException) {
                    group.createNotification(
                        "Icarus Verilog download failed", e.message ?: e.toString(), NotificationType.ERROR
                    ).notify(project)
                }
            }
        }.queue()
    }

    /** Balloon shown when a simulation is started without Icarus Verilog, offering to download it. */
    fun notifyMissing(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
            .createNotification(
                "Icarus Verilog was not found",
                "Simulating a testbench needs iverilog and vvp. The download is the OSS CAD Suite " +
                    "(500–750 MB), of which only Icarus Verilog is kept.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Download Icarus Verilog") { downloadInBackground(project) })
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VeribleConfigurable::class.java)
            })
            .notify(project)
    }
}
