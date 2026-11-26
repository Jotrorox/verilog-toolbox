package com.jotrorox.verilogtoolbox.sby

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Path

/**
 * Takes a task of the test tree to its line in `[tasks]`. A task is `sby-task://<file>#<task>`, a run of a
 * file without tasks `sby-task://<file>#`; the result remembered under the URL shows in the gutter.
 */
object SbyTestLocator : SMTestLocator, DumbAware {
    private const val PROTOCOL = "sby-task"

    fun url(file: Path, task: String?): String = url(file.toAbsolutePath().normalize().toString(), task)

    fun url(path: String, task: String?): String = "$PROTOCOL://${FileUtil.toSystemIndependentName(path)}#${task.orEmpty()}"

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val task = path.substringAfterLast('#')
        val file = LocalFileSystem.getInstance().findFileByPath(path.substringBeforeLast('#'))
            ?.let { PsiManager.getInstance(project).findFile(it) } ?: return emptyList()
        val element = SbyConfig.tasks(file.text).firstOrNull { it.name == task }?.let { file.findElementAt(it.offset) }
        return listOf(PsiLocation(element ?: file))
    }
}
