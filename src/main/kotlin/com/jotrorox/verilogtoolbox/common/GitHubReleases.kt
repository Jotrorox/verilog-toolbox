package com.jotrorox.verilogtoolbox.common

import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Looks up and downloads the assets of a project's latest GitHub release. */
object GitHubReleases {
    class Asset(val name: String, val url: String, val size: Long, private val sha256: String?) {
        /** Downloads the asset to [target] and checks it against the digest GitHub reports, when it reports one. */
        @Throws(IOException::class)
        fun download(target: Path, indicator: ProgressIndicator) {
            HttpRequests.request(url).saveToFile(target, indicator)
            val expected = sha256 ?: return
            val sha = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(target).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sha.update(buffer, 0, read)
                }
            }
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("The download of $name is corrupted (checksum mismatch). Please try again.")
            }
        }
    }

    class Release(val tag: String, val assets: List<Asset>)

    /** Fetches the latest release of [repository], given as `owner/name`. Blocking. */
    @Throws(IOException::class)
    fun latest(repository: String, indicator: ProgressIndicator): Release {
        val release = JsonParser.parseString(
            HttpRequests.request("https://api.github.com/repos/$repository/releases/latest")
                .accept("application/vnd.github+json").readString(indicator)
        ).asJsonObject
        val assets = release["assets"].asJsonArray.map { it.asJsonObject }.map { asset ->
            val digest = asset["digest"]?.takeIf { it.isJsonPrimitive }?.asString
                ?.removePrefix("sha256:")?.takeIf { it.length == 64 }
            Asset(asset["name"].asString, asset["browser_download_url"].asString, asset["size"].asLong, digest)
        }
        return Release(release["tag_name"].asString, assets)
    }
}
