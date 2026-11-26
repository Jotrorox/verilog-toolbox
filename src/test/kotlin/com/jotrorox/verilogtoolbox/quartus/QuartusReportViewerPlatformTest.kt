package com.jotrorox.verilogtoolbox.quartus

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class QuartusReportViewerPlatformTest : BasePlatformTestCase() {
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

    private val report = """
        Timing Analyzer report for blinky
        Mon Nov 16 11:57:13 2026

        +----------------------------------------------+
        ; Timing Analyzer Summary                      ;
        +-----------------------+----------------------+
        ; Revision Name         ; blinky               ;
        +-----------------------+----------------------+


        +-------------------------------------------------------+
        ; Slow 1200mV 85C Model Fmax Summary                    ;
        +------------+-----------------+------------+-----------+
        ; Fmax       ; Restricted Fmax ; Clock Name ; Note      ;
        +------------+-----------------+------------+-----------+
        ; 311.43 MHz ; 250.0 MHz       ; clk_50     ;           ;
        +------------+-----------------+------------+-----------+


        +-------------------------------------------------------+
        ; Slow 1200mV 85C Model Setup Summary                   ;
        +------------+-----------------+------------------------+
        ; Clock      ; Slack           ; End Point TNS          ;
        +------------+-----------------+------------------------+
        ; spi_sclk   ; -1.250          ; -37.500                ;
        +------------+-----------------+------------------------+


        +---------------------------+
        ; Timing Analyzer Messages  ;
        +---------------------------+
        Critical Warning (332148): Timing requirements not met
    """.trimIndent() + "\n"

    fun testReportsOpenInTheViewer() {
        val file = myFixture.addFileToProject("output_files/blinky.sta.rpt", report).virtualFile
        val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.filterIsInstance<QuartusReportEditorProvider>().single()
        assertTrue(provider.accept(project, file))
        assertFalse(provider.accept(project, myFixture.addFileToProject("notes.txt", "").virtualFile))

        val editor = provider.createEditor(project, file) as QuartusReportEditor
        try {
            editor.component // The layout is set up with the component.
            assertEquals(TextEditorWithPreview.Layout.SHOW_PREVIEW, editor.getLayout())
            val panel = editor.previewEditor.component as QuartusReportPanel
            PlatformTestUtil.waitWithEventsDispatching("The report was not read", { panel.listedTitles.isNotEmpty() }, 10)
            assertEquals(
                listOf("Timing Analyzer Summary", "Slow 1200mV 85C Model Fmax Summary", "Slow 1200mV 85C Model Setup Summary", "Timing Analyzer Messages"),
                panel.listedTitles,
            )
            assertEquals("Timing Analyzer Summary", panel.selectedTitle)

            editor.select("Fmax Summary")
            assertEquals("Slow 1200mV 85C Model Fmax Summary", panel.selectedTitle)

            // Found in the contents, not only in the titles.
            panel.search("spi_sclk")
            assertEquals(listOf("Slow 1200mV 85C Model Setup Summary"), panel.listedTitles)
            assertEquals("Slow 1200mV 85C Model Setup Summary", panel.selectedTitle)
            panel.search("")
            assertEquals(4, panel.listedTitles.size)

            editor.showLine(report.lines().indexOfFirst { it.contains("spi_sclk") })
            assertEquals(TextEditorWithPreview.Layout.SHOW_EDITOR, editor.getLayout())
            val caret = editor.editor.caretModel.logicalPosition.line
            assertTrue(editor.editor.document.text.lines()[caret].contains("spi_sclk"))
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testASectionAskedForBeforeTheReportIsReadIsShown() {
        val file = myFixture.addFileToProject("output_files/blinky.sta.rpt", report).virtualFile
        val editor = QuartusReportEditorProvider().createEditor(project, file) as QuartusReportEditor
        try {
            editor.component
            editor.select("Setup Summary")
            val panel = editor.previewEditor.component as QuartusReportPanel
            PlatformTestUtil.waitWithEventsDispatching("The report was not read", { panel.listedTitles.isNotEmpty() }, 10)
            assertEquals("Slow 1200mV 85C Model Setup Summary", panel.selectedTitle)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testEveryKindOfSectionIsShown() {
        val file = myFixture.addFileToProject("output_files/blinky.sta.rpt", report).virtualFile
        val panel = QuartusReportPanel(project, file) {}
        panel.show(QuartusReportDocument.parse(report))
        for (title in panel.listedTitles) {
            panel.select(title)
            assertEquals(title, panel.selectedTitle)
        }
        panel.show(null)
        assertTrue(panel.listedTitles.isEmpty())
    }
}
