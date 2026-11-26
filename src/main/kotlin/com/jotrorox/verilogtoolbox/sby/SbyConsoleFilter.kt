package com.jotrorox.verilogtoolbox.sby

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Links what SymbiYosys prints: a source location such as `counter.sv:14.46-14.68` or `counter.sv:11:` to the
 * line, and a trace such as `counterexample trace: counter_bmc\engine_0\trace.vcd` to the waveform viewer.
 * Sources are named as they are in the task's `src` directory; [resolveSource] finds the file they were copied
 * from. Traces are named from [workingDirectory], or, when an engine announces one, from the task's directory.
 */
class SbyConsoleFilter(
    private val project: Project,
    private val workingDirectory: Path,
    private val resolveSource: (String) -> Path?,
    private val openTrace: (Path) -> Unit,
) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = links(line).mapNotNull { link ->
            val info: HyperlinkInfo = when (link) {
                is Link.Source -> {
                    val file = resolveSource(link.file)?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
                        ?: return@mapNotNull null
                    OpenFileHyperlinkInfo(project, file, link.line - 1, maxOf(link.column - 1, 0))
                }
                is Link.Trace -> {
                    val trace = resolve(link.workdir?.let { workingDirectory.resolve(it) } ?: workingDirectory, link.path)
                        ?: return@mapNotNull null
                    HyperlinkInfo { openTrace(trace) }
                }
            }
            Filter.ResultItem(lineStart + link.range.first, lineStart + link.range.last + 1, info)
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun resolve(directory: Path, path: String): Path? =
        try {
            directory.resolve(path).normalize()
        } catch (_: InvalidPathException) {
            null
        }

    sealed class Link(val range: IntRange) {
        class Source(range: IntRange, val file: String, val line: Int, val column: Int) : Link(range)

        /** [path] is from the working directory, or from the directory of the task [workdir] if that is given. */
        class Trace(range: IntRange, val path: String, val workdir: String?) : Link(range)
    }

    companion object {
        private const val MAX_LINE_LENGTH = 4000

        private val LOCATION =
            Regex("""(?<![\w./\\-])([\w./\\-]+\.(?:sv|v|svh|vh|vhd|vhdl|sby)):(\d+)(?:\.(\d+))?(?:-\d+(?:\.\d+)?)?""")
        private val SUMMARY_TRACE = Regex("""(?:counterexample trace|cover trace|trace)(?: \[[^\]]+])?: (\S+\.(?:vcd|fst))\s*$""")
        private val WRITTEN_TRACE = Regex("""Writing trace to VCD file: (\S+)""")

        internal fun links(text: String): List<Link> {
            if (text.length > MAX_LINE_LENGTH) return emptyList()
            val links = mutableListOf<Link>()
            for (match in LOCATION.findAll(text)) {
                val (file, line, column) = match.destructured
                links += Link.Source(match.range, file, line.toInt(), column.toIntOrNull() ?: 1)
            }
            SUMMARY_TRACE.find(text)?.groups?.get(1)?.let { links += Link.Trace(it.range, it.value, null) }
            WRITTEN_TRACE.find(text)?.groups?.get(1)?.let { group ->
                val workdir = SbyOutput.parse(text)?.workdir
                if (workdir != null) links += Link.Trace(group.range, group.value, workdir)
            }
            return links
        }

        /**
         * The file a source name of SymbiYosys was copied from: the one `[files]` copies under that name, from
         * [workingDirectory]; else the file of that name there; else the copy in one of [workdirs].
         */
        fun resolveSource(name: String, workingDirectory: Path, files: List<SbyConfig.FileEntry>, workdirs: () -> Collection<String>): Path? {
            fun existing(path: () -> Path): Path? =
                try {
                    path().normalize().takeIf { Files.isRegularFile(it) }
                } catch (_: InvalidPathException) {
                    null
                }
            return files.firstOrNull { it.destination == name }?.let { entry -> existing { workingDirectory.resolve(entry.source) } }
                ?: existing { workingDirectory.resolve(name) }
                ?: workdirs().firstNotNullOfOrNull { workdir -> existing { workingDirectory.resolve(workdir).resolve("src").resolve(name) } }
        }
    }
}
