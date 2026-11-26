package com.jotrorox.verilogtoolbox.verilator

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.ProjectDirectoryState
import com.jotrorox.verilogtoolbox.icarus.IcarusRunConfiguration
import com.jotrorox.verilogtoolbox.icarus.IcarusRunConfigurationProducer
import com.jotrorox.verilogtoolbox.settings.TestbenchSimulator
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path

class VerilatorRunPlatformTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private lateinit var projectDirectory: ProjectDirectoryState

    override fun setUp() {
        super.setUp()
        projectDirectory = ProjectDirectoryState(project)
        directory = Files.createTempDirectory("verilator run").toRealPath()
    }

    override fun tearDown() {
        try {
            ToolchainSettings.getInstance().testbenchSimulator = TestbenchSimulator.ICARUS
            projectDirectory.restore()
            NioFiles.deleteRecursively(directory)
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun write(path: String, text: String): Path {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    private fun contextOf(path: Path): ConfigurationContext {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val manager = PsiManager.getInstance(project)
        return ConfigurationContext(if (file.isDirectory) manager.findDirectory(file)!! else manager.findFile(file)!!)
    }

    private val verilator get() = RunConfigurationProducer.getInstance(VerilatorRunConfigurationProducer::class.java)
    private val icarus get() = RunConfigurationProducer.getInstance(IcarusRunConfigurationProducer::class.java)

    fun testCppTestbenchRunsWithVerilator() {
        val file = write("sim/counter_tb.cpp", "#include <verilated.h>\n#include \"Vcounter.h\"\nint main() { return 0; }\n")
        val configuration = verilator.createConfigurationFromContext(contextOf(file))!!.configuration as VerilatorRunConfiguration
        assertEquals(file.toString(), configuration.settings.testbench)
        assertEquals("counter", configuration.resolveTopModule())
        assertEquals("counter_tb", configuration.name)
        assertTrue(configuration.isCppTestbench)
        assertEquals("counter_tb.cpp", configuration.simulationName)
        assertEquals("TOP", configuration.waveformScope())
        assertTrue(verilator.isConfigurationFromContext(configuration, contextOf(file)))

        val plain = write("sim/util.cpp", "int helper() { return 1; }\n")
        assertNull(verilator.createConfigurationFromContext(contextOf(plain)))
    }

    fun testVerilogTestbenchGoesToTheChosenSimulator() {
        val testbench = write("tb/counter_tb.sv", "module counter_tb;\n  initial \$finish;\nendmodule\n")
        assertNull(verilator.createConfigurationFromContext(contextOf(testbench)))
        assertNotNull(icarus.createConfigurationFromContext(contextOf(testbench)))
        assertNull(verilator.createConfigurationFromContext(contextOf(directory.resolve("tb"))))

        ToolchainSettings.getInstance().testbenchSimulator = TestbenchSimulator.VERILATOR
        assertNull(icarus.createConfigurationFromContext(contextOf(testbench)))
        val configuration = verilator.createConfigurationFromContext(contextOf(testbench))!!.configuration as VerilatorRunConfiguration
        assertEquals("counter_tb", configuration.settings.topModule)
        assertFalse(configuration.isCppTestbench)
        assertEquals("TOP.counter_tb", configuration.waveformScope())
        val all = verilator.createConfigurationFromContext(contextOf(directory.resolve("tb")))!!.configuration as VerilatorRunConfiguration
        assertEquals(directory.resolve("tb"), all.testbenchDirectory)

        // A configuration that exists is still found for its testbench.
        val existing = icarus.configurationFactory.createTemplateConfiguration(project) as IcarusRunConfiguration
        existing.settings.testbench = testbench.toString()
        existing.settings.topModule = "counter_tb"
        assertTrue(icarus.isConfigurationFromContext(existing, contextOf(testbench)))
    }

    fun testSimulationDirectoriesAreBelowTheProjectsBuildDirectory() {
        val testbench = write("tb/counter_tb.sv", "module counter_tb;\nendmodule\n")
        val configuration = verilator.configurationFactory.createTemplateConfiguration(project) as VerilatorRunConfiguration
        configuration.settings.testbench = testbench.toString()
        val base = Path.of(project.basePath!!)
        assertEquals(base.resolve("build/verilator"), configuration.cmakeRoot)
        assertEquals(base.resolve("build/sim/counter_tb"), configuration.simulationDirectory)
        assertEquals(testbench.parent, configuration.workingDirectory)
    }
}
