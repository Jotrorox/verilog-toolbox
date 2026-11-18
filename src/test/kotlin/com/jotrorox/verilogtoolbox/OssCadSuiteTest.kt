package com.jotrorox.verilogtoolbox

import com.intellij.openapi.util.SystemInfo
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

class OssCadSuiteTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A stand-in for a launcher in the suite's `bin`. */
    private fun launcher(root: Path, name: String, script: String = "echo 'Icarus Verilog version 13.0 (devel)'"): Path {
        val binaries = Files.createDirectories(root.resolve("bin"))
        val launcher = Files.writeString(binaries.resolve(name), "#!/bin/sh\n$script\n")
        launcher.toFile().setExecutable(true)
        return launcher
    }

    @Test
    fun newestBuildIsUsed() {
        assumeFalse(SystemInfo.isWindows)
        val root = folder.root.toPath()
        assertNull(OssCadSuite.installed(root.resolve("missing")))
        assertNull(OssCadSuite.installed(root))

        launcher(root.resolve("2026-10-01"), "iverilog")
        val newest = root.resolve("2026-11-18").also { launcher(it, "iverilog") }
        launcher(root.resolve("2026-11-19.partial"), "iverilog") // An interrupted download.
        Files.createDirectories(root.resolve("2026-11-20")) // Nothing in it.
        assertEquals(newest, OssCadSuite.installed(root))
    }

    @Test
    fun toolsAreTheLaunchersInBin() {
        assumeFalse(SystemInfo.isWindows)
        val root = folder.root.toPath()
        val yosys = launcher(root, "yosys")
        assertEquals(yosys, OssCadSuite.tool(root, "yosys"))
        assertNull(OssCadSuite.tool(root, "verilator_bin"))
        Files.createDirectories(root.resolve("bin").resolve("gtkwave")) // Not a file.
        assertNull(OssCadSuite.tool(root, "gtkwave"))
    }

    /** Packs [directory] the way the suite is packed: everything below `oss-cad-suite/`. */
    private fun archive(directory: Path): Path {
        val archive = folder.root.toPath().resolve("oss-cad-suite-test.tgz")
        val tar = ProcessBuilder("tar", "czf", archive.toString(), "-C", directory.parent.toString(), directory.fileName.toString())
            .inheritIO().start()
        assertEquals(0, tar.waitFor())
        return archive
    }

    @Test
    fun wholeSuiteIsInstalledOverAnOlderCopy() {
        assumeFalse(SystemInfo.isWindows)
        val source = folder.newFolder("pack", "oss-cad-suite").toPath()
        launcher(source, "iverilog")
        launcher(source, "yosys")
        Files.createDirectories(source.resolve("share/yosys/plugins")).resolve("slang.so").let { Files.writeString(it, "") }
        Files.createDirectories(source.resolve("libexec")).resolve("verilator_bin").let { Files.writeString(it, "") }

        val target = folder.root.toPath().resolve("install").resolve("2026-11-18")
        Files.writeString(Files.createDirectories(target).resolve("stale.txt"), "left over")
        OssCadSuite.install(archive(source), target, null)

        assertTrue(Files.isExecutable(target.resolve("bin/iverilog")))
        assertTrue(Files.isExecutable(target.resolve("bin/yosys")))
        assertTrue(Files.isRegularFile(target.resolve("libexec/verilator_bin")))
        assertTrue(YosysSynthesis.hasSlangPlugin(target.resolve("bin/yosys")))
        assertFalse(Files.exists(target.resolve("stale.txt")))
        assertEquals(listOf("2026-11-18"), Files.list(target.parent).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun suiteWhoseIcarusDoesNotStartIsNotInstalled() {
        assumeFalse(SystemInfo.isWindows)
        val source = folder.newFolder("pack", "oss-cad-suite").toPath()
        launcher(source, "iverilog", "echo 'GLIBC_2.38 not found' >&2; exit 1")

        val target = folder.root.toPath().resolve("install").resolve("2026-11-18")
        val error = assertThrows(IOException::class.java) { OssCadSuite.install(archive(source), target, null) }
        assertEquals("The downloaded OSS CAD Suite does not start: GLIBC_2.38 not found", error.message)
        assertFalse(Files.exists(target))
        assertNull(OssCadSuite.installed(target.parent))
    }
}
