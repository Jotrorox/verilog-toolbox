package com.jotrorox.verilogtoolbox.gowin

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GowinCstPlatformTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
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
}
