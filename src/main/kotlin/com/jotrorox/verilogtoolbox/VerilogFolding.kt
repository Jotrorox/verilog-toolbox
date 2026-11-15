package com.jotrorox.verilogtoolbox

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Finds the foldable regions of a Verilog source by pairing block keywords over the token stream.
 * There is no grammar, so the pairing is a stack that forgives the unbalanced code of someone typing.
 */
object VerilogFolding {
    /** [start] until [end] is hidden and shown as [placeholder]. */
    data class Region(val start: Int, val end: Int, val placeholder: String)

    private class Token(val type: IElementType, val start: Int, val end: Int, val text: String)

    /** An opener waiting for its closer; the fold will begin at [foldStart], after the header. */
    private class Open(val closer: String, val foldStart: Int)

    fun regions(source: CharSequence): List<Region> {
        val regions = mutableListOf<Region>()
        val code = mutableListOf<Token>()
        val lineComments = mutableListOf<Token>()
        val lexer = VerilogLexer()
        lexer.start(source, 0, source.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val text = source.substring(lexer.tokenStart, lexer.tokenEnd)
            val token = Token(type, lexer.tokenStart, lexer.tokenEnd, text)
            when (type) {
                TokenType.WHITE_SPACE -> {}
                VerilogTokenTypes.BLOCK_COMMENT -> regions.addIfMultiline(source, token.start, token.end, "/*...*/")
                VerilogTokenTypes.LINE_COMMENT -> lineComments += token
                else -> code += token
            }
            lexer.advance()
        }
        foldCommentRuns(source, lineComments, regions)
        foldBlocks(source, code, regions)
        return regions.sortedBy { it.start }
    }

    private fun foldBlocks(source: CharSequence, tokens: List<Token>, regions: MutableList<Region>) {
        val stack = ArrayDeque<Open>()
        // Keywords since the last `;` or block boundary: the qualifiers of the declaration being read.
        val qualifiers = mutableSetOf<String>()
        for ((i, token) in tokens.withIndex()) {
            if (token.type == VerilogTokenTypes.SEMICOLON) {
                qualifiers.clear()
                continue
            }
            if (token.type !in VerilogTokenTypes.KEYWORDS) continue
            val word = token.text
            if (word in CLOSERS) {
                qualifiers.clear()
                val closer = if (word.startsWith("join")) "join" else word
                val open = stack.popUntil(closer) ?: continue
                regions.addIfMultiline(source, open.foldStart, token.start, " ... ")
                continue
            }
            val closer = OPENERS[word]
            if (closer == null || !opensBlock(word, tokens, i, qualifiers)) {
                qualifiers += word
                continue
            }
            qualifiers.clear()
            stack.addLast(Open(closer, headerEnd(word, tokens, i)))
        }
    }

    /** Whether the opener keyword at [index] really starts a block that its end keyword will close. */
    private fun opensBlock(word: String, tokens: List<Token>, index: Int, qualifiers: Set<String>): Boolean {
        val previous = tokens.getOrNull(index - 1)
        val next = tokens.getOrNull(index + 1)
        return when (word) {
            // `wait fork;` and `disable fork;` are statements.
            "fork" -> previous?.text != "wait" && previous?.text != "disable"
            // Prototypes have no body: `extern`, `pure virtual`, DPI imports and exports, modport lists,
            // and the sample function of `covergroup cg with function sample(...)`.
            "function", "task" -> previous?.text != "with" && PROTOTYPE_QUALIFIERS.none { it in qualifiers }
            // `typedef class x;` is a forward declaration.
            "class" -> "typedef" !in qualifiers
            // Not `virtual interface x`, an `interface` port, `interface.modport` or `interface class`.
            "interface" -> "extern" !in qualifiers && previous?.text != "virtual" &&
                previous?.type != VerilogTokenTypes.LPAREN && previous?.type != VerilogTokenTypes.COMMA &&
                next?.type != VerilogTokenTypes.DOT && next?.text != "class"
            "module", "macromodule", "program" -> "extern" !in qualifiers
            else -> true
        }
    }

    /**
     * Where the fold begins, so that the collapsed line still reads `module foo ... endmodule`,
     * `begin : label ... end`, `case (x) ... endcase` or `function int f(); ... endfunction`.
     */
    private fun headerEnd(word: String, tokens: List<Token>, index: Int): Int {
        var last = index
        when (word) {
            "begin", "fork" ->
                if (tokens.getOrNull(index + 1)?.text == ":" && tokens.getOrNull(index + 2).isIdentifier()) {
                    last = index + 2
                }
            "case", "casex", "casez" -> last = closingParenthesis(tokens, index + 1) ?: index
            // The name of a function follows its return type, so keep the whole prototype.
            "function", "task" ->
                (index + 1 until tokens.size).firstOrNull { tokens[it].type == VerilogTokenTypes.SEMICOLON }
                    ?.let { last = it }
            in NAMED -> {
                if (tokens.getOrNull(last + 1)?.text in LIFETIMES) last++
                if (tokens.getOrNull(last + 1).isIdentifier()) last++
            }
        }
        return tokens[last].end
    }

