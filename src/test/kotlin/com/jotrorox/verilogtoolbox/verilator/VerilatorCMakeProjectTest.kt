package com.jotrorox.verilogtoolbox.verilator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class VerilatorCMakeProjectTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun manifest(timing: Boolean = true, fst: Boolean = false) = VerilatorManifest(
        generated = listOf(Path.of("/b/verilated/Vtb.cpp"), Path.of("/b/verilated/Vtb__Syms.cpp")),
        global = listOf(Path.of("/v/include/verilated.cpp")),
        userClasses = listOf(Path.of("/b/verilated/Vtb__main.cpp")),
        dependencies = listOf(Path.of("/p/tb.sv")),
        timing = timing, traceVcd = !fst, traceFst = fst, traceSaif = false, coverage = false, dpi = false,
        systemC = false, threads = 1, cflags = emptyList(), ldflags = emptyList(),
    )

    private fun simulation(manifest: VerilatorManifest, environment: Map<String, String> = emptyMap(), cpp: List<Path> = emptyList()) =
        VerilatorCMakeProject.Simulation(
            target = "tb", description = "tb", prefix = "Vtb", manifest = manifest,
            modelDirectory = Path.of("/b/verilated"),
            installation = VerilatorInstallation(Path.of("/v/bin/verilator_bin"), Path.of("/v"), true, environment),
            verilatorArguments = listOf("--cc", "--top", "tb", "-DMSG=\"a b\"", "/p/tb.sv"),
            workingDirectory = Path.of("/p"),
            cppSources = cpp,
            simulationDirectory = Path.of("/p/build/sim/tb"),
            simulationArguments = listOf("+seed=1"),
        )

    private fun cmakePath(path: String) = Path.of(path).toString().replace('\\', '/')

    @Test
    fun simulationIsBuiltFromWhatVerilatorGenerated() {
        val text = VerilatorCMakeProject.simulationText(simulation(manifest()))
        assertTrue(text, text.startsWith(VerilatorCMakeProject.MARKER))
        assertTrue(text, "set(VERILATOR_ROOT \"${cmakePath("/v")}\")" in text)
        assertTrue(text, "    \"${cmakePath("/b/verilated/Vtb__Syms.cpp")}\"\n" in text)
        assertTrue(text, "add_executable(tb\n    \${tb_GENERATED}\n    \"${cmakePath("/v/include/verilated.cpp")}\"\n" in text)
        assertTrue(text, "VM_TRACE=1 VM_TRACE_VCD=1 VM_TRACE_FST=0" in text)
        assertTrue(text, "VM_PREFIX=Vtb \"VM_PREFIX_INCLUDE=<Vtb.h>\"" in text)
        assertTrue(text, "target_compile_features(tb PRIVATE cxx_std_20)" in text)
        assertTrue(text, "-fcoroutines" in text)
        assertTrue(text, "OUTPUT \${tb_GENERATED}" in text)
        assertTrue(text, "COMMAND \"${cmakePath("/v/bin/verilator_bin")}\" \"--cc\" \"--top\" \"tb\" \"-DMSG=\\\"a b\\\"\"" in text)
        assertTrue(text, "add_test(NAME tb COMMAND tb \"+seed=1\" WORKING_DIRECTORY \"${cmakePath("/p/build/sim/tb")}\")" in text)
        assertFalse(text, "ZLIB" in text)
    }

    @Test
    fun withoutTimingNoCoroutines() {
        val text = VerilatorCMakeProject.simulationText(simulation(manifest(timing = false, fst = true)))
        assertTrue(text, "cxx_std_17" in text)
        assertFalse(text, "-fcoroutines" in text)
        assertTrue(text, "VM_TRACE_FST=1" in text)
        assertTrue(text, "ZLIB::ZLIB" in text)
    }

    @Test
    fun cppTestbenchIsCompiledWithTheModel() {
        val text = VerilatorCMakeProject.simulationText(simulation(manifest(), cpp = listOf(Path.of("/p/counter_tb.cpp"))))
        assertTrue(text, "    \"${cmakePath("/p/counter_tb.cpp")}\"\n)" in text)
    }

    @Test
    fun environmentWithAPathKeepsItsSemicolons() {
        val text = VerilatorCMakeProject.simulationText(simulation(manifest(), mapOf("VERILATOR_ROOT" to "/v", "PATH" to "/a${java.io.File.pathSeparator}/b")))
        assertTrue(text, "COMMAND \"\${CMAKE_COMMAND}\" -E env \"PATH=/a$<SEMICOLON>/b\" \"VERILATOR_ROOT=/v\" " in text)
    }

    @Test
    fun quoting() {
        assertEquals("\"C:/a b/c\"", VerilatorCMakeProject.quote("C:\\a b\\c"))
        assertEquals("\"-DX=\\\"y\\\"\"", VerilatorCMakeProject.quote("-DX=\"y\"", path = false))
        assertEquals("\"a\\\\b \\\$x $<SEMICOLON>\"", VerilatorCMakeProject.quote("a\\b \$x $<SEMICOLON>", path = false))
    }

    @Test
    fun rootTakesInOnlyOurSimulations() {
        val root = folder.root.toPath()
        VerilatorCMakeProject.write(root.resolve("b_tb/CMakeLists.txt"), "${VerilatorCMakeProject.MARKER} for b\n")
        VerilatorCMakeProject.write(root.resolve("a_tb/CMakeLists.txt"), "${VerilatorCMakeProject.MARKER} for a\n")
        Files.createDirectories(root.resolve("mine"))
        Files.writeString(root.resolve("mine/CMakeLists.txt"), "project(mine)\n")
        Files.createDirectories(root.resolve(VerilatorCMakeProject.BUILD_DIRECTORY))
        val text = VerilatorCMakeProject.rootText(root)
        assertTrue(text, text.endsWith("add_subdirectory(a_tb)\nadd_subdirectory(b_tb)\n"))
        assertFalse(text, "mine" in text)
    }

    @Test
    fun unchangedFileIsNotWrittenAgain() {
        val file = folder.root.toPath().resolve("CMakeLists.txt")
        VerilatorCMakeProject.write(file, "same\n")
        val written = Files.getLastModifiedTime(file)
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(written.toMillis() - 60_000))
        VerilatorCMakeProject.write(file, "same\n")
        assertEquals(written.toMillis() - 60_000, Files.getLastModifiedTime(file).toMillis())
        VerilatorCMakeProject.write(file, "other\n")
        assertEquals("other\n", Files.readString(file))
    }

    @Test
    fun targetNames() {
        assertEquals("counter_tb", VerilatorCMakeProject.target("counter_tb"))
        assertEquals("counter_tb_cpp", VerilatorCMakeProject.target("counter_tb.cpp"))
        assertEquals("simulation", VerilatorCMakeProject.target(""))
    }
}
