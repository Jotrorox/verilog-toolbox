package com.jotrorox.verilogtoolbox.icarus

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
 * Takes a node of the test tree to its source. A testbench is `verilog-testbench://<file>#<module>`:
 * naming the module, not its line, keeps the URL the same while the file is edited, so the result
 * remembered under it can be shown in the gutter. A suite is the `file://` URL of its source.
 */
object VerilogTestLocator : SMTestLocator, DumbAware {
    private const val PROTOCOL = "verilog-testbench"
    private const val FILE_PROTOCOL = "file"

    fun url(file: Path, module: String): String = url(file.toAbsolutePath().normalize().toString(), module)

    fun url(path: String, module: String): String = "$PROTOCOL://${FileUtil.toSystemIndependentName(path)}#$module"

    fun fileUrl(file: Path): String =
        "$FILE_PROTOCOL://${FileUtil.toSystemIndependentName(file.toAbsolutePath().normalize().toString())}"

    override fun getLocation(
        protocol: String, path: String, project: Project, scope: GlobalSearchScope,
    ): List<Location<*>> {
        val module = if (protocol == PROTOCOL) path.substringAfterLast('#') else null
        if (module == null && protocol != FILE_PROTOCOL) return emptyList()
        val file = LocalFileSystem.getInstance().findFileByPath(if (module == null) path else path.substringBeforeLast('#'))
            ?.let { PsiManager.getInstance(project).findFile(it) } ?: return emptyList()
        val declaration = module?.let { name ->
            VerilogTestbenches.modules(file.text).firstOrNull { it.name == name }?.let { file.findElementAt(it.nameOffset) }
        }
        return listOf(PsiLocation(declaration ?: file))
    }
}
