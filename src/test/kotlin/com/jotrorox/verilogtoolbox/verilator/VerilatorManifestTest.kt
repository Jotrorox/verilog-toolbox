package com.jotrorox.verilogtoolbox.verilator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** The manifests are shaped as `verilator-config.cmake` of Verilator 5.055 reads them. */
class VerilatorManifestTest {
    private val directory = Path.of("/work/tb").toAbsolutePath()
    private val root = Path.of("/opt/verilator").toAbsolutePath()

    /** [root] as JSON writes it, with its backslashes escaped on Windows. */
    private val jsonRoot = root.toString().replace("\\", "\\\\")

    @Test
    fun jsonListsTheFilesAndOptions() {
        val json = """
            {
              "system": {"perl": "perl", "python3": "python3", "verilator_root": "$jsonRoot", "verilator_solver": ""},
              "options": {
                "cflags": ["-DFOO"], "ldflags": [], "system_c": false, "coverage": false, "dpi": false,
                "use_timing": true, "threads": 1, "trace_fst": false, "trace_saif": false, "trace_vcd": true
              },
              "sources": {
                "global": ["$jsonRoot/include/verilated.cpp", "$jsonRoot/include/verilated_vcd_c.cpp"],
                "classes_slow": ["obj/Vtb___024root__Slow.cpp"],
                "classes_fast": ["obj/Vtb.cpp", "obj/Vtb___024root.cpp"],
                "support_slow": ["obj/Vtb__Syms.cpp"],
                "support_fast": [],
                "user_classes": ["obj/Vtb__main.cpp"],
                "module_interfaces": [],
                "deps": ["/work/tb/tb.sv", "/work/rtl/counter.sv"]
              }
            }
        """.trimIndent()
        val manifest = VerilatorManifest.parseJson(json, directory)
        val obj = directory.resolve("obj")
        assertEquals(
            listOf(obj.resolve("Vtb.cpp"), obj.resolve("Vtb___024root.cpp"), obj.resolve("Vtb___024root__Slow.cpp"), obj.resolve("Vtb__Syms.cpp")),
            manifest.generated,
        )
        assertEquals(listOf(root.resolve("include/verilated.cpp"), root.resolve("include/verilated_vcd_c.cpp")), manifest.global)
        assertEquals(listOf(obj.resolve("Vtb__main.cpp")), manifest.userClasses)
        assertEquals(listOf(Path.of("/work/tb/tb.sv").toAbsolutePath(), Path.of("/work/rtl/counter.sv").toAbsolutePath()), manifest.dependencies)
        assertTrue(manifest.timing)
        assertTrue(manifest.traceVcd)
        assertFalse(manifest.traceFst)
        assertTrue(manifest.trace)
        assertEquals(listOf("-DFOO"), manifest.cflags)
        assertFalse(manifest.hierarchical)
    }

    @Test
    fun jsonWithSubmodulesIsHierarchical() {
        val json = """{"options": {}, "sources": {}, "submodules": [{"prefix": "Vsub", "top": "sub"}]}"""
        assertTrue(VerilatorManifest.parseJson(json, directory).hierarchical)
    }

    @Test
    fun jsonListGivenAsOneStringIsSplit() {
        val json = """{"options": {"cflags": "-O2 -DX"}, "sources": {"classes_fast": "a.cpp b.cpp"}}"""
        val manifest = VerilatorManifest.parseJson(json, directory)
        assertEquals(listOf("-O2", "-DX"), manifest.cflags)
        assertEquals(listOf(directory.resolve("a.cpp"), directory.resolve("b.cpp")), manifest.generated)
    }

    @Test
    fun cmakeFileOfAnOlderVerilator() {
        val cmake = """
            # Verilated -*- CMake -*-
            set(VERILATOR_ROOT "$root" CACHE PATH "Path to Verilator kit (from ${'$'}VERILATOR_ROOT)")
            set(Vtb_SC 0)
            set(Vtb_TIMING 1)
            set(Vtb_THREADS 1)
            set(Vtb_TRACE_VCD 0)
            set(Vtb_TRACE_FST 1)
            set(Vtb_GLOBAL "${'$'}{VERILATOR_ROOT}/include/verilated.cpp" "${'$'}{VERILATOR_ROOT}/include/verilated_fst_c.cpp" )
            set(Vtb_CLASSES_FAST "/work/tb/obj/Vtb.cpp" )
            set(Vtb_CLASSES_SLOW )
            set(Vtb_SUPPORT_SLOW "/work/tb/obj/Vtb__Syms.cpp" )
            set(Vtb_USER_CLASSES )
            set(Vtb_USER_LDLIBS -lm )
        """.trimIndent()
        val manifest = VerilatorManifest.parseCmake(cmake, "Vtb", directory, root)
        assertEquals(listOf(root.resolve("include/verilated.cpp"), root.resolve("include/verilated_fst_c.cpp")), manifest.global)
        assertEquals(listOf(Path.of("/work/tb/obj/Vtb.cpp").toAbsolutePath(), Path.of("/work/tb/obj/Vtb__Syms.cpp").toAbsolutePath()), manifest.generated)
        assertTrue(manifest.timing)
        assertTrue(manifest.traceFst)
        assertFalse(manifest.traceVcd)
        assertEquals(listOf("-lm"), manifest.ldflags)
    }

    @Test
    fun cmakeTraceBeforeTraceVcdMeantVcd() {
        val manifest = VerilatorManifest.parseCmake("set(Vtb_TRACE 1)\nset(Vtb_TRACE_FST 0)\n", "Vtb", directory, root)
        assertTrue(manifest.traceVcd)
        assertFalse(manifest.traceFst)
    }
}
