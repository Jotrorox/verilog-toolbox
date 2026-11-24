package com.jotrorox.verilogtoolbox.quartus

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/** Runs a flow on the Quartus project of the IDE project. Off while another one runs: they share the project's database. */
abstract class QuartusFlowAction(private val flow: QuartusFlow) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            QuartusProjectService.getInstance(project).active != null && QuartusRuns.getInstance(project).running == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        QuartusRunner.run(e.project ?: return, flow)
    }
}

class QuartusAnalyzeAction : QuartusFlowAction(QuartusFlow.ANALYSIS)
class QuartusSynthesizeAction : QuartusFlowAction(QuartusFlow.SYNTHESIS)
class QuartusFitAction : QuartusFlowAction(QuartusFlow.FITTER)
class QuartusAssembleAction : QuartusFlowAction(QuartusFlow.ASSEMBLER)
class QuartusTimingAction : QuartusFlowAction(QuartusFlow.TIMING)
class QuartusCompileAction : QuartusFlowAction(QuartusFlow.COMPILE)
class QuartusCompileAndProgramAction : QuartusFlowAction(QuartusFlow.COMPILE_AND_PROGRAM)
class QuartusProgramAction : QuartusFlowAction(QuartusFlow.PROGRAM)

/** The reports the last compilation wrote, each of which opens in the editor. */
class QuartusReportsGroup : ActionGroup(), com.intellij.openapi.project.DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { reports(it).isNotEmpty() } == true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        return reports(project).map { (title, file) ->
            object : DumbAwareAction(title, "Open ${file.fileName}", null) {
                override fun actionPerformed(e: AnActionEvent) = QuartusFiles.open(project, file)
            }
        }.toTypedArray()
    }

    private fun reports(project: Project): List<Pair<String, Path>> {
        val revision = QuartusProjectService.getInstance(project).revision ?: return emptyList()
        return QuartusCompilation.REPORTS.map { (title, suffix) -> title to revision.output(suffix) }
            .filter { Files.isRegularFile(it.second) }
    }
}

/** Opens the settings file of the active revision, where every assignment can be seen and changed. */
class QuartusOpenSettingsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { QuartusProjectService.getInstance(it).active } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = QuartusProjectService.getInstance(project)
        QuartusFiles.open(project, service.active?.settingsFile(service.activeRevisionName ?: return) ?: return)
    }
}

/** Opens a tab of the Quartus tool window, for those who look for it in the menu. */
abstract class QuartusShowTabAction(private val tab: String) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { QuartusProjectService.getInstance(it).active } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        QuartusToolWindow.show(e.project ?: return, tab)
    }
}

class QuartusShowOverviewAction : QuartusShowTabAction(QuartusToolWindow.OVERVIEW)
class QuartusShowPinsAction : QuartusShowTabAction(QuartusToolWindow.PINS)
class QuartusShowMessagesAction : QuartusShowTabAction(QuartusToolWindow.MESSAGES)

/** Looks for project files and reports again, for what changed outside the IDE. */
class QuartusRefreshAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val service = QuartusProjectService.getInstance(e.project ?: return)
        service.rescan()
        service.outputChanged()
    }
}

internal object QuartusFiles {
    /** Opens [file] in the editor, at [line] counted from 1 when that is given. Does nothing for a file that is gone. */
    fun open(project: Project, file: Path, line: Int? = null) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)?.takeIf { !it.isDirectory } ?: return
        if (line == null) FileEditorManager.getInstance(project).openFile(virtualFile, true)
        else OpenFileDescriptor(project, virtualFile, maxOf(line - 1, 0), 0).navigate(true)
    }
}
