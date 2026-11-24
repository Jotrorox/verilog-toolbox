package com.jotrorox.verilogtoolbox.verilator

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.NioFiles
import com.intellij.psi.PsiFile
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verilator's lint as a second source of diagnostics next to slang-server. Verilator elaborates the
 * design and so reports what a single-file parser cannot, such as width mismatches, incomplete cases,
 * inferred latches and multiple drivers.
 *
 * The text of the editor is linted, saved or not: it is written to a temporary copy that takes the
 * place of the file in the design.
 */
object VerilatorLint {
    private const val TIMEOUT_MILLIS = 30_000

    /** Extensions of the files that are linted; headers only make sense where they are included. */
    private val LINTED_EXTENSIONS = setOf("v", "sv")

    /**
     * Always passed: lint only, report warnings without failing, and do not warn that a file with several
     * modules has several tops, which is what linting a file on its own makes of it.
     */
    private val FIXED_OPTIONS = listOf("--lint-only", "-Wno-fatal", "-Wno-MULTITOP")

    /** `%Warning-WIDTHTRUNC: /path/file.sv:3:12: message` or `%Error: file.sv:6:5: message`. */
    private val MESSAGE = Regex("""^%(Error|Warning)(?:-([A-Za-z0-9_]+))?: (.*)$""")

    /** The location at the start of a message, up to the last `:<line>:<column>: ` so that paths may hold colons. */
    private val LOCATION = Regex("""^(.+?):(\d+):(\d+): (.*)$""")

    /** The source line Verilator prints under a message, `   12 |   assign y = a;`. */
    private val SOURCE_LINE = Regex("""^\s*(\d+) \| """)

    /** The marker under it, `      |        ^~~~~`, whose length is that of the reported code. */
    private val MARKER_LINE = Regex("""^\s*\| *(\^~*)""")

    /** Messages that only say that Verilator stopped, which the messages before them explain. */
    private val CONSEQUENCES = listOf("Exiting due to", "Cannot continue")

    data class Problem(
        val severity: HighlightSeverity,
        val message: String,
        /** Where in the linted text, or null for a problem with the run itself. */
        val range: TextRange?,
    )

    /** A parse error, which slang-server reports at the same place with a better message. */
    fun isSyntaxError(problem: Problem): Boolean = problem.message.startsWith("syntax error")

    fun isLinted(file: Path): Boolean = file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() in LINTED_EXTENSIONS

    /**
     * The arguments that lint [design] with [copy] in place of [original]. The original's directory is
     * searched for headers first, as it would be for the file itself.
     */
    internal fun arguments(design: FileList, original: Path, copy: Path, options: String): List<String> = buildList {
        addAll(FIXED_OPTIONS)
        addAll(ParametersListUtil.parse(options))
        val originalPath = original.toAbsolutePath().normalize()
        for (directory in (listOfNotNull(originalPath.parent) + design.includeDirectories).distinct()) add("-I$directory")
        for (define in design.defines) add("-D$define")
        for (directory in design.libraryDirectories) addAll(listOf("-y", directory.toString()))
        if (design.libraryDirectories.isNotEmpty() || design.libraryFiles.isNotEmpty()) {
            add("+libext+" + (VerilogDesign.LIBRARY_EXTENSIONS + design.libraryExtensions).distinct().joinToString("+"))
        }
        for (file in design.libraryFiles) addAll(listOf("-v", file.toString()))
        // No --top: a top that leaves the file out would keep it from being linted.
        for (source in design.sources) add(if (source == originalPath) copy.toString() else source.toString())
    }

    /**
     * The problems Verilator reports in [output] for [file], the path it was given. [text] is the linted
     * text, for turning lines and columns into offsets. Problems in other files are left out; a problem
     * with no place, such as an invalid option, comes back without a range.
     */
    internal fun parse(output: String, file: Path, text: CharSequence): List<Problem> {
        val lines = output.lines()
        val problems = mutableListOf<Problem>()
        for ((index, line) in lines.withIndex()) {
            val match = MESSAGE.matchEntire(line) ?: continue
            val (kind, code, rest) = match.destructured
            val severity = if (kind == "Error") HighlightSeverity.ERROR else HighlightSeverity.WARNING
            val location = LOCATION.matchEntire(rest)
            if (location == null) {
                if (CONSEQUENCES.none { rest.startsWith(it) }) problems += Problem(severity, describe(rest, code), null)
                continue
            }
            val (path, lineText, columnText, message) = location.destructured
            if (!samePath(path, file)) continue
            val lineNumber = lineText.toIntOrNull() ?: continue
            val start = offset(text, lineNumber, columnText.toIntOrNull() ?: 1) ?: continue
            val length = markerLength(lines, index + 1, lineNumber)
            val lineEnd = lineEnd(text, start)
            val end = if (length != null) minOf(start + length, lineEnd) else wordEnd(text, start, lineEnd)
            problems += Problem(severity, describe(message, code), TextRange(start, maxOf(end, start)))
        }
        return problems
    }

    private fun describe(message: String, code: String): String =
        if (code.isEmpty()) "$message (Verilator)" else "$message (Verilator $code)"

    private fun samePath(reported: String, file: Path): Boolean =
        try {
            Path.of(reported).toAbsolutePath().normalize() == file.toAbsolutePath().normalize()
        } catch (_: Exception) {
            false
        }

