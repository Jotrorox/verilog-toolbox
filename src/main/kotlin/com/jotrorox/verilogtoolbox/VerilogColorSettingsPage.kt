package com.jotrorox.verilogtoolbox

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Settings | Editor | Color Scheme | Verilog. */
class VerilogColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Verilog"
    override fun getIcon(): Icon = VerilogFileType.icon
    override fun getHighlighter(): SyntaxHighlighter = VerilogSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS
    override fun getDemoText(): String = DEMO

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", VerilogColors.KEYWORD),
            AttributesDescriptor("Built-in type", VerilogColors.TYPE),
            AttributesDescriptor("Identifier", VerilogColors.IDENTIFIER),
            AttributesDescriptor("Declaration name", VerilogColors.DECLARATION),
            AttributesDescriptor("System task or function", VerilogColors.SYSTEM_TASK),
            AttributesDescriptor("Compiler directive", VerilogColors.DIRECTIVE),
            AttributesDescriptor("Macro", VerilogColors.MACRO),
            AttributesDescriptor("Number", VerilogColors.NUMBER),
            AttributesDescriptor("String//String text", VerilogColors.STRING),
            AttributesDescriptor("String//Valid escape sequence", VerilogColors.VALID_ESCAPE),
            AttributesDescriptor("String//Invalid escape sequence", VerilogColors.INVALID_ESCAPE),
            AttributesDescriptor("Comments//Line comment", VerilogColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", VerilogColors.BLOCK_COMMENT),
            AttributesDescriptor("Braces and Operators//Operator", VerilogColors.OPERATOR),
            AttributesDescriptor("Braces and Operators//Parentheses", VerilogColors.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", VerilogColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Braces", VerilogColors.BRACES),
            AttributesDescriptor("Braces and Operators//Semicolon", VerilogColors.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Comma", VerilogColors.COMMA),
            AttributesDescriptor("Braces and Operators//Dot", VerilogColors.DOT),
            AttributesDescriptor("Bad character", VerilogColors.BAD_CHARACTER),
        )

        val TAGS = mapOf("decl" to VerilogColors.DECLARATION, "macro" to VerilogColors.MACRO)

        val DEMO = """
            `timescale 1ns / 1ps
            `define <macro>WIDTH</macro> 8

            /* A counter that wraps
               at a configurable limit. */
            module <decl>counter</decl> #(
                parameter int LIMIT = 8'hFF
            ) (
                input  logic                clk,
                input  logic                rst_n,
                output logic [`WIDTH-1:0]   count
            );
                // Count up on every clock edge.
                always_ff @(posedge clk or negedge rst_n) begin
                    if (!rst_n)
                        count <= '0;
                    else if (count == LIMIT)
                        count <= 4'b0000;
                    else
                        count <= count + 1;
                end

                initial begin
                    #10ns ${'$'}display("count = %0d\n\q", count);
                end
            endmodule
        """.trimIndent()
    }
}
