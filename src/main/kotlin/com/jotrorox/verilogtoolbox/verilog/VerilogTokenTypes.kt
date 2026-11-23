package com.jotrorox.verilogtoolbox.verilog

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class VerilogTokenType(debugName: String) : IElementType(debugName, VerilogLanguage)

object VerilogTokenTypes {
    @JvmField val LINE_COMMENT = VerilogTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = VerilogTokenType("BLOCK_COMMENT")
    @JvmField val STRING = VerilogTokenType("STRING")
    @JvmField val NUMBER = VerilogTokenType("NUMBER")
    @JvmField val IDENTIFIER = VerilogTokenType("IDENTIFIER")
    @JvmField val KEYWORD = VerilogTokenType("KEYWORD")
    /** Built-in net and data types such as `wire`, `logic` and `int`. */
    @JvmField val TYPE = VerilogTokenType("TYPE")
    /** System tasks and functions such as `$display`. */
    @JvmField val SYSTEM_TASK = VerilogTokenType("SYSTEM_TASK")
    /** Compiler directives such as `` `define `` and `` `ifdef ``. */
    @JvmField val DIRECTIVE = VerilogTokenType("DIRECTIVE")
    /** Macro usages such as `` `WIDTH ``. */
    @JvmField val MACRO = VerilogTokenType("MACRO")
    @JvmField val OPERATOR = VerilogTokenType("OPERATOR")
    @JvmField val LPAREN = VerilogTokenType("(")
    @JvmField val RPAREN = VerilogTokenType(")")
    @JvmField val LBRACKET = VerilogTokenType("[")
    @JvmField val RBRACKET = VerilogTokenType("]")
    @JvmField val LBRACE = VerilogTokenType("{")
    @JvmField val RBRACE = VerilogTokenType("}")
    @JvmField val SEMICOLON = VerilogTokenType(";")
    @JvmField val COMMA = VerilogTokenType(",")
    @JvmField val DOT = VerilogTokenType(".")

    // Keywords that open or close a block get their own types so the editor can match them like braces.
    @JvmField val BEGIN = VerilogTokenType("begin")
    @JvmField val END = VerilogTokenType("end")
    @JvmField val MODULE = VerilogTokenType("module")
    @JvmField val ENDMODULE = VerilogTokenType("endmodule")
    @JvmField val CASE = VerilogTokenType("case")
    @JvmField val ENDCASE = VerilogTokenType("endcase")
    @JvmField val GENERATE = VerilogTokenType("generate")
    @JvmField val ENDGENERATE = VerilogTokenType("endgenerate")
    @JvmField val PACKAGE = VerilogTokenType("package")
    @JvmField val ENDPACKAGE = VerilogTokenType("endpackage")
    @JvmField val FORK = VerilogTokenType("fork")
    @JvmField val JOIN = VerilogTokenType("join")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
    @JvmField val KEYWORDS = TokenSet.create(
        KEYWORD, BEGIN, END, MODULE, ENDMODULE, CASE, ENDCASE,
        GENERATE, ENDGENERATE, PACKAGE, ENDPACKAGE, FORK, JOIN,
    )
}

/** Reserved words of IEEE 1800-2017 (SystemVerilog), which include all Verilog keywords. */
internal object VerilogKeywords {
    private val blockKeywords = mapOf(
        "begin" to VerilogTokenTypes.BEGIN, "end" to VerilogTokenTypes.END,
        "module" to VerilogTokenTypes.MODULE, "macromodule" to VerilogTokenTypes.MODULE,
        "endmodule" to VerilogTokenTypes.ENDMODULE,
        "case" to VerilogTokenTypes.CASE, "casex" to VerilogTokenTypes.CASE,
        "casez" to VerilogTokenTypes.CASE, "randcase" to VerilogTokenTypes.CASE,
        "endcase" to VerilogTokenTypes.ENDCASE,
        "generate" to VerilogTokenTypes.GENERATE, "endgenerate" to VerilogTokenTypes.ENDGENERATE,
        "package" to VerilogTokenTypes.PACKAGE, "endpackage" to VerilogTokenTypes.ENDPACKAGE,
        "fork" to VerilogTokenTypes.FORK, "join" to VerilogTokenTypes.JOIN,
        "join_any" to VerilogTokenTypes.JOIN, "join_none" to VerilogTokenTypes.JOIN,
    )

    private val types = """
        bit byte chandle event genvar int integer logic longint real realtime reg shortint shortreal
        signed string supply0 supply1 time tri tri0 tri1 triand trior trireg unsigned uwire var void
        wand wire wor
    """.words()

    private val keywords = """
        accept_on alias always always_comb always_ff always_latch and assert assign assume automatic
        before bind bins binsof break buf bufif0 bufif1 cell checker class clocking cmos config const
        constraint context continue cover covergroup coverpoint cross deassign default defparam design
        disable dist do edge else endchecker endclass endclocking endconfig endfunction endgroup
        endinterface endprimitive endprogram endproperty endsequence endspecify endtable endtask enum
        eventually expect export extends extern final first_match for force foreach forever forkjoin
        function global highz0 highz1 if iff ifnone ignore_bins illegal_bins implements implies import
        incdir include initial inout input inside instance interconnect interface intersect large let
        liblist library local localparam matches medium modport nand negedge nettype new nexttime nmos
        nor noshowcancelled not notif0 notif1 null or output packed parameter pmos posedge primitive
        priority program property protected pull0 pull1 pulldown pullup pulsestyle_ondetect
        pulsestyle_onevent pure rand randc randsequence rcmos ref reject_on release repeat restrict
        return rnmos rpmos rtran rtranif0 rtranif1 s_always s_eventually s_nexttime s_until
        s_until_with scalared sequence showcancelled small soft solve specify specparam static strong
        strong0 strong1 struct super sync_accept_on sync_reject_on table tagged task this throughout
        timeprecision timeunit tran tranif0 tranif1 type typedef union unique unique0 until until_with
        untyped use vectored virtual wait wait_order weak weak0 weak1 while wildcard with within xnor
        xor
    """.words()

    private val directives = """
        __FILE__ __LINE__ begin_keywords celldefine default_nettype define else elsif end_keywords
        endcelldefine endif ifdef ifndef include line nounconnected_drive pragma resetall timescale
        unconnected_drive undef undefineall
    """.words()

    private fun String.words(): Set<String> = split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    fun tokenTypeOf(word: String): IElementType = blockKeywords[word] ?: when (word) {
        in types -> VerilogTokenTypes.TYPE
        in keywords -> VerilogTokenTypes.KEYWORD
        else -> VerilogTokenTypes.IDENTIFIER
    }

    /** [name] is the directive without its leading backtick. */
    fun isDirective(name: String): Boolean = name in directives
}
