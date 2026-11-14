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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType

/** Creates an Icarus run configuration from a testbench: its gutter icon, the editor or the project view. */
class IcarusRunConfigurationProducer : LazyRunConfigurationProducer<IcarusRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(IcarusConfigurationType::class.java)

    override fun setupConfigurationFromContext(
        configuration: IcarusRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val (path, testbench) = testbenchAt(context) ?: return false
        configuration.settings.testbench = path
        configuration.settings.topModule = testbench.name
        configuration.name = testbench.name
        return true
    }

    override fun isConfigurationFromContext(configuration: IcarusRunConfiguration, context: ConfigurationContext): Boolean {
        val (path, testbench) = testbenchAt(context) ?: return false
        val settings = configuration.settings
        return FileUtil.pathsEqual(settings.testbench, path) && settings.topModule == testbench.name
    }

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

/** Shows a run icon next to the name of every testbench module. */
class IcarusRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != VerilogTokenTypes.IDENTIFIER || !followsModuleKeyword(element)) return null
        val offset = element.textRange.startOffset
        val isTestbench = VerilogTestbenches.testbenches(element.containingFile.text).any { it.nameOffset == offset }
        if (!isTestbench) return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Simulate testbench" }
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
