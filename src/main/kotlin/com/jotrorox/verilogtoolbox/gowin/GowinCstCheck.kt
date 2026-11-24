package com.jotrorox.verilogtoolbox.gowin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Checks a `.cst` file against the ports of the design's top module, so that a misspelt port or a port
 * without a pin shows up in the editor rather than minutes later as a failed build.
 */
object GowinCstCheck {
    /** A single packed dimension of plain numbers, `[7:0]` or `[0:7]`. */
    private val NUMERIC_RANGE = Regex("""\[\s*(\d+)\s*:\s*(\d+)\s*]""")

    data class Problem(
        val message: String,
        /** Where the quoted port name stands in the text, or null for a problem with the whole file. */
        val range: IntRange?,
    )

    /**
     * The problems of [text] for the ports of [top], which is declared in [topFile] (as shown in messages):
     * names that are no port or no bit of a port, and ports that have no `IO_LOC`.
     */
    fun problems(text: CharSequence, top: VerilogModuleHeaders.Module, topFile: String): List<Problem> {
        val ofTop = "top module '${top.name}' ($topFile)"
        val ports = top.ports.associateBy { it.name }
        val constraints = GowinCst.portConstraints(text)
        // A file that places none of the top's ports is for another design: one warning says so, not one per line.
        if (constraints.isNotEmpty() && constraints.none { it.port in ports }) {
            return listOf(Problem("None of the ports in this file is a port of $ofTop. Is the file for another design?", null))
        }
        val problems = mutableListOf<Problem>()

        for (constraint in constraints) {
            val port = ports[constraint.port]
            val bit = constraint.bit
            val message = when {
                port == null -> "'${constraint.port}' is not a port of $ofTop."
                bit == null -> null
                isScalar(port) -> "'${constraint.name}' has a bit index, but port '${port.name}' of $ofTop is a single bit."
                bits(port)?.contains(bit) == false -> "'${constraint.name}' is out of range: port '${port.name}' of $ofTop is ${port.packed}."
                else -> null
            }
            if (message != null) problems += Problem(message, constraint.nameRange)
        }

        val located = constraints.filter { it.keyword == "IO_LOC" }
        val wholePorts = located.filter { it.bit == null }.map { it.port }.toSet()
        val locatedBits = located.filter { it.bit != null }.groupBy({ it.port }, { it.bit!! })
        val missing = top.ports.flatMap { port ->
            val bits = bits(port)
            when {
                port.name in wholePorts -> emptyList()
                // A vector with a numeric range needs every bit placed; one with none placed is named as a whole.
                bits != null -> {
                    val placed = locatedBits[port.name].orEmpty().toSet()
                    if (placed.isEmpty()) listOf(port.name) else bits.filter { it !in placed }.map { "${port.name}[$it]" }
                }
                // Otherwise the bits are unknown, so any pin for the port counts.
                port.name in locatedBits -> emptyList()
                else -> listOf(port.name)
            }
        }
        if (missing.isNotEmpty()) {
            problems += Problem("No pin for ${missing.joinToString(", ")}: these ports of $ofTop have no IO_LOC.", null)
        }
        return problems
    }

    /** A plain single-bit port; interface ports and ports of other types have a [VerilogModuleHeaders.Port.dataType]. */
    private fun isScalar(port: VerilogModuleHeaders.Port): Boolean =
        port.dataType == null && port.packed.isEmpty() && port.unpacked.isEmpty()

    /** The bits of a plain vector port whose range is written in numbers; null for any other port. */
    private fun bits(port: VerilogModuleHeaders.Port): IntRange? {
        if (port.dataType != null || port.unpacked.isNotEmpty()) return null
        val match = NUMERIC_RANGE.matchEntire(port.packed) ?: return null
        val (left, right) = match.destructured.toList().map { it.toIntOrNull() ?: return null }
        return minOf(left, right)..maxOf(left, right)
    }
}

/**
 * Runs [GowinCstCheck] on the open `.cst` file. The top module is found among the project's sources on
 * disk, so the work is done in the background; the constraints are taken from the editor, saved or not.
 */
class GowinCstExternalAnnotator : ExternalAnnotator<GowinCstExternalAnnotator.Input, List<GowinCstCheck.Problem>>() {
    class Input(
        val text: CharSequence,
        /** The directory whose sources make up the design. */
        val base: Path,
        /** The `.cst` file on disk, which helps choose the top module; null for one that is not on disk. */
        val constraints: Path?,
    )

    override fun collectInformation(file: PsiFile): Input? {
        val text = file.viewProvider.document?.immutableCharSequence ?: return null
        val constraints = file.virtualFile?.let { it.fileSystem.getNioPath(it) }?.toAbsolutePath()?.normalize()
        val projectDirectory = file.project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        // The design is the project's, unless the constraint file lies outside of the project.
        val base = when {
            constraints != null && (projectDirectory == null || !constraints.startsWith(projectDirectory)) -> constraints.parent
            else -> projectDirectory
        } ?: return null
        return Input(text, base, constraints)
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? = collectInformation(file)

    override fun doAnnotate(input: Input): List<GowinCstCheck.Problem>? {
        val sources = GowinProject.sources(input.base)
        ProgressManager.checkCanceled()
        // Without a top module there is nothing to check against.
        val topName = GowinProject.topModule(sources, input.constraints) ?: return null
        for (source in sources) {
            val modules = runCatching { VerilogModuleHeaders.modules(Files.readString(source)) }.getOrDefault(emptyList())
            val top = modules.firstOrNull { it.name == topName && it.ports.isNotEmpty() } ?: continue
            val shown = if (source.startsWith(input.base)) input.base.relativize(source) else source
            return GowinCstCheck.problems(input.text, top, shown.invariantSeparatorsPathString)
        }
        return null
    }

    override fun apply(file: PsiFile, problems: List<GowinCstCheck.Problem>?, holder: AnnotationHolder) {
        val length = file.textLength
        for (problem in problems.orEmpty()) {
            val builder = holder.newAnnotation(HighlightSeverity.WARNING, problem.message)
            val range = problem.range
            if (range == null) {
                builder.fileLevel().create()
            } else {
                val start = minOf(range.first, length)
                builder.range(TextRange(start, minOf(maxOf(range.last + 1, start), length))).create()
            }
        }
    }
}
