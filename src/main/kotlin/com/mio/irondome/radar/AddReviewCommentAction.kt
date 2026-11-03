package com.mio.irondome.radar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

class AddReviewCommentAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && e.editorAndFile() != null
        val pullRequest = project?.service<ReviewCommentService>()?.activePullRequest ?: 0
        if (pullRequest != 0) {
            e.presentation.text = "Iron Dome IA: Comentar no PR #$pullRequest"
            e.presentation.description = "Comenta a seleção ou a linha atual no PR em revisão"
            e.presentation.icon = ReviewIcons.addPullRequestComment
        } else {
            e.presentation.text = templatePresentation.text
            e.presentation.description = templatePresentation.description
            e.presentation.icon = templatePresentation.icon
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (editor, _) = e.editorAndFile() ?: return
        CommentForm.open(editor, editor.selectedLines())
    }
}
