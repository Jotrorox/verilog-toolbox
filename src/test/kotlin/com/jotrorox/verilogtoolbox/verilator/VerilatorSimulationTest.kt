package com.jotrorox.verilogtoolbox.verilator

import com.jotrorox.verilogtoolbox.common.FileList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class VerilatorSimulationTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun traceFollowsTheDumpTheTestbenchAsksFor() {
        assertEquals(listOf("--trace-vcd"), VerilatorSimulation.traceOptions("initial \$dumpfile(\"tb.vcd\");", cpp = false, newOptions = true))
        assertEquals(listOf("--trace"), VerilatorSimulation.traceOptions("initial \$dumpfile(\"tb.vcd\");", cpp = false, newOptions = false))
        assertEquals(listOf("--trace-fst"), VerilatorSimulation.traceOptions("\$dumpfile ( \"waves.fst\" );", cpp = false, newOptions = true))
        assertEquals(emptyList<String>(), VerilatorSimulation.traceOptions("initial \$display(1);", cpp = false, newOptions = true))
        assertEquals(listOf("--trace-fst"), VerilatorSimulation.traceOptions("#include <verilated_fst_c.h>", cpp = true, newOptions = true))
        assertEquals(listOf("--trace-vcd"), VerilatorSimulation.traceOptions("#include \"verilated_vcd_c.h\"", cpp = true, newOptions = true))
        assertEquals(emptyList<String>(), VerilatorSimulation.traceOptions("#include \"Vtop.h\"", cpp = true, newOptions = true))
    }

    @Test
    fun designOfAFileListWithTheTestbenchLast() {
        val rtl = Path.of("/p/rtl").toAbsolutePath()
        val design = FileList(
            sources = listOf(rtl.resolve("counter.sv")),
            includeDirectories = listOf(rtl.resolve("include")),
            defines = listOf("WIDTH=4"),
            libraryDirectories = listOf(rtl),
            libraryFiles = listOf(rtl.resolve("cells.v")),
            libraryExtensions = listOf(".vh"),
            topModules = listOf("counter"),
            ignored = emptyList(),
            errors = emptyList(),
        )
        val testbench = Path.of("/p/tb/counter_tb.sv").toAbsolutePath()
        assertEquals(
            listOf(
                "-I${testbench.parent}", "-I${rtl.resolve("include")}", "-DWIDTH=4", "-y", rtl.toString(), "+libext+.v+.sv+.vh",
                "-v", rtl.resolve("cells.v").toString(), rtl.resolve("counter.sv").toString(), testbench.toString(),
            ),
            VerilatorSimulation.designArguments(design, testbench),
        )
        // A C++ testbench is no Verilog source; the list's top is given apart as --top.
        assertEquals(rtl.resolve("counter.sv").toString(), VerilatorSimulation.designArguments(design, null).last())
    }

    @Test
    fun configureWithTheIdesNinjaAndMinGw() {
        val toolchain = CppToolchain(
            Path.of("/ide/cmake"), Path.of("/ide/ninja"), listOf(Path.of("/ide/mingw/bin")),
            Path.of("/ide/mingw/bin/gcc.exe"), Path.of("/ide/mingw/bin/g++.exe"),
        )
        val root = Path.of("/p/build/verilator")
        assertEquals(
            listOf(
                "-S", root.toString(), "-B", root.resolve("cmake-build-run").toString(), "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${Path.of("/ide/ninja")}", "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_C_COMPILER=${Path.of("/ide/mingw/bin/gcc.exe")}", "-DCMAKE_CXX_COMPILER=${Path.of("/ide/mingw/bin/g++.exe")}",
            ),
            VerilatorSimulation.configureArguments(toolchain, root, root.resolve("cmake-build-run")),
        )
        val plain = CppToolchain(Path.of("/usr/bin/cmake"), null, emptyList(), null, null)
        assertEquals(
            listOf("-S", root.toString(), "-B", root.resolve("b").toString(), "-DCMAKE_BUILD_TYPE=Release"),
            VerilatorSimulation.configureArguments(plain, root, root.resolve("b")),
        )
    }

    @Test
    fun dumpWrittenInTheRunIsFound() {
        val directory = folder.root.toPath()
        val start = System.currentTimeMillis()
        val old = Files.writeString(directory.resolve("old.vcd"), "")
        Files.setLastModifiedTime(old, FileTime.fromMillis(start - 60_000))
        assertNull(VerilatorSimulation.findDump(directory, null, start - 1_000))
        val named = Files.writeString(directory.resolve("tb.vcd"), "")
        val other = Files.writeString(directory.resolve("other.fst"), "")
        Files.setLastModifiedTime(other, FileTime.fromMillis(start + 5_000))
        assertEquals(named, VerilatorSimulation.findDump(directory, named, start - 1_000))
        assertEquals(other, VerilatorSimulation.findDump(directory, directory.resolve("missing.vcd"), start - 1_000))
    }

    @Test
    fun modelIncludedByACppTestbench() {
        assertEquals("counter", VerilatorRunConfiguration.modelIncluded("#include <verilated.h>\n#include \"Vcounter.h\"\n"))
        assertEquals("counter_tb", VerilatorRunConfiguration.modelIncluded("  #  include <Vcounter_tb.h>"))
        assertEquals("alu", VerilatorRunConfiguration.modelIncluded("#include \"Valu__Syms.h\""))
        assertNull(VerilatorRunConfiguration.modelIncluded("#include \"verilated_vcd_c.h\"\nint main() {}"))
    }

    /** CLion 2026.2 on Windows: `bin/ninja/{cygwin,win,wsl}/x64/…`; the Cygwin copy does not start without Cygwin. */
    @Test
    fun bundledCopyForThisSystemIsChosen() {
        val ninja = Path.of("/ide/bin/ninja")
        val cygwin = ninja.resolve("cygwin/x64/ninja.exe")
        val win = ninja.resolve("win/x64/ninja.exe")
        val wsl = ninja.resolve("wsl/x64/ninja")
        assertEquals(win, CppToolchain.choose(ninja, listOf(cygwin, win, wsl), "win", setOf("x64")))
        assertNull(CppToolchain.choose(ninja, listOf(cygwin, wsl), "win", setOf("x64")))

        val linux = Path.of("/ide/bin/cmake")
        val arm = linux.resolve("linux/aarch64/bin/cmake")
        val intel = linux.resolve("linux/x64/bin/cmake")
        assertEquals(arm, CppToolchain.choose(linux, listOf(intel, arm), "linux", setOf("aarch64", "arm64")))
        // A layout without the system's name is still taken, as long as it is not another toolchain's.
        assertEquals(linux.resolve("bin/cmake"), CppToolchain.choose(linux, listOf(linux.resolve("bin/cmake")), "mac", setOf("arm64")))
    }

    @Test
    fun bundledToolIsFoundBelowTheIdesDirectory() {
        val directory = folder.root.toPath().resolve("cmake")
        val name = if (com.intellij.openapi.util.SystemInfo.isWindows) "cmake.exe" else "cmake"
        val cmake = Files.createDirectories(directory.resolve("win/x64/bin")).resolve(name)
        Files.writeString(cmake, "")
        cmake.toFile().setExecutable(true)
        assertEquals(cmake, CppToolchain.bundled(directory, name))
        assertNull(CppToolchain.bundled(folder.root.toPath().resolve("ninja"), name))
    }
}
