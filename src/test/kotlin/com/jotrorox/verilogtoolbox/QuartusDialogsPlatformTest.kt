package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.dsl.builder.panel
import javax.swing.text.JTextComponent

class QuartusDialogsPlatformTest : BasePlatformTestCase() {
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

    fun testFilteringComboBoxHasAnEditor() {
        val field = QuartusFilteringComboBox()
        assertTrue(field.isEditable)
        // An editable combo box without an editor component cannot be laid out.
        assertTrue(field.editor.editorComponent is JTextComponent)
        assertTrue(field.components.contains(field.editor.editorComponent))
        field.items = listOf("10M08DAF256C8G", "10M50DAF484C7G")
        field.text = "10M50DAF484C7G"
        assertEquals("10M50DAF484C7G", field.text)
        assertNotNull(field.preferredSize)
    }

    fun testDeviceChooserLaysOutWithoutQuartus() {
        val disposable = Disposer.newDisposable()
        try {
            val chooser = QuartusDeviceChooser(null, "max 10", "10m50daf484c7g", disposable)
            val panel = panel { chooser.addTo(this) }
            assertTrue(panel.preferredSize.width > 0)
            // Without an installation what is typed is taken as it is, the device in upper case.
            assertEquals("max 10", chooser.family)
            assertEquals("10M50DAF484C7G", chooser.device)
            assertNull(chooser.validate())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testDeviceChooserAsksForADevice() {
        val disposable = Disposer.newDisposable()
        try {
            val chooser = QuartusDeviceChooser(null, "MAX 10", null, disposable)
            panel { chooser.addTo(this) }
            assertNotNull(chooser.validate())
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
