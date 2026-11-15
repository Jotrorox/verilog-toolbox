package com.jotrorox.verilogtoolbox

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Creates an Icarus run configuration from a testbench (its gutter icon, the editor or the project
 * view) or, for all the testbenches below it, from a directory.
 */
class IcarusRunConfigurationProducer : LazyRunConfigurationProducer<IcarusRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(IcarusConfigurationType::class.java)

    override fun setupConfigurationFromContext(
        configuration: IcarusRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        directoryAt(context)?.let { directory ->
            // Reads the sources until it finds a testbench; a directory without one offers no run.
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

    override fun isConfigurationFromContext(configuration: IcarusRunConfiguration, context: ConfigurationContext): Boolean {
        val settings = configuration.settings
        directoryAt(context)?.let { return FileUtil.pathsEqual(settings.directory, it.toString()) }
        if (configuration.testbenchDirectory != null) return false
        val (path, testbench) = testbenchAt(context) ?: return false
        return FileUtil.pathsEqual(settings.testbench, path) && settings.topModule == testbench.name
    }

    private fun directoryAt(context: ConfigurationContext): Path? =
        (context.psiLocation as? PsiDirectory)?.virtualFile?.takeIf { it.isInLocalFileSystem }?.toNioPath()

    /** The testbench the context points at: the one declared last before the caret, else the file's first. */
    private fun testbenchAt(context: ConfigurationContext): Pair<String, VerilogTestbenches.Module>? {
        val element = context.psiLocation ?: return null
        val file = element.containingFile as? VerilogFile ?: return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val testbenches = VerilogTestbenches.testbenches(file.text)
        val offset = if (element === file) 0 else element.textRange.endOffset
        val testbench = testbenches.lastOrNull { it.nameOffset < offset } ?: testbenches.firstOrNull() ?: return null
        return FileUtil.toSystemDependentName(virtualFile.path) to testbench
    }
}

/** Shows a run icon next to the name of every testbench module, which tells how its last run ended. */
class IcarusRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != VerilogTokenTypes.IDENTIFIER || !followsModuleKeyword(element)) return null
        val offset = element.textRange.startOffset
        val file = element.containingFile
        if (VerilogTestbenches.testbenches(file.text).none { it.nameOffset == offset }) return null
        val icon = file.virtualFile?.let { getTestStateIcon(VerilogTestLocator.url(it.path, element.text), element.project, false) }
        return Info(icon ?: AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Simulate testbench" }
    }

    /** A cheap check that spares lexing the file for identifiers that cannot be module names. */
    private fun followsModuleKeyword(name: PsiElement): Boolean {
        var previous = name.previousCodeLeaf()
        if (previous?.elementType == VerilogTokenTypes.KEYWORD) previous = previous.previousCodeLeaf()
        return previous?.elementType == VerilogTokenTypes.MODULE
    }

    private fun PsiElement.previousCodeLeaf(): PsiElement? {
        var sibling = prevSibling
        while (sibling is PsiWhiteSpace || sibling is PsiComment) sibling = sibling.prevSibling
        return sibling
    }
}
