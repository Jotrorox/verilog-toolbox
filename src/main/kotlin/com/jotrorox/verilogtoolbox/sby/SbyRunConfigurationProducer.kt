package com.jotrorox.verilogtoolbox.sby

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
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType

/**
 * Creates a SymbiYosys run configuration from a `.sby` file: for one task from its line in `[tasks]`, for
 * the default tasks from anywhere else in the file or from the project view.
 */
class SbyRunConfigurationProducer : LazyRunConfigurationProducer<SbyRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(SbyConfigurationType::class.java)

    override fun setupConfigurationFromContext(
        configuration: SbyRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val (path, task) = target(context) ?: return false
        configuration.settings.sbyFile = path
        configuration.settings.tasks = task.orEmpty()
        val name = path.replace('\\', '/').substringAfterLast('/').removeSuffix(".sby")
        configuration.name = if (task == null) name else "$name: $task"
        return true
    }

    override fun isConfigurationFromContext(configuration: SbyRunConfiguration, context: ConfigurationContext): Boolean {
        val (path, task) = target(context) ?: return false
        return FileUtil.pathsEqual(configuration.settings.sbyFile, path) && configuration.settings.tasks.orEmpty() == task.orEmpty()
    }

    /** The `.sby` file the context is in, and the task whose name it is on, if it is on one. */
    private fun target(context: ConfigurationContext): Pair<String, String?>? {
        val element = context.psiLocation ?: return null
        val file = element.containingFile as? SbyFile ?: return null
        val virtualFile = file.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        return FileUtil.toSystemDependentName(virtualFile.path) to taskName(element)
    }
}

/** The task [element] declares in `[tasks]`, if it is the name of one. */
internal fun taskName(element: PsiElement): String? =
    element.takeIf { it.elementType == SbyTokenTypes.TASK }?.text?.takeIf { name -> name.none { it in "?*.[]|()" } }

/**
 * A run icon next to every task of `[tasks]`, which tells how its last run ended, and one on the `[tasks]`
 * header, or the file's first section when it has no tasks, for running the file's default tasks.
 */
class SbyRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        val task = taskName(element)
        if (task != null) {
            val path = element.containingFile.virtualFile?.path ?: return null
            val icon = getTestStateIcon(SbyTestLocator.url(path, task), element.project, false)
            return Info(icon ?: AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run task '$task'" }
        }
        if (element.elementType != SbyTokenTypes.SECTION || !runsFile(element)) return null
        return Info(AllIcons.RunConfigurations.TestState.Run_run, ExecutorAction.getActions(0)) { "Run the default tasks" }
    }

    private fun runsFile(section: PsiElement): Boolean {
        if (SbySection.of(section.text) == SbySection.TASKS) return true
        val hasTasks = TASKS_HEADER.containsMatchIn(section.containingFile.text)
        if (hasTasks) return false
        var previous = section.prevSibling
        while (previous != null) {
            if (previous.elementType == SbyTokenTypes.SECTION) return false
            previous = previous.prevSibling
        }
        return true
    }

    private companion object {
        val TASKS_HEADER = Regex("""(?m)^\s*\[tasks]\s*$""")
    }
}
