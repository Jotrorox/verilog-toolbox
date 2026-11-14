package com.jotrorox.verilogtoolbox

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import com.intellij.util.system.CpuArch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name

/** Downloads the prebuilt Verible release for this machine from GitHub. */
object VeribleDownloader {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/chipsalliance/verible/releases/latest"

    /** Verible versions are unpacked into `<IDE system dir>/verilog-toolbox/verible/<tag>`. */
    private val installRoot: Path
        get() = PathManager.getSystemDir().resolve("verilog-toolbox").resolve("verible")

    /** Suffix of the release asset built for this OS and CPU, or an error if Verible ships none. */
    private fun assetSuffix(): String = when {
        SystemInfo.isMac && CpuArch.isArm64() -> "-macOS.tar.gz"
        SystemInfo.isMac -> throw IOException(
            "Verible publishes no prebuilt binary for Intel Macs. " +
                "Install it yourself (for example with Homebrew) and set the path in the settings."
        )
        SystemInfo.isLinux && CpuArch.isArm64() -> "-linux-static-arm64.tar.gz"
        SystemInfo.isLinux && CpuArch.isIntel64() -> "-linux-static-x86_64.tar.gz"
        SystemInfo.isWindows -> "-win64.zip"
        else -> throw IOException("Verible publishes no prebuilt binary for ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}.")
    }

    /**
     * Downloads and unpacks the latest release, returning the path of `verible-verilog-ls`.
     * Blocking; call from a background thread.
     */
    @Throws(IOException::class)
    fun downloadLatest(indicator: ProgressIndicator): Path {
        indicator.isIndeterminate = true
        indicator.text = "Looking up the latest Verible release…"
        val release = JsonParser.parseString(
            HttpRequests.request(LATEST_RELEASE_URL).accept("application/vnd.github+json").readString(indicator)
        ).asJsonObject
        val tag = release["tag_name"].asString
        val suffix = assetSuffix()
        val asset = release["assets"].asJsonArray.map { it.asJsonObject }
            .firstOrNull { it["name"].asString.endsWith(suffix) }
            ?: throw IOException("Verible release $tag has no '*$suffix' download.")

        val target = installRoot.resolve(tag)
        findLanguageServer(target)?.let { return it }

        val archive = Files.createTempFile("verible", suffix)
        try {
            indicator.text = "Downloading Verible $tag…"
            HttpRequests.request(asset["browser_download_url"].asString).saveToFile(archive, indicator)
            verifyDigest(archive, asset)

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

    /** Checks the archive against the SHA-256 digest GitHub reports for the asset, when it reports one. */
    private fun verifyDigest(archive: Path, asset: JsonObject) {
        val expected = asset["digest"]?.takeIf { it.isJsonPrimitive }?.asString
            ?.removePrefix("sha256:")?.takeIf { it.length == 64 } ?: return
        val sha = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(archive).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        val actual = sha.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IOException("The Verible download is corrupted (checksum mismatch). Please try again.")
        }
    }

    private fun findLanguageServer(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        return Files.walk(dir).use { paths ->
            paths.filter { it.name == VeribleSettings.LANGUAGE_SERVER_BINARY && Files.isRegularFile(it) }
                .findFirst().orElse(null)
        }?.also { if (!SystemInfo.isWindows) it.toFile().setExecutable(true) }
    }
}
