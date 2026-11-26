package com.jotrorox.verilogtoolbox.verilator

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.jotrorox.verilogtoolbox.icarus.VerilogTestbenches
import com.jotrorox.verilogtoolbox.settings.TestbenchSimulator
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.verilog.VerilogFile
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Creates a Verilator run configuration from a C++ testbench, a C++ file that includes a Verilated model, and,
 * when Verilator is the simulator chosen in the settings, from a Verilog testbench or a directory of them, which
 * otherwise go to Icarus Verilog. A configuration that exists is found for its context whatever the choice.
 */
class VerilatorRunConfigurationProducer : LazyRunConfigurationProducer<VerilatorRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(VerilatorConfigurationType::class.java)

    private val verilatorChosen: Boolean
        get() = ToolchainSettings.getInstance().testbenchSimulator == TestbenchSimulator.VERILATOR

    override fun setupConfigurationFromContext(
        configuration: VerilatorRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        cppTestbenchAt(context)?.let { (path, module) ->
            configuration.settings.testbench = path.toString()
            configuration.settings.topModule = module
            configuration.name = path.nameWithoutExtension
            return true
        }
        if (!verilatorChosen) return false
        directoryAt(context)?.let { directory ->
            if (VerilogTestbenches.inDirectory(directory).none()) return false
            configuration.settings.directory = directory.toString()
            configuration.name = "Testbenches in ${directory.name}"
            return true
        }
        val (path, testbench) = testbenchAt(context) ?: return false
        configuration.settings.testbench = path
        configuration.settings.topModule = testbench.name
        configuration.name = testbench.name
        return true
    }

    override fun isConfigurationFromContext(configuration: VerilatorRunConfiguration, context: ConfigurationContext): Boolean {
        val settings = configuration.settings
        directoryAt(context)?.let { return FileUtil.pathsEqual(settings.directory, it.toString()) }
        if (configuration.testbenchDirectory != null) return false
        cppTestbenchAt(context)?.let { (path, _) -> return FileUtil.pathsEqual(settings.testbench, path.toString()) }
        val (path, testbench) = testbenchAt(context) ?: return false
        return FileUtil.pathsEqual(settings.testbench, path) && settings.topModule == testbench.name
    }

    private fun directoryAt(context: ConfigurationContext): Path? =
        (context.psiLocation as? PsiDirectory)?.virtualFile?.takeIf { it.isInLocalFileSystem }?.toNioPath()

    /** The Verilog testbench the context points at: the one declared last before the caret, else the file's first. */
    private fun testbenchAt(context: ConfigurationContext): Pair<String, VerilogTestbenches.Module>? {
        val element = context.psiLocation ?: return null
        val file = element.containingFile as? VerilogFile ?: return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val testbenches = VerilogTestbenches.testbenches(file.text)
        val offset = if (element === file) 0 else element.textRange.endOffset
        val testbench = testbenches.lastOrNull { it.nameOffset < offset } ?: testbenches.firstOrNull() ?: return null
        return FileUtil.toSystemDependentName(virtualFile.path) to testbench
    }

    /** A C++ file of the context and the module whose model it includes. */
    private fun cppTestbenchAt(context: ConfigurationContext): Pair<Path, String>? {
        val file = context.psiLocation?.containingFile ?: return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem && !it.isDirectory } ?: return null
        val path = virtualFile.toNioPath()
        if (!VerilatorRunConfiguration.isCpp(path)) return null
        return VerilatorRunConfiguration.modelIncluded(file.text)?.let { path to it }
    }
}
