package com.jotrorox.verilogtoolbox.testbench

import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerilatorCppTestbenchGeneratorTest {
    private fun module(source: String) = VerilogModuleHeaders.modules(source).single()

    @Test
    fun clockedModuleWithReset() {
        val counter = module(
            "module counter(input logic clk, input logic rst_n, input logic en, output logic [3:0] count);\nendmodule\n"
        )
        assertEquals("counter_tb.cpp", VerilatorCppTestbenchGenerator.fileName(counter))
        val text = VerilatorCppTestbenchGenerator.generate(counter)
        assertTrue(text, "#include \"Vcounter.h\"" in text)
        assertTrue(text, "const auto top = std::make_unique<Vcounter>(context.get());" in text)
        assertTrue(text, "trace->open(\"counter_tb.vcd\");" in text)
        // Held in reset for four cycles, then released: rst_n is active low.
        assertTrue(text, "    top->rst_n = 0;\n    for (int i = 0; i < 4; ++i) tick();\n    top->rst_n = 1;\n" in text)
        assertTrue(text, "    //     top->en = 1;\n    //     tick();\n    //     CHECK(top->count == 1);" in text)
        assertTrue(text, "std::printf(\"%%Error: %s:%d: check failed: %s\\n\", __FILE__, __LINE__, #condition);" in text)
        assertTrue(text, "return failures == 0 ? 0 : 1;" in text)
    }

    @Test
    fun combinationalModuleSteps() {
        val text = VerilatorCppTestbenchGenerator.generate(module("module add(input [3:0] a, b, output [4:0] y);\nendmodule\n"))
        assertFalse(text, "tick" in text)
        assertTrue(text, "    step();\n\n    ${VerilatorCppTestbenchGenerator.STIMULUS_MARKER}" in text)
        assertTrue(text, "//     top->a = 1;" in text)
    }

    @Test
    fun escapedPortIsLeftToTheUser() {
        val text = VerilatorCppTestbenchGenerator.generate(module("module m(input clk, output \\q! );\nendmodule\n"))
        assertTrue(text, "// TODO: \\q! is not driven from here" in text)
    }
}
