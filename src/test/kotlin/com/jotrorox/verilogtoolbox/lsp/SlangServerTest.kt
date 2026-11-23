package com.jotrorox.verilogtoolbox.lsp

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SlangServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun releaseAssetMatchesThePlatform() {
        assertEquals("slang-server-macos.tar.gz", SlangServerDownloader.assetName(OS.macOS, CpuArch.ARM64))
        assertEquals("slang-server-macos.tar.gz", SlangServerDownloader.assetName(OS.macOS, CpuArch.X86_64))
        assertEquals("slang-server-linux-x64.tar.gz", SlangServerDownloader.assetName(OS.Linux, CpuArch.X86_64))
        assertEquals("slang-server-linux-arm64.tar.gz", SlangServerDownloader.assetName(OS.Linux, CpuArch.ARM64))
        assertEquals("slang-server-windows-x64.zip", SlangServerDownloader.assetName(OS.Windows, CpuArch.X86_64))
        assertEquals("slang-server-windows-arm64.zip", SlangServerDownloader.assetName(OS.Windows, CpuArch.ARM64))
        assertNull(SlangServerDownloader.assetName(OS.Linux, CpuArch.X86))
        assertNull(SlangServerDownloader.assetName(OS.FreeBSD, CpuArch.X86_64))
    }

    @Test
    fun versionsAreOrderedByNumber() {
        assertTrue(SlangServerDownloader.compareVersions("v0.10.0", "v0.9.3") > 0)
        assertTrue(SlangServerDownloader.compareVersions("v0.3.1", "v0.3") > 0)
        assertTrue(SlangServerDownloader.compareVersions("v0.3.1", "v1.0.0") < 0)
        assertEquals(0, SlangServerDownloader.compareVersions("v0.3.0", "0.3"))
    }

    /** A stand-in for the server that only answers `--version`. */
    private fun fakeServer(directory: Path, script: String = "echo 'slang-server version 9.9.9+abc'"): Path {
        Files.createDirectories(directory)
        val binary = Files.writeString(directory.resolve(ToolchainSettings.SLANG_SERVER_BINARY), "#!/bin/sh\n$script\n")
        binary.toFile().setExecutable(true)
        return binary
    }

    @Test
    fun newestInstalledVersionIsUsed() {
        assumeFalse(SystemInfo.isWindows)
        val root = folder.root.toPath()
        assertNull(SlangServerDownloader.installed(root.resolve("missing")))
        assertNull(SlangServerDownloader.installed(root))

        fakeServer(root.resolve("v0.9.3"))
        val newest = fakeServer(root.resolve("v0.10.0"))
        fakeServer(root.resolve("v0.11.0.partial")) // An interrupted download.
        Files.createDirectories(root.resolve("v0.12.0")) // Nothing in it.
        assertEquals(newest, SlangServerDownloader.installed(root))
    }

    /** Packs [directory] the way the release archives are: files at the root, named `./<file>`. */
    private fun archive(directory: Path): Path {
        val archive = folder.root.toPath().resolve("slang-server-test.tar.gz")
        val tar = ProcessBuilder("tar", "czf", archive.toString(), "-C", directory.toString(), ".").inheritIO().start()
        assertEquals(0, tar.waitFor())
        return archive
    }

    @Test
    fun archiveIsInstalledOverAnOlderCopy() {
        assumeFalse(SystemInfo.isWindows)
        val source = folder.newFolder("source").toPath()
        Files.writeString(source.resolve("README.txt"), "prebuilt")
        // Not executable in the archive: the installer has to see to that.
        fakeServer(source).toFile().setExecutable(false)

        val target = folder.root.toPath().resolve("install").resolve("v9.9.9")
        Files.writeString(Files.createDirectories(target).resolve("stale.txt"), "left over")
        SlangServerDownloader.install(archive(source), target)

        val binary = target.resolve(ToolchainSettings.SLANG_SERVER_BINARY)
        assertEquals("9.9.9+abc", SlangServerDownloader.version(binary))
        assertFalse(Files.exists(target.resolve("stale.txt")))
        assertEquals(listOf("v9.9.9"), Files.list(target.parent).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun serverThatDoesNotStartIsNotInstalled() {
        assumeFalse(SystemInfo.isWindows)
        val source = folder.newFolder("broken").toPath()
        fakeServer(source, "echo 'GLIBC_2.38 not found' >&2; exit 1")

        val target = folder.root.toPath().resolve("install").resolve("v9.9.9")
        val error = assertThrows(IOException::class.java) { SlangServerDownloader.install(archive(source), target) }
        assertEquals("slang-server does not start: GLIBC_2.38 not found", error.message)
        assertFalse(Files.exists(target))
        assertNull(SlangServerDownloader.installed(target.parent))
    }

    @Test
    fun onlyVeribleParseErrorsAreLeftToSlang() {
        assertTrue(isVeribleSyntaxError("syntax error at token"))
        assertTrue(isVeribleSyntaxError("Parse error: unexpected EOF"))
        assertFalse(isVeribleSyntaxError("Remove trailing spaces. [Style: trailing-spaces] [no-trailing-spaces]"))
        assertFalse(isVeribleSyntaxError(null))
    }
}