    /** The index of the `)` matching the `(` at [open], if there is a `(` and it is closed. */
    private fun closingParenthesis(tokens: List<Token>, open: Int): Int? {
        if (tokens.getOrNull(open)?.type != VerilogTokenTypes.LPAREN) return null
        var depth = 0
        for (i in open until tokens.size) {
            when (tokens[i].type) {
                VerilogTokenTypes.LPAREN -> depth++
                VerilogTokenTypes.RPAREN -> if (--depth == 0) return i
            }
        }
        return null
    }

    private fun Token?.isIdentifier(): Boolean = this?.type == VerilogTokenTypes.IDENTIFIER

    /**
     * Pops the innermost opener that [closer] ends, dropping the unclosed ones inside it. `end`, `join` and
     * `endcase` do not reach past an enclosing declaration, so a stray one cannot close a block outside it.
     */
    private fun ArrayDeque<Open>.popUntil(closer: String): Open? {
        val statementLevel = closer in STATEMENT_CLOSERS
        for (i in indices.reversed()) {
            if (this[i].closer == closer) {
                val open = this[i]
                while (size > i) removeLast()
                return open
            }
            if (statementLevel && this[i].closer !in STATEMENT_CLOSERS) return null
        }
        return null
    }

    /** Folds runs of `//` comments that stand on lines of their own, leaving the first line visible. */
    private fun foldCommentRuns(source: CharSequence, comments: List<Token>, regions: MutableList<Region>) {
        var i = 0
        while (i < comments.size) {
            var last = i
            if (source.isBlankBetween(source.lineStart(comments[i].start), comments[i].start)) {
                while (last + 1 < comments.size &&
                    source.isOneLineBreak(comments[last].end, comments[last + 1].start)
                ) last++
            }
            if (last > i) regions += Region(comments[i].end, comments[last].end, " ...")
            i = last + 1
        }
    }

    private fun CharSequence.lineStart(offset: Int): Int {
        var i = offset
        while (i > 0 && this[i - 1] != '\n') i--
        return i
    }

    private fun CharSequence.isBlankBetween(start: Int, end: Int): Boolean =
        (start until end).all { this[it].isWhitespace() }

    private fun CharSequence.isOneLineBreak(start: Int, end: Int): Boolean =
        isBlankBetween(start, end) && (start until end).count { this[it] == '\n' } == 1

    /** Only regions spanning more than one line are worth folding. */
    private fun MutableList<Region>.addIfMultiline(source: CharSequence, start: Int, end: Int, placeholder: String) {
        if (start < end && (start until end).any { source[it] == '\n' }) this += Region(start, end, placeholder)
    }

    /**
     * Opener keyword to the keyword that closes it (`join` stands for `join_any` and `join_none` too).
     * Left out because the keyword is too often used without a body: `property`, `sequence`, `clocking`.
     */
    private val OPENERS = mapOf(
        "begin" to "end", "fork" to "join",
        "case" to "endcase", "casex" to "endcase", "casez" to "endcase", "randcase" to "endcase",
        "module" to "endmodule", "macromodule" to "endmodule", "generate" to "endgenerate",
        "package" to "endpackage", "interface" to "endinterface", "program" to "endprogram",
        "class" to "endclass", "function" to "endfunction", "task" to "endtask",
        "covergroup" to "endgroup", "checker" to "endchecker", "primitive" to "endprimitive",
        "config" to "endconfig", "specify" to "endspecify", "table" to "endtable",
    )
    private val CLOSERS = OPENERS.values.toSet() + setOf("join_any", "join_none")
    private val STATEMENT_CLOSERS = setOf("end", "join", "endcase")
    private val NAMED = setOf(
        "module", "macromodule", "package", "interface", "program", "class", "covergroup", "checker",
        "primitive", "config",
    )
    private val LIFETIMES = setOf("automatic", "static")
    private val PROTOTYPE_QUALIFIERS = setOf("extern", "pure", "import", "export", "modport")
}

class VerilogFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> =
        VerilogFolding.regions(root.node.chars).map { region ->
            // Each region is anchored to its own leaf: the editor remembers collapsed regions by element.
            val anchor = root.findElementAt(region.start)?.node ?: root.node
            FoldingDescriptor(anchor, TextRange(region.start, region.end), null, region.placeholder)
        }.toTypedArray()

    override fun getPlaceholderText(node: ASTNode): String = "..."
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
