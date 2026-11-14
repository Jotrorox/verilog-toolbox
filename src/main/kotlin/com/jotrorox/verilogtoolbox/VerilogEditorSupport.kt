package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class VerilogCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Matches brackets and the keyword pairs that delimit blocks, such as `begin`/`end`. */
class VerilogBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(VerilogTokenTypes.LPAREN, VerilogTokenTypes.RPAREN, false),
            BracePair(VerilogTokenTypes.LBRACKET, VerilogTokenTypes.RBRACKET, false),
            BracePair(VerilogTokenTypes.LBRACE, VerilogTokenTypes.RBRACE, false),
            BracePair(VerilogTokenTypes.BEGIN, VerilogTokenTypes.END, true),
            BracePair(VerilogTokenTypes.FORK, VerilogTokenTypes.JOIN, true),
            BracePair(VerilogTokenTypes.CASE, VerilogTokenTypes.ENDCASE, true),
            BracePair(VerilogTokenTypes.GENERATE, VerilogTokenTypes.ENDGENERATE, true),
            BracePair(VerilogTokenTypes.MODULE, VerilogTokenTypes.ENDMODULE, true),
            BracePair(VerilogTokenTypes.PACKAGE, VerilogTokenTypes.ENDPACKAGE, true),
        )
    }
}

class VerilogQuoteHandler : SimpleTokenSetQuoteHandler(VerilogTokenTypes.STRING)
