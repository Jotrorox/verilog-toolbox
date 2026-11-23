package com.jotrorox.verilogtoolbox.quartus.tcl

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class QuartusTclPlatformTest : BasePlatformTestCase() {
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

    fun testFileTypesByName() {
        val fileTypes = FileTypeManager.getInstance()
        for (name in listOf("top.qsf", "top.qpf", "ip.qip", "top_assignment_defaults.qdf")) {
            assertEquals(name, QuartusTclSettingsFileType, fileTypes.getFileTypeByFileName(name))
        }
        assertEquals(QuartusTclSdcFileType, fileTypes.getFileTypeByFileName("top.sdc"))
        for (name in listOf("top.fit.rpt", "top.sta.summary", "top.map.smsg", "top.pin")) {
            assertEquals(name, QuartusTclReportFileType, fileTypes.getFileTypeByFileName(name))
        }
        assertNotSame(QuartusTclReportFileType, fileTypes.getFileTypeByFileName("foo.rpt"))
        assertNotSame(QuartusTclReportFileType, fileTypes.getFileTypeByFileName("foo.summary"))
    }

    fun testReportsOpenAsPlainText() {
        val file = myFixture.configureByText("top.fit.rpt", "Fitter Status : Successful\n")
        assertEquals(QuartusTclReportFileType, file.fileType)
        assertEquals("TEXT", file.language.id)
    }

    fun testSettingsFileHighlighting() {
        myFixture.configureByText(
            "top.qsf",
            "# Project settings\nset_global_assignment -name TOP_LEVEL_ENTITY top\n" +
                "set_location_assignment PIN_P11 -to clk\n",
        )
        assertEquals(QuartusTclLanguage, myFixture.file.language)
        assertHighlight("# Project", QuartusTclTokenTypes.COMMENT, QuartusTclColors.COMMENT)
        assertHighlight("set_global_assignment", QuartusTclTokenTypes.KNOWN_COMMAND, QuartusTclColors.KNOWN_COMMAND)
        assertHighlight("-name", QuartusTclTokenTypes.OPTION, QuartusTclColors.OPTION)
        assertHighlight("TOP_LEVEL_ENTITY", QuartusTclTokenTypes.ASSIGNMENT_NAME, QuartusTclColors.ASSIGNMENT_NAME)
        assertHighlight("PIN_P11", QuartusTclTokenTypes.WORD, QuartusTclColors.WORD)
    }

    fun testConstraintsFileHighlighting() {
        myFixture.configureByText("top.sdc", "create_clock -name clk -period 20.000 [get_ports {clk}]\n")
        assertEquals(QuartusTclSdcFileType, myFixture.file.fileType)
        assertHighlight("create_clock", QuartusTclTokenTypes.KNOWN_COMMAND, QuartusTclColors.KNOWN_COMMAND)
        assertHighlight("20.000", QuartusTclTokenTypes.NUMBER, QuartusTclColors.NUMBER)
        assertHighlight("get_ports", QuartusTclTokenTypes.KNOWN_COMMAND, QuartusTclColors.KNOWN_COMMAND)
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

    fun testCommentLineWithHash() {
        myFixture.configureByText("top.sdc", "<caret>derive_pll_clocks\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("#"))
        myFixture.configureByText("top.qsf", "<caret># set_global_assignment -name SEED 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("set_global_assignment -name SEED 1\n", myFixture.editor.document.text.trimStart())
    }

    fun testBracesAreMatched() {
        myFixture.configureByText("top.sdc", "set_false_path -to [get_ports {a b}]")
        val text = myFixture.editor.document.text
        for ((open, close) in listOf('[' to ']', '{' to '}')) {
            val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(text.indexOf(open))
            assertTrue(BraceMatchingUtil.matchBrace(text, myFixture.file.fileType, iterator, true))
            assertEquals(text.indexOf(close), iterator.start)
        }
    }

    /** Completes at the caret and returns the offered items, or the one that was inserted right away. */
    private fun complete(fileName: String, text: String): List<String> {
        myFixture.configureByText(fileName, text)
        val items = myFixture.completeBasic()
        return items?.map { it.lookupString } ?: listOf(myFixture.editor.document.text)
    }

    fun testCompletesQuartusCommandsInSettingsFiles() {
        val items = complete("top.qsf", "set_glo<caret>")
        assertTrue(items.toString(), items.any { it.contains("set_global_assignment") })
        val all = complete("top.qsf", "<caret>")
        assertTrue(all.toString(), "set_location_assignment" in all)
        assertFalse(all.toString(), "create_clock" in all)
    }

    fun testCompletesSdcCommands() {
        val all = complete("top.sdc", "create_clock -period 10 [<caret>]")
        assertTrue(all.toString(), "get_ports" in all)
        assertFalse(all.toString(), "set_global_assignment" in all)
        val clock = complete("top.sdc", "create_gen<caret>")
        assertTrue(clock.toString(), clock.any { it.contains("create_generated_clock") })
    }

    fun testCompletesAssignmentNames() {
        val items = complete("top.qsf", "set_global_assignment -name TOP_LEVEL<caret>")
        assertTrue(items.toString(), items.any { it.contains("TOP_LEVEL_ENTITY") })
        val all = complete("top.qsf", "set_instance_assignment -name <caret>")
        assertTrue(all.toString(), all.containsAll(listOf("IO_STANDARD", "WEAK_PULL_UP_RESISTOR", "VIRTUAL_PIN")))
    }

    fun testCompletesOptionFlags() {
        val sdc = complete("top.sdc", "create_clock -per<caret>")
        assertTrue(sdc.toString(), sdc.any { it.contains("-period") })
        assertFalse(sdc.toString(), sdc.any { it.contains("--period") })
        val qsf = complete("top.qsf", "set_location_assignment PIN_A1 -<caret>")
        assertTrue(qsf.toString(), qsf.containsAll(listOf("-to", "-entity")))
        assertFalse(qsf.toString(), "-name" in qsf)
    }

    fun testCompletesValues() {
        val standards = complete("top.qsf", "set_instance_assignment -name IO_STANDARD <caret> -to clk")
        assertTrue(standards.toString(), "\"3.3-V LVTTL\"" in standards && "LVDS" in standards)
        val switches = complete("top.qsf", "set_instance_assignment -name WEAK_PULL_UP_RESISTOR <caret>")
        assertTrue(switches.toString(), switches.containsAll(listOf("ON", "OFF")))
        assertFalse(switches.toString(), "LVDS" in switches)
    }

    fun testQuotedValueReplacesTheOpenedQuote() {
        myFixture.configureByText("top.qsf", "set_instance_assignment -name IO_STANDARD \"3.3-V LVT<caret>\" -to clk")
        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "\"3.3-V LVTTL\"" }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        assertEquals(
            "set_instance_assignment -name IO_STANDARD \"3.3-V LVTTL\" -to clk",
            myFixture.editor.document.text,
        )
    }

    fun testNoCompletionInComments() {
        myFixture.configureByText("top.qsf", "# set_glo<caret>")
        val items = myFixture.completeBasic()
        assertTrue(items.isNullOrEmpty())
        assertEquals("# set_glo", myFixture.editor.document.text)
    }
}
