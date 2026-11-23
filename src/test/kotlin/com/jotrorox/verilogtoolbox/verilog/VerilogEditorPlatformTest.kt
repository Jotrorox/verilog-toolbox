package com.jotrorox.verilogtoolbox.verilog

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.lang.Language
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class VerilogEditorPlatformTest : BasePlatformTestCase() {
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

    fun testFoldRegionsReachTheEditor() {
        myFixture.configureByText("design.sv", "module top;\n  initial begin : init\n    a = 1;\n  end\nendmodule\n")
        myFixture.doHighlighting()
        val text = myFixture.editor.document.text
        val folds = myFixture.editor.foldingModel.allFoldRegions
            .map { text.substring(0, it.startOffset).substringAfterLast('\n') + it.placeholderText }
        assertEquals(listOf("module top ... ", "  initial begin : init ... "), folds)
    }

    fun testLiveTemplateExpandsWithTheIndentOfItsLine() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("design.sv", "module top;\n    always_comb<caret>\nendmodule\n")
        myFixture.type("\t")
        myFixture.checkResult("module top;\n    always_comb begin\n        <caret>\n    end\nendmodule\n")
    }

    fun testLiveTemplatesDoNotExpandInComments() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("design.sv", "// always_comb<caret>\n")
        myFixture.type("\t")
        assertFalse(myFixture.editor.document.text.contains("begin"))
    }

    fun testFileTemplatesUseTheFileName() {
        val directory = myFixture.psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("rtl"))!!
        val templates = FileTemplateManager.getInstance(project)
        val module = FileTemplateUtil.createFromTemplate(
            templates.getInternalTemplate("SystemVerilog Module"), "counter", null, directory,
        ).containingFile
        assertEquals("counter.sv", module.name)
        assertTrue(module.text, module.text.startsWith("module counter (\n"))
        val header = FileTemplateUtil.createFromTemplate(
            templates.getInternalTemplate("SystemVerilog Header"), "defs", null, directory,
        ).containingFile
        assertEquals("defs.svh", header.name)
        assertEquals("`ifndef DEFS_SVH\n`define DEFS_SVH\n\n`endif\n", header.text)
    }

    fun testNewFileActionIsInTheNewMenu() {
        val actions = ActionManager.getInstance()
        val newGroup = actions.getAction("NewGroup") as DefaultActionGroup
        assertTrue(newGroup.getChildren(actions).any { it is VerilogCreateFileAction })
    }

    /** The structure view is the platform's, fed by the language server: nothing of ours may take its place. */
    fun testStructureViewIsLeftToTheLanguageServer() {
        val structureViews = LanguageStructureViewBuilder.getInstance()
        assertNull(structureViews.forLanguage(VerilogLanguage))
        assertEquals(
            "com.intellij.platform.lsp.impl.structureView.LspStructureViewFactory",
            structureViews.forLanguage(Language.ANY)?.javaClass?.name,
        )
    }
}
