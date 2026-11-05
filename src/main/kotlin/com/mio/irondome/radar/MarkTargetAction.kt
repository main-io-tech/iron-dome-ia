package com.mio.irondome.radar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.mio.irondome.base.IdeSupport
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

/** Comenta o arquivo ou o pacote selecionado inteiro, pela árvore do projeto, pela aba ou pelo editor. */
class MarkTargetAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = !IdeSupport.isLimited(RADAR) &&
            project != null && file != null && project.service<ReviewCommentService>().targetPath(file) != null
        val pullRequest = project?.service<ReviewCommentService>()?.activePullRequest ?: 0
        if (file != null) {
            e.presentation.text = when {
                pullRequest != 0 && file.isDirectory -> "Iron Dome IA: Comentar Pacote no PR #$pullRequest"
                pullRequest != 0 -> "Iron Dome IA: Comentar Arquivo Inteiro no PR #$pullRequest"
                file.isDirectory -> "Iron Dome IA: Marcar Pacote como Alvo"
                else -> "Iron Dome IA: Marcar Arquivo Inteiro como Alvo"
            }
        }
        e.presentation.icon = if (pullRequest != 0) ReviewIcons.addPullRequestComment else templatePresentation.icon
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        TargetComments.mark(project, file)
    }
}
