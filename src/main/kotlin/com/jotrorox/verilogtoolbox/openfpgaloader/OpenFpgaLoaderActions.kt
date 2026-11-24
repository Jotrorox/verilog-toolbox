package com.jotrorox.verilogtoolbox.openfpgaloader

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import java.nio.file.Path

/** Programs the project's bitstream, with the run configuration for [destination]; one is set up the first time. */
abstract class OpenFpgaLoaderProgramAction(private val destination: FpgaDestination) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        OpenFpgaLoaderRunner.run(e.project ?: return, destination)
    }
}

class OpenFpgaLoaderLoadAction : OpenFpgaLoaderProgramAction(FpgaDestination.SRAM)
class OpenFpgaLoaderFlashAction : OpenFpgaLoaderProgramAction(FpgaDestination.FLASH)

/** Says which probe and FPGA are connected, over the board or cable the project programs with. */
class OpenFpgaLoaderDetectAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        OpenFpgaLoaderRunner.detect(e.project ?: return)
    }
}

/**
 * Writes the bitstream file in the context to the flash. Loading it into the SRAM is the run configuration
 * the context menu offers already, as "Run 'Load …'".
 */
class OpenFpgaLoaderFlashFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && bitstream(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        OpenFpgaLoaderRunner.run(e.project ?: return, FpgaDestination.FLASH, bitstream(e) ?: return)
    }

    private fun bitstream(e: AnActionEvent): Path? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && it.isInLocalFileSystem }
            ?.let { it.fileSystem.getNioPath(it) }?.normalize()?.takeIf { OpenFpgaLoader.isBitstream(it) }
}
