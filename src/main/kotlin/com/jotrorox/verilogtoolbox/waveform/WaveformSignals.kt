package com.jotrorox.verilogtoolbox.waveform

import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import com.jotrorox.verilogtoolbox.verilog.VerilogLexer
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import com.jotrorox.verilogtoolbox.verilog.VerilogTokenTypes
import com.jotrorox.verilogtoolbox.verilog.isVerilog
import java.io.IOException
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Finds a signal of the source in a waveform: the hierarchical names it has below the simulated top
 * module, such as `counter_tb.dut.count`. The instances written in the sources give the names, and the
 * header of a VCD dump, which lists every scope and variable, confirms or corrects them.
 */
object WaveformSignals {
    /** `module name(...)` written in the body of [parent]. */
    data class Instance(val parent: String, val module: String, val name: String)

    private class Token(val type: IElementType, val start: Int, val end: Int, val text: String)

    /** The identifier at [offset], or the one that ends there, as at a caret after a word. */
    fun identifierAt(source: CharSequence, offset: Int): String? {
        val tokens = codeTokens(source)
        val at = tokens.firstOrNull { offset >= it.start && offset < it.end }?.takeIf { it.type == VerilogTokenTypes.IDENTIFIER }
            ?: tokens.firstOrNull { it.end == offset && it.type == VerilogTokenTypes.IDENTIFIER }
        return at?.text?.takeIf { SIMPLE_IDENTIFIER.matches(it) }
    }

