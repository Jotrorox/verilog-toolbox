package com.jotrorox.verilogtoolbox

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Links the `file:line` locations in Icarus output to the source, as in `tb.v:12: syntax error` from
 * iverilog and `ERROR: tb.v:20: ...` or `tb.v:31: $finish called` from vvp.
 */
class IcarusConsoleFilter(private val project: Project, private val workingDirectory: Path) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = locations(line, ::resolve).mapNotNull { location ->
            val file = LocalFileSystem.getInstance().findFileByNioFile(location.file) ?: return@mapNotNull null
            Filter.ResultItem(
                lineStart + location.start, lineStart + location.end,
                OpenFileHyperlinkInfo(project, file, location.line - 1),
            )
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun resolve(path: String): Path? =
        try {
            workingDirectory.resolve(path).normalize().takeIf { Files.isRegularFile(it) }
        } catch (_: InvalidPathException) {
            null
        }

    /** A `file:line` reference covering [start] until [end] of a console line; [line] is one-based. */
    data class Location(val start: Int, val end: Int, val file: Path, val line: Int)

    companion object {
        private const val MAX_LINE_LENGTH = 2000
        private val LINE_NUMBER = Regex(""":(\d{1,9})(?![\w.])""")

        /**
         * Finds the locations in [text]. Icarus does not quote file names, so the text in front of each
         * `:<line>` is tried as a path, dropping leading words until [resolve] knows the file.
         */
        internal fun locations(text: String, resolve: (String) -> Path?): List<Location> {
            if (text.length > MAX_LINE_LENGTH) return emptyList()
            val locations = mutableListOf<Location>()
            var searchStart = 0
            for (match in LINE_NUMBER.findAll(text)) {
                var start = searchStart
                while (start < match.range.first) {
                    if (!text[start].isWhitespace()) {
                        val file = resolve(text.substring(start, match.range.first))
                        if (file != null) {
                            locations += Location(start, match.range.last + 1, file, match.groupValues[1].toInt())
                            break
                        }
                    }
                    // Next candidate: the text after the following space.
                    val space = text.indexOf(' ', start)
                    if (space < 0 || space >= match.range.first) break
                    start = space + 1
                }
                searchStart = match.range.last + 1
            }
            return locations
        }
    }
}
