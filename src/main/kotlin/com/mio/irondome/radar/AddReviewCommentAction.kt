package com.mio.irondome.radar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

class AddReviewCommentAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val target = e.editorAndFile()
        e.presentation.isVisible = project != null && target != null
        val service = project?.service<ReviewCommentService>()
        e.presentation.isEnabled = target != null && service?.accepts(target.first.document) == true
        val pullRequest = service?.activePullRequest ?: 0
        if (pullRequest != 0) {
            val reviewOutput = target != null && service?.relativePath(target.second)?.let(ReviewCommentService::isReviewOutput) == true
            e.presentation.text = "Iron Dome IA: Comentar no PR #$pullRequest"
            e.presentation.description = if (reviewOutput) {
                "Os arquivos gerados pela revisão, em ${ReviewCommentService.REVIEW_DIR}/, não fazem parte do PR e não recebem comentário"
            } else {
                "Comenta a seleção ou a linha atual no PR em revisão"
            }
            e.presentation.icon = ReviewIcons.addPullRequestComment
        } else {
            e.presentation.text = templatePresentation.text
            e.presentation.description = templatePresentation.description
            e.presentation.icon = templatePresentation.icon
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (editor, _) = e.editorAndFile() ?: return
        if (e.project?.service<ReviewCommentService>()?.accepts(editor.document) != true) return
        CommentForm.open(editor, editor.selectedLines())
    }
}
