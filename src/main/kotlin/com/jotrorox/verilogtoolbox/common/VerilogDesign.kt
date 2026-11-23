package com.jotrorox.verilogtoolbox.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.jotrorox.verilogtoolbox.lsp.SlangDesign
import com.jotrorox.verilogtoolbox.verilog.VerilogFileType
import java.nio.file.Path

/**
 * The design a tool checks a file in, for the tools that look at more than a file: Verilator's lint and
 * Yosys's synthesis. It is the project's design file list when one is selected, so that they see what
 * slang-server and Icarus runs see, and otherwise the file itself with every project directory to find
 * the modules and headers it uses, as Icarus runs without a file list do.
 */
object VerilogDesign {
    /** slang looks library modules up as `<module>.v` and `<module>.sv`; the other tools are told to do so too. */
    val LIBRARY_EXTENSIONS = listOf(".v", ".sv")

    private val DIRECTORIES = Key.create<CachedValue<List<Path>>>("verilogToolbox.verilogDirectories")

    /**
     * Every project directory with Verilog files, searched for the modules and headers a source refers
     * to. Needs a read action and the indexes.
     */
    fun libraryDirectories(project: Project): List<Path> =
        CachedValuesManager.getManager(project).getCachedValue(project, DIRECTORIES, {
            val directories = FileTypeIndex.getFiles(VerilogFileType, GlobalSearchScope.projectScope(project))
                .mapNotNull { it.parent?.takeIf { parent -> parent.isInLocalFileSystem }?.toNioPath() }
                .distinct().sorted()
            CachedValueProvider.Result.create(
                directories,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootModificationTracker.getInstance(project),
            )
        }, false)

    /** [file] on its own, with [directories] to search for what it uses. */
    fun standalone(file: Path, directories: List<Path>): FileList {
        val absolute = file.toAbsolutePath().normalize()
        val own = listOfNotNull(absolute.parent)
        return FileList(
            sources = listOf(absolute),
            includeDirectories = (own + directories).distinct(),
            defines = emptyList(),
            libraryDirectories = (own + directories).distinct(),
            libraryFiles = emptyList(),
            libraryExtensions = LIBRARY_EXTENSIONS,
            topModules = emptyList(),
            ignored = emptyList(),
            errors = emptyList(),
        )
    }

    /**
     * The design [file] is checked in: the project's design file list, with [file] added when the list
     * leaves it out, or [file] on its own. A list that cannot be read comes back with its [FileList.errors].
     * [directories] are those of [libraryDirectories], looked up beforehand because this reads files and
     * should not run in a read action.
     */
    fun forFile(project: Project, file: Path, directories: List<Path>): FileList {
        val list = SlangDesign.getInstance(project).fileList ?: return standalone(file, directories)
        val design = FileList.parse(list, FileList.baseDirectory(list, project.basePath?.let { Path.of(it) }))
        val absolute = file.toAbsolutePath().normalize()
        return if (absolute in design.sources) design else design.copy(sources = design.sources + absolute)
    }
}
