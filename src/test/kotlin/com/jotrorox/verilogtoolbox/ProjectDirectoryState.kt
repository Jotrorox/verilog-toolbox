package com.jotrorox.verilogtoolbox

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.NioFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * Puts the project directory back the way a test found it. Simulations run in `build/sim` below it, and the
 * light project outlives the test: a directory left there is found by a later refresh of the file system,
 * which starts indexing in the middle of whatever test runs then. Highlighting does not survive that.
 */
class ProjectDirectoryState(project: Project) {
    private val directory: Path? = project.basePath?.let { Path.of(it) }
    private val existed = directory != null && Files.exists(directory)

    fun restore() {
        if (directory == null) return
        NioFiles.deleteRecursively(if (existed) directory.resolve("build") else directory)
    }
}
