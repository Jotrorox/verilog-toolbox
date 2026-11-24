package com.jotrorox.verilogtoolbox.gowin

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class GowinCstPlatformTest : BasePlatformTestCase() {
    private var directory: Path? = null

    override fun tearDown() {
        try {
            directory?.let { NioFiles.deleteRecursively(it) }
            // CLion switches this on at startup, which the test framework reports as a setting the test damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testFileType() {
        assertEquals(GowinCstFileType, FileTypeManager.getInstance().getFileTypeByFileName("primer25k.cst"))
    }

    fun testHighlighting() {
        myFixture.configureByText(
            "primer25k.cst",
            "// LEDs\nIO_LOC \"led[0]\" G5;\nIO_PORT \"led[0]\" IO_TYPE=LVCMOS33 DRIVE=8;\n",
        )
        assertEquals(GowinCstLanguage, myFixture.file.language)
        assertHighlight("// LEDs", GowinCstTokenTypes.COMMENT, GowinCstColors.COMMENT)
        assertHighlight("IO_LOC", GowinCstTokenTypes.KEYWORD, GowinCstColors.KEYWORD)
        assertHighlight("\"led[0]\"", GowinCstTokenTypes.STRING, GowinCstColors.STRING)
        assertHighlight("G5", GowinCstTokenTypes.LOCATION, GowinCstColors.LOCATION)
        assertHighlight("IO_TYPE", GowinCstTokenTypes.ATTRIBUTE, GowinCstColors.ATTRIBUTE)
        assertHighlight("LVCMOS33", GowinCstTokenTypes.VALUE, GowinCstColors.VALUE)
        assertHighlight("8;", GowinCstTokenTypes.NUMBER, GowinCstColors.NUMBER)
    }

    /** The editor's own highlighter, found through the file type, gives the token at [text] its colour. */
    private fun assertHighlight(text: String, type: IElementType, key: TextAttributesKey) {
        val offset = myFixture.editor.document.text.indexOf(text)
        assertTrue(text, offset >= 0)
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(offset)
        assertEquals(text, type, iterator.tokenType)
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(myFixture.file.fileType, project, null)!!
        assertTrue(text, key in highlighter.getTokenHighlights(type))
    }

    fun testCommentLineWithSlashes() {
        myFixture.configureByText("top.cst", "<caret>IO_LOC \"clk\" E2;\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("//"))
    }

    /**
     * Opens a `.cst` file with [text] in a directory on disk next to `src/top.v`. The directory is outside of
     * the project, so the design is taken from the directory of the constraint file.
     */
    private fun openConstraints(text: String) {
        val directory = Files.createTempDirectory("gowin test").toRealPath().also { directory = it }
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        Files.createDirectories(directory.resolve("src"))
        Files.writeString(directory.resolve("src/top.v"), "module top(input clk, output [3:0] led);\nendmodule\n")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("top.cst"), text))!!
        myFixture.configureFromExistingVirtualFile(file)
    }

    /** The warnings of the open file, each with the text it marks, or "file" for one about the whole file. */
    private fun warnings(): List<Pair<String, String>> =
        myFixture.doHighlighting(HighlightSeverity.WARNING)
            .filter { it.severity == HighlightSeverity.WARNING }
            .map { info ->
                val marked = if (info.isFileLevelAnnotation) "file" else myFixture.editor.document.text.substring(info.startOffset, info.endOffset)
                marked to info.description
            }

    fun testPortsAreCheckedAgainstTheTopModule() {
        openConstraints("IO_LOC \"clk\" E2;\nIO_LOC \"ledd[0]\" G5;\nIO_LOC \"led[4]\" G6;\nIO_LOC \"led[1]\" G7;\n")
        assertSameElements(
            warnings(),
            "\"ledd[0]\"" to "'ledd' is not a port of top module 'top' (src/top.v).",
            "\"led[4]\"" to "'led[4]' is out of range: port 'led' of top module 'top' (src/top.v) is [3:0].",
            "file" to "No pin for led[0], led[2], led[3]: these ports of top module 'top' (src/top.v) have no IO_LOC.",
        )
    }

    fun testUnsavedTextIsChecked() {
        openConstraints("IO_LOC \"clk\" E2;\nIO_LOC \"led\" G5;\n")
        assertEquals(emptyList<Pair<String, String>>(), warnings())
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.replaceString(0, document.textLength, "IO_LOC \"clk\" E2;\n")
        }
        assertEquals(
            listOf("file" to "No pin for led: these ports of top module 'top' (src/top.v) have no IO_LOC."),
            warnings(),
        )
    }
}
