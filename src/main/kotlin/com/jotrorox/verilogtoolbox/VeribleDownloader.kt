package com.jotrorox.verilogtoolbox

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import com.intellij.util.system.CpuArch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** Downloads the prebuilt Verible release for this machine from GitHub. */
object VeribleDownloader {
    /** Verible versions are unpacked into `<IDE system dir>/verilog-toolbox/verible/<tag>`. */
    private val installRoot: Path
        get() = PathManager.getSystemDir().resolve("verilog-toolbox").resolve("verible")

    /** Homebrew's commands for Verible, the way to get it where no prebuilt binary is published. */
    const val HOMEBREW_INSTALL: String = "brew tap chipsalliance/verible && brew install verible"

    /**
     * Suffix of the release asset built for this OS and CPU, or null if Verible ships none, as for Intel
     * Macs: its macOS archive is for Apple silicon only.
     */
    private val assetSuffix: String? = when {
        SystemInfo.isMac && CpuArch.isArm64() -> "-macOS.tar.gz"
        SystemInfo.isLinux && CpuArch.isArm64() -> "-linux-static-arm64.tar.gz"
        SystemInfo.isLinux && CpuArch.isIntel64() -> "-linux-static-x86_64.tar.gz"
        SystemInfo.isWindows -> "-win64.zip"
        else -> null
    }

    /** Whether Verible can be downloaded for this machine. */
    val isAvailable: Boolean
        get() = assetSuffix != null

    /** On a Mac without a download, Verible comes from Homebrew. */
    val useHomebrew: Boolean
        get() = SystemInfo.isMac && !isAvailable

    /**
     * Downloads and unpacks the latest release, returning the path of `verible-verilog-ls`.
     * Blocking; call from a background thread.
     */
    @Throws(IOException::class)
    fun downloadLatest(indicator: ProgressIndicator): Path {
        val suffix = assetSuffix ?: throw IOException(
            if (useHomebrew) "Verible publishes no prebuilt binary for Intel Macs. Install it with Homebrew: $HOMEBREW_INSTALL"
            else "Verible publishes no prebuilt binary for ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}."
        )
        indicator.isIndeterminate = true
        indicator.text = "Looking up the latest Verible release…"
        val release = GitHubReleases.latest("chipsalliance/verible", indicator)
        val tag = release.tag
        val asset = release.assets.firstOrNull { it.name.endsWith(suffix) }
            ?: throw IOException("Verible release $tag has no '*$suffix' download.")

        val target = installRoot.resolve(tag)
        findLanguageServer(target)?.let { return it }

        val archive = Files.createTempFile("verible", suffix)
        try {
            indicator.text = "Downloading Verible $tag…"
            asset.download(archive, indicator)

            indicator.isIndeterminate = true
            indicator.text = "Unpacking Verible $tag…"
            NioFiles.deleteRecursively(target)
            Files.createDirectories(target)
            if (suffix.endsWith(".zip")) Decompressor.Zip(archive).extract(target)
            else Decompressor.Tar(archive).extract(target)
        } finally {
            Files.deleteIfExists(archive)
        }
        return findLanguageServer(target)
            ?: throw IOException("The Verible $tag download does not contain ${VeribleSettings.LANGUAGE_SERVER_BINARY}.")
    }

    private fun findLanguageServer(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        return Files.walk(dir).use { paths ->
            paths.filter { it.name == VeribleSettings.LANGUAGE_SERVER_BINARY && Files.isRegularFile(it) }
                .findFirst().orElse(null)
        }?.also { if (!SystemInfo.isWindows) it.toFile().setExecutable(true) }
    }
}