    /**
     * The length of the code Verilator marks for the message whose lines follow [from]: the marker under
     * the source line [lineNumber], when it comes before any other place the message names.
     */
    private fun markerLength(lines: List<String>, from: Int, lineNumber: Int): Int? {
        for (i in from until lines.size) {
            val line = lines[i]
            if (line.startsWith("%") || line.startsWith("- ")) return null
            val source = SOURCE_LINE.find(line)
            if (source != null) {
                if (source.groupValues[1].toIntOrNull() != lineNumber) return null
                return MARKER_LINE.find(lines.getOrNull(i + 1).orEmpty())?.groupValues?.get(1)?.length
            }
            // A note that names a place of its own comes with that place's source line.
            if (LOCATION.containsMatchIn(line.trim().removePrefix(": "))) return null
        }
        return null
    }

    /** The offset of one-based [line] and [column] in [text], kept within that line. */
    private fun offset(text: CharSequence, line: Int, column: Int): Int? {
        var start = 0
        repeat(line - 1) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) return null
            start = newline + 1
        }
        return minOf(start + maxOf(column - 1, 0), lineEnd(text, start))
    }

    private fun lineEnd(text: CharSequence, from: Int): Int =
        text.indexOf('\n', from).let { if (it < 0) text.length else it }.let { if (it > from && text[it - 1] == '\r') it - 1 else it }

    /** Without a marker the word at [start] is highlighted, or the rest of the line if there is none. */
    private fun wordEnd(text: CharSequence, start: Int, lineEnd: Int): Int {
        var end = start
        while (end < lineEnd && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
        return if (end > start) end else lineEnd
    }

    /** Lints [text], the content of [file], in [design]. Blocking. */
    @Throws(IOException::class, ExecutionException::class)
    fun lint(verilator: Path, file: Path, text: CharSequence, design: FileList, options: String, workingDirectory: Path?): List<Problem> {
        val directory = Files.createTempDirectory("verilator-lint")
        try {
            // Named as the original, so that what Verilator says about file names still holds.
            val copy = directory.resolve(file.fileName.toString())
            Files.writeString(copy, text)
            val commandLine: GeneralCommandLine = ToolchainSettings.toolCommandLine(verilator)
                .withParameters(arguments(design, file, copy, options))
                .withWorkingDirectory(workingDirectory?.takeIf { Files.isDirectory(it) })
            val indicator = ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator()
            val output = CapturingProcessHandler(commandLine).runProcessWithProgressIndicator(indicator, TIMEOUT_MILLIS)
            if (output.isCancelled || output.isTimeout) return emptyList()
            return parse(output.stderr + "\n" + output.stdout, copy, text)
        } finally {
            NioFiles.deleteRecursively(directory)
        }
    }
}

/** Runs [VerilatorLint] on the open Verilog file whenever the editor's highlighting is updated. */
class VerilatorExternalAnnotator : ExternalAnnotator<VerilatorExternalAnnotator.Input, List<VerilatorLint.Problem>>() {
    class Input(
        val project: Project,
        val verilator: Path,
        val file: Path,
        val text: CharSequence,
        val directories: List<Path>,
        val options: String,
        /** Whether slang-server reports syntax errors already, and better. */
        val slangServerRuns: Boolean,
    )

    override fun collectInformation(file: PsiFile): Input? {
        val settings = ToolchainSettings.getInstance()
        if (!settings.verilatorLintEnabled) return null
        // Only a file on disk can be linted: Verilator reads the rest of the design from there.
        val virtualFile = file.virtualFile ?: return null
        val path = virtualFile.fileSystem.getNioPath(virtualFile) ?: return null
        if (!VerilatorLint.isLinted(path)) return null
        val verilator = settings.findVerilator() ?: return null
        val text = file.viewProvider.document?.immutableCharSequence ?: return null
        return Input(
            file.project, verilator, path, text, VerilogDesign.libraryDirectories(file.project), settings.verilatorOptions,
            slangServerRuns = settings.findSlangServer() != null,
        )
    }

    override fun collectInformation(file: PsiFile, editor: com.intellij.openapi.editor.Editor, hasErrors: Boolean): Input? =
        collectInformation(file)

    override fun doAnnotate(input: Input): List<VerilatorLint.Problem>? {
        var design = VerilogDesign.forFile(input.project, input.file, input.directories)
        // A broken file list is reported by slang-server and Icarus runs; lint the file on its own meanwhile.
        if (design.errors.isNotEmpty()) design = VerilogDesign.standalone(input.file, input.directories)
        return try {
            val problems = VerilatorLint.lint(
                input.verilator, input.file, input.text, design, input.options, input.project.basePath?.let { Path.of(it) },
            )
            if (input.slangServerRuns) problems.filterNot { VerilatorLint.isSyntaxError(it) } else problems
        } catch (e: IOException) {
            LOG.warn("Verilator lint of ${input.file} failed", e)
            null
        } catch (e: ExecutionException) {
            LOG.warn("Verilator lint of ${input.file} failed", e)
            null
        }
    }

    override fun apply(file: PsiFile, problems: List<VerilatorLint.Problem>?, holder: AnnotationHolder) {
        val length = file.textLength
        for (problem in problems.orEmpty()) {
            val builder = holder.newAnnotation(problem.severity, problem.message)
            val range = problem.range
            if (range == null) {
                builder.fileLevel().create()
            } else {
                val start = minOf(range.startOffset, length)
                builder.range(TextRange(start, minOf(maxOf(range.endOffset, start), length))).create()
            }
        }
    }

    private companion object {
        val LOG = logger<VerilatorExternalAnnotator>()
    }
}