    /**
     * The module instances of [source], by the module they are in. Read from tokens, without
     * elaboration: an instance in a generate block counts as one of the module, and its generate scope
     * is left to the dump.
     */
    fun instances(source: CharSequence): List<Instance> {
        val tokens = codeTokens(source)
        val instances = mutableListOf<Instance>()
        var module: String? = null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token.type == VerilogTokenTypes.MODULE -> {
                    var name = i + 1
                    if (tokens.getOrNull(name)?.text in LIFETIMES) name++
                    module = tokens.getOrNull(name)?.takeIf { it.type == VerilogTokenTypes.IDENTIFIER }?.text
                    // The header's ports are not instances.
                    i = name
                    while (i < tokens.size && tokens[i].type != VerilogTokenTypes.SEMICOLON) {
                        i = if (tokens[i].type == VerilogTokenTypes.LPAREN) closing(tokens, i) + 1 else i + 1
                    }
                }
                token.type == VerilogTokenTypes.ENDMODULE -> module = null
                module != null && token.type == VerilogTokenTypes.IDENTIFIER && tokens.getOrNull(i - 1)?.text !in QUALIFIERS -> {
                    val end = instantiation(tokens, i, module, instances)
                    if (end > i) i = end
                }
            }
            i++
        }
        return instances
    }

    /**
     * Reads `type [#(...)] name [dims] (...) [, name [dims] (...)]*` at [start], adding each name to
     * [instances]. Returns the index of the last token read, or [start] if there is no instance.
     */
    private fun instantiation(tokens: List<Token>, start: Int, parent: String, instances: MutableList<Instance>): Int {
        val type = tokens[start].text
        var i = start + 1
        if (tokens.getOrNull(i)?.text == "#" && tokens.getOrNull(i + 1)?.type == VerilogTokenTypes.LPAREN) {
            i = closing(tokens, i + 1) + 1
        }
        var last = start
        while (true) {
            val name = tokens.getOrNull(i)?.takeIf { it.type == VerilogTokenTypes.IDENTIFIER } ?: return last
            var next = i + 1
            while (tokens.getOrNull(next)?.type == VerilogTokenTypes.LBRACKET) next = closing(tokens, next) + 1
            if (tokens.getOrNull(next)?.type != VerilogTokenTypes.LPAREN) return last
            instances += Instance(parent, type, name.text)
            last = closing(tokens, next)
            if (tokens.getOrNull(last + 1)?.type != VerilogTokenTypes.COMMA) return last
            i = last + 2
        }
    }

    /** The instances of every Verilog source in [directories], with [overrides] read instead of the files they name. */
    fun instancesIn(directories: Collection<Path>, overrides: Map<Path, CharSequence> = emptyMap()): List<Instance> {
        val files = directories.flatMap { directory ->
            try {
                Files.list(directory).use { entries -> entries.filter { it.isRegularFile() && it.extension.lowercase() in EXTENSIONS }.toList() }
            } catch (_: IOException) {
                emptyList()
            }
        }.map { it.toAbsolutePath().normalize() }.toSet() + overrides.keys
        return files.flatMap { file ->
            val source = overrides[file] ?: try {
                Files.readString(file)
            } catch (_: IOException) {
                return@flatMap emptyList()
            }
            instances(source)
        }.distinct()
    }

    /** The scopes [module] has below [top]: `top`, or `top.u_core.u_alu` for each chain of instances. */
    fun scopes(top: String, module: String, instances: List<Instance>): List<String> {
        val byModule = instances.groupBy { it.module }
        val found = mutableListOf<String>()
        fun walk(current: String, below: List<String>, visited: Set<String>) {
            if (found.size >= MAX_SCOPES) return
            if (current == top) {
                found += (listOf(top) + below).joinToString(".")
                return
            }
            for (instance in byModule[current].orEmpty()) {
                if (instance.parent !in visited) walk(instance.parent, listOf(instance.name) + below, visited + instance.parent)
            }
        }
        walk(module, emptyList(), setOf(module))
        return found
    }

    /**
     * The scopes of a VCD header and the variables in each, by hierarchical name. A variable is named
     * without its bit range or index. Null if [reader] does not read a VCD file.
     */
    fun vcdScopes(reader: Reader): Map<String, Set<String>>? {
        val words = Words(reader)
        val scopes = LinkedHashMap<String, MutableSet<String>>()
        val path = ArrayList<String>()
        var first = true
        while (true) {
            val word = words.next() ?: break
            if (first && !word.startsWith("$")) return null
            first = false
            when (word) {
                "\$scope" -> {
                    words.next() // The kind: module, begin, task, …
                    path += words.next() ?: break
                    scopes.getOrPut(path.joinToString(".")) { LinkedHashSet() }
                    words.skipToEnd()
                }
                "\$upscope" -> {
                    path.removeLastOrNull()
                    words.skipToEnd()
                }
                "\$var" -> {
                    val fields = words.untilEnd()
                    // $var <type> <size> <id> <reference> [<range>] $end
                    val reference = fields.getOrNull(3)?.substringBefore('[')
                    if (reference != null && path.isNotEmpty()) scopes.getOrPut(path.joinToString(".")) { LinkedHashSet() } += reference
                }
                "\$enddefinitions" -> break
                else -> if (word.startsWith("$") && word != "\$end") words.skipToEnd() else if (!word.startsWith("$")) return scopes
            }
        }
        return scopes
    }

    /** [vcdScopes] of [dump], or null if it is not a VCD file or cannot be read. */
    fun vcdScopes(dump: Path): Map<String, Set<String>>? =
        try {
            Files.newBufferedReader(dump, Charsets.ISO_8859_1).use { vcdScopes(it) }
        } catch (_: IOException) {
            null
        }

    /**
     * The hierarchical names of [signal], declared in [module], in a waveform of a simulation of [top].
     * With [dumpScopes], the scopes of the dump, only names the dump has are given: those the instances
     * lead to, else those in a scope named like an instance of [module], else any scope that has
     * [signal]. Without them the names are those the instances lead to.
     */
    fun candidates(
        signal: String, module: String?, top: String?, instances: List<Instance>, dumpScopes: Map<String, Set<String>>?,
    ): List<String> {
        val written = if (top != null && module != null) scopes(top, module, instances) else emptyList()
        if (dumpScopes == null) return written.map { "$it.$signal" }
        val withSignal = dumpScopes.filterValues { signal in it }.keys
        val names = instances.filter { it.module == module }.map { it.name }.toSet() + listOfNotNull(module)
        val scopes = written.filter { it in withSignal }.ifEmpty {
            withSignal.filter { it.substringAfterLast('.').substringBefore('[') in names }
        }.ifEmpty { withSignal.toList() }
        return scopes.map { "$it.$signal" }
    }

    private fun closing(tokens: List<Token>, open: Int): Int {
        val opening = tokens[open].type
        val closing = if (opening == VerilogTokenTypes.LBRACKET) VerilogTokenTypes.RBRACKET else VerilogTokenTypes.RPAREN
        var depth = 0
        for (i in open until tokens.size) {
            when (tokens[i].type) {
                opening -> depth++
                closing -> if (--depth == 0) return i
            }
        }
        return tokens.size
    }

    private fun codeTokens(source: CharSequence): List<Token> {
        val lexer = VerilogLexer()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in VerilogTokenTypes.COMMENTS) {
                tokens += Token(type, lexer.tokenStart, lexer.tokenEnd, source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString())
            }
            lexer.advance()
        }
        return tokens
    }

    /** The words of a VCD header, which whitespace separates. */
    private class Words(private val reader: Reader) {
        private val word = StringBuilder()

        fun next(): String? {
            word.setLength(0)
            while (true) {
                val c = reader.read()
                if (c < 0) return word.takeIf { it.isNotEmpty() }?.toString()
                if (c.toChar().isWhitespace()) {
                    if (word.isNotEmpty()) return word.toString()
                } else if (word.length < MAX_WORD) {
                    word.append(c.toChar())
                }
            }
        }

        fun untilEnd(): List<String> = buildList {
            while (true) add(next()?.takeIf { it != "\$end" } ?: break)
        }

        fun skipToEnd() {
            while (true) if ((next() ?: return) == "\$end") return
        }
    }

    private val SIMPLE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_$]*""")
    private val LIFETIMES = setOf("automatic", "static")
    /** What comes before a name that is not the type of an instance: `.port(...)`, `pkg::f(...)`. */
    private val QUALIFIERS = setOf(".", "::")
    private val EXTENSIONS = setOf("v", "sv")
    private const val MAX_SCOPES = 50
    private const val MAX_WORD = 4096
}

/**
 * "Add to Waveform" in the editor: shows the signal at the caret in the GTKWave or Surfer that Open
 * Waveform started, under the hierarchical name it has in the simulation. Where the module is instantiated
 * more than once, or more than one viewer is open, the instance is picked from a list.
 */
class AddToWaveformAction : DumbAwareAction() {
    private class Target(val viewer: OpenViewer, val signal: String)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null && file != null && file.isVerilog()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val source = editor.document.immutableCharSequence
        val offset = editor.caretModel.offset
        val signal = WaveformSignals.identifierAt(source, offset)
            ?: return HintManager.getInstance().showErrorHint(editor, "Place the caret on a signal to add it to the waveform.")
        val viewers = Waveforms.openViewers()
        if (viewers.isEmpty()) {
            return HintManager.getInstance().showInformationHint(
                editor,
                "No waveform is open. Run a testbench and choose Open Waveform; then signals can be added from here.",
            )
        }
        val commanded = viewers.filter { it.takesCommands }
        if (commanded.isEmpty()) {
            return HintManager.getInstance().showErrorHint(
                editor, "${viewers.first().viewer.displayName} cannot be told to add signals. Surfer needs version 0.4 or later.",
            )
        }
        val module = VerilogModuleHeaders.modules(source).firstOrNull { offset in it.range }?.name
        val path = file.fileSystem.getNioPath(file)?.toAbsolutePath()?.normalize()
        object : Task.Backgroundable(project, "Finding $signal in the waveform", true) {
            private var targets: List<Target> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val directories = ReadAction.nonBlocking(Callable { VerilogDesign.libraryDirectories(project) })
                    .inSmartMode(project).executeSynchronously()
                // The file is read as it is in the editor, unsaved changes included.
                val instances = WaveformSignals.instancesIn(
                    directories + listOfNotNull(path?.parent), path?.let { mapOf(it to source) }.orEmpty(),
                )
                targets = commanded.flatMap { viewer ->
                    indicator.checkCanceled()
                    val top = viewer.configuration.resolveTopModule()
                    WaveformSignals.candidates(signal, module, top, instances, WaveformSignals.vcdScopes(viewer.dump))
                        .map { Target(viewer, it) }
                }
            }

            override fun onSuccess() {
                if (editor.isDisposed) return
                when (targets.size) {
                    0 -> HintManager.getInstance().showErrorHint(
                        editor,
                        "'$signal' is not in the waveform. It is dumped only if \$dumpvars covers its module.",
                    )
                    1 -> add(project, targets)
                    else -> choose(project, editor, signal, targets)
                }
            }
        }.queue()
    }

    private fun choose(project: Project, editor: Editor, signal: String, targets: List<Target>) {
        val severalViewers = targets.map { it.viewer }.distinct().size > 1
        val all = "All ${targets.size}"
        val labels = targets.associateBy { target ->
            if (severalViewers) "${target.signal}   (${target.viewer.dump.name})" else target.signal
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(listOf(all) + labels.keys)
            .setTitle("Add $signal of")
            .setItemChosenCallback { label -> add(project, labels[label]?.let(::listOf) ?: targets) }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun add(project: Project, targets: List<Target>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            for ((viewer, signals) in targets.groupBy({ it.viewer }, { it.signal })) {
                val name = viewer.viewer.displayName
                if (viewer.addSignals(signals)) {
                    ApplicationManager.getApplication().invokeLater({
                        StatusBar.Info.set("Added ${signals.joinToString()} to $name", project)
                    }, project.disposed)
                } else {
                    NotificationGroupManager.getInstance().getNotificationGroup("Verilog Toolbox")
                        .createNotification(
                            "$name did not take the signals",
                            "It could not be told to add ${signals.joinToString()}. A GTKWave built without Tcl " +
                                "cannot be; restarting the viewer with Open Waveform may help otherwise.",
                            NotificationType.WARNING,
                        )
                        .notify(project)
                }
            }
        }
    }
}
