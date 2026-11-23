package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import java.awt.Component
import java.awt.Container
import javax.swing.JScrollPane

class SettingsPagePlatformTest : BasePlatformTestCase() {
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

    /** The page takes the width of the Settings dialog, its comments wrapping, rather than scrolling sideways. */
    fun testPageFollowsTheDialogWidth() {
        val configurable = VeribleConfigurable()
        // The page brings its own scroll pane, which the dialog only sizes.
        val scroll = configurable.createComponent() as JScrollPane
        val page = scroll.viewport.view
        val rows = descendants(page).filterIsInstance<DialogPanel>().single().components
        // Lightweight peers, as on screen: without one, a component's minimum size is its current size.
        scroll.addNotify()
        try {
            // Wide, narrower than the page's preferred width, and narrower than it can get at all.
            for (width in listOf(1200, 600, 1200, 300)) {
                layOut(scroll, width)
                val rightmost = rows.filter { it.isVisible }.maxOf { it.x + it.width }
                assertTrue("nothing reaches past the page at $width", rightmost <= page.width)
                if (width == 300) {
                    assertTrue("too narrow a dialog scrolls sideways", page.width > scroll.viewport.width)
                } else {
                    assertEquals("the page is as wide as the dialog at $width", scroll.viewport.width, page.width)
                }
            }
        } finally {
            scroll.removeNotify()
            configurable.disposeUIResources()
        }
    }

    fun testEditsAreAppliedAndReset() {
        val toolchain = ToolchainSettings.getInstance()
        val options = toolchain.verilatorOptions
        val configurable = VeribleConfigurable()
        try {
            val page = configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified)

            val lint = descendants(page).filterIsInstance<JBCheckBox>().single { it.text == "Lint open files with Verilator" }
            lint.doClick()
            assertTrue(configurable.isModified)
            configurable.reset()
            assertFalse(configurable.isModified)
            assertEquals(toolchain.verilatorLintEnabled, lint.isSelected)

            // The only text field of its own, the others being those of path fields.
            val optionsField = descendants(page).filterIsInstance<JBTextField>().single { it.parent is DialogPanel }
            optionsField.text = "-Wall"
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals("-Wall", toolchain.verilatorOptions)
            assertFalse(configurable.isModified)
        } finally {
            toolchain.verilatorOptions = options
            configurable.disposeUIResources()
        }
    }

    private fun layOut(scroll: JScrollPane, width: Int) {
        scroll.setSize(width, 900)
        // A comment's height follows the width it was given the time before, as on screen after a resize.
        repeat(3) {
            descendants(scroll).forEach { it.invalidate() }
            descendants(scroll).filterIsInstance<Container>().forEach { it.doLayout() }
        }
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())
}
