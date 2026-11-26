package com.jotrorox.verilogtoolbox.testbench

import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Direction
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Module
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders.Port

/**
 * Writes a C++ testbench of a module for Verilator: a `main()` that makes the module's Verilated model, dumps a
 * waveform, drives the clock and reset it recognises by their names and checks what the user adds. A failed
 * check is printed as Verilator prints a failed assertion, so that the run's test tree shows it with its place.
 */
object VerilatorCppTestbenchGenerator {
    /** The line the user's own code goes below; the editor opens there. */
    const val STIMULUS_MARKER = "// TODO: add stimulus and checks here."

    /** Half a clock period in time units of the waveform, as in the Verilog testbench's `always #5`. */
    private const val HALF_PERIOD = 5

    /** `_tb.cpp`, like the Verilog testbench but for its extension; the run configuration tells them apart. */
    fun fileName(module: Module): String = plain(module.name) + "_tb.cpp"

    fun generate(module: Module): String {
        val name = plain(module.name)
        val model = "V$name"
        // Verilator names a port of the model as the port; an escaped name it mangles, which is left to the user.
        val ports = module.ports.filter { SIMPLE_IDENTIFIER.matches(it.name) }
        val clocks = ports.filter { it.isScalarInput() && VerilogTestbenchGenerator.isClock(it.name) }
        val resets = ports.filter {
            it.isScalarInput() && it !in clocks && VerilogTestbenchGenerator.resetIsActiveLow(it.name) != null
        }
        val clock = clocks.firstOrNull()?.name
        val skipped = module.ports.filter { it !in ports }

        val lines = mutableListOf<String>()
        lines += "// C++ testbench for ${module.name}, simulated by Verilator. Run it from the context menu of this file:"
        lines += "// the design is verilated and built with this file by CMake. Load CMake Project in CLion, in the"
        lines += "// toolbar of the run, gives the simulation CLion's debugger."
        lines += "#include <cstdio>"
        lines += "#include <memory>"
        lines += ""
        lines += "#include <verilated.h>"
        lines += "#include <verilated_vcd_c.h>"
        lines += ""
        lines += "#include \"$model.h\""
        lines += ""
        lines += "namespace {"
        lines += "int failures = 0;"
        lines += "}"
        lines += ""
        lines += "// A failed check is printed as Verilator prints a failed assertion, which the run's test tree shows."
        lines += "#define CHECK(condition)                                                                      \\"
        lines += "    do {                                                                                      \\"
        lines += "        if (!(condition)) {                                                                   \\"
        lines += "            std::printf(\"%%Error: %s:%d: check failed: %s\\n\", __FILE__, __LINE__, #condition); \\"
        lines += "            ++failures;                                                                       \\"
        lines += "        }                                                                                     \\"
        lines += "    } while (0)"
        lines += ""
        lines += "int main(int argc, char** argv) {"
        lines += "    const auto context = std::make_unique<VerilatedContext>();"
        lines += "    context->commandArgs(argc, argv);"
        lines += "    context->traceEverOn(true);"
        lines += "    const auto top = std::make_unique<$model>(context.get());"
        skipped.forEach { lines += "    // TODO: ${it.name} is not driven from here; Verilator names it otherwise in $model.h." }
        lines += ""
        lines += "    // Written where the simulation runs, build/sim/<testbench> of the project."
        lines += "    const auto trace = std::make_unique<VerilatedVcdC>();"
        lines += "    top->trace(trace.get(), 99);"
        lines += "    trace->open(\"${name}_tb.vcd\");"
        lines += ""
        lines += "    // Evaluates the design, dumps it and lets ${if (clock != null) "half a period of $clock" else "$HALF_PERIOD time units"} pass."
        lines += "    const auto step = [&] {"
        lines += "        top->eval();"
        lines += "        trace->dump(context->time());"
        lines += "        context->timeInc($HALF_PERIOD);"
        lines += "    };"
        if (clock != null) {
            lines += "    // One period of $clock: the design sees the rising edge, the testbench changes inputs after the falling one."
            lines += "    const auto tick = [&] {"
            for (port in clocks) lines += "        top->${port.name} = 1;"
            lines += "        step();"
            for (port in clocks) lines += "        top->${port.name} = 0;"
            lines += "        step();"
            lines += "    };"
        }
        lines += ""
        for (port in clocks) lines += "    top->${port.name} = 0;"
        for (port in resets) lines += "    top->${port.name} = ${if (VerilogTestbenchGenerator.resetIsActiveLow(port.name) == true) 0 else 1};"
        if (resets.isNotEmpty()) {
            lines += if (clock != null) "    for (int i = 0; i < 4; ++i) tick();" else "    for (int i = 0; i < 4; ++i) step();"
            for (port in resets) lines += "    top->${port.name} = ${if (VerilogTestbenchGenerator.resetIsActiveLow(port.name) == true) 1 else 0};"
        } else {
            lines += "    step();"
        }
        lines += ""
        lines += "    $STIMULUS_MARKER"
        val input = ports.firstOrNull { it.direction == Direction.INPUT && it !in clocks && it !in resets }?.name ?: "<input>"
        val output = ports.firstOrNull { it.direction == Direction.OUTPUT }?.name ?: "<output>"
        lines += "    //     top->$input = 1;"
        lines += if (clock != null) "    //     tick();" else "    //     step();"
        lines += "    //     CHECK(top->$output == 1);"
        lines += ""
        lines += if (clock != null) {
            "    for (int i = 0; i < 20 && !context->gotFinish(); ++i) tick();"
        } else {
            "    for (int i = 0; i < 20 && !context->gotFinish(); ++i) step();"
        }
        lines += ""
        lines += "    top->final();"
        lines += "    trace->close();"
        lines += "    return failures == 0 ? 0 : 1;"
        lines += "}"
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun Port.isScalarInput(): Boolean =
        direction == Direction.INPUT && dataType == null && packed.isEmpty() && unpacked.isEmpty()

    private fun plain(name: String): String = name.removePrefix("\\").replace(Regex("[^A-Za-z0-9_]"), "_")

    private val SIMPLE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
}
