package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class VerilatorPlatformTest : BasePlatformTestCase() {
    private var directory: Path? = null

    override fun tearDown() {
        try {
            directory?.let { NioFiles.deleteRecursively(it) }
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Opens [text] as a file on disk next to a counter module, or returns false when Verilator is not installed. */
    private fun open(text: String): Boolean {
        if (ToolchainSettings.getInstance().findVerilator() == null) return false
        val directory = Files.createTempDirectory("verilator test").toRealPath().also { directory = it }
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        Files.writeString(
            directory.resolve("counter.sv"),
            "module counter(input logic clk, output logic [3:0] q);\n  always_ff @(posedge clk) q <= q + 1'b1;\nendmodule\n",
        )
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("top.sv"), text))!!
        myFixture.configureFromExistingVirtualFile(file)
        return true
    }

    private fun verilatorMessages(): List<String> =
        myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description }.filter { "(Verilator" in it }

    fun testWidthMismatchIsReported() {
        if (!open("module top(input logic clk, output logic [2:0] y);\n  counter c(.clk(clk), .q(y));\nendmodule\n")) return
        val messages = verilatorMessages()
        assertTrue(messages.toString(), messages.any { "(Verilator PINCONNECTEMPTY)" in it || "(Verilator WIDTH" in it })
    }

    fun testUnsavedTextIsLinted() {
        if (!open("module top(input logic [3:0] a, output logic [3:0] y);\n  assign y = a;\nendmodule\n")) return
        assertEquals(emptyList<String>(), verilatorMessages())
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.replaceString(0, document.textLength, "module top(input logic [3:0] a, output logic [1:0] y);\n  assign y = a;\nendmodule\n")
        }
        val messages = verilatorMessages()
        assertTrue(messages.toString(), messages.any { "(Verilator WIDTHTRUNC)" in it })
    }

    fun testLintCanBeSwitchedOff() {
        if (!open("module top(input logic [3:0] a, output logic [1:0] y);\n  assign y = a;\nendmodule\n")) return
        val settings = ToolchainSettings.getInstance()
        settings.verilatorLintEnabled = false
        try {
            assertEquals(emptyList<String>(), verilatorMessages())
        } finally {
            settings.verilatorLintEnabled = true
        }
    }
}
