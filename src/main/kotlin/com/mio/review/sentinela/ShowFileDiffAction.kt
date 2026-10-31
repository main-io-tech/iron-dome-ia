package com.mio.review.sentinela

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.vcsUtil.VcsUtil
import com.mio.review.radar.editorAndFile

/** Na barra do editor, só aparece se o arquivo aberto tiver alterações não commitadas. */
class ShowFileDiffAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.editorAndFile()?.second
        e.presentation.isEnabledAndVisible = project != null && file != null && ChangeDiff.changeOf(project, file) != null
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.editorAndFile()?.second ?: return
        val change = ChangeDiff.changeOf(project, file) ?: return
        val path = VcsUtil.getFilePath(file)
        project.service<ChangeDiff>().show(listOf(ChangeDiff.Item(change, path, ChangesPanel.relativePath(project, path.path))), 0)
    }
}
