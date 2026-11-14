package com.jotrorox.verilogtoolbox

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

object VerilogLanguage : Language("Verilog") {
    private fun readResolve(): Any = VerilogLanguage
}

object VerilogFileType : LanguageFileType(VerilogLanguage) {
    override fun getName(): String = "Verilog"
    override fun getDescription(): String = "Verilog / SystemVerilog"
    override fun getDefaultExtension(): String = "v"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}

private val SYSTEM_VERILOG_EXTENSIONS = setOf("sv", "svh")

fun VirtualFile.isVerilog(): Boolean = fileType == VerilogFileType

/** Language identifier sent to the language server for this file. */
fun VirtualFile.verilogLanguageId(): String =
    if (extension?.lowercase() in SYSTEM_VERILOG_EXTENSIONS) "systemverilog" else "verilog"
