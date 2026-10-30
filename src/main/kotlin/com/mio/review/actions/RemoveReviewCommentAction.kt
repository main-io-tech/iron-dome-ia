package com.mio.review.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.mio.review.ReviewCommentService

/** Marca para remoção, ao salvar, os comentários não resolvidos que cruzam a seleção ou a linha do cursor. */
class RemoveReviewCommentAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.editorAndFile()?.first
        e.presentation.isEnabledAndVisible = project != null && editor != null &&
            project.service<ReviewCommentService>().commentsAt(editor.document, editor.selectedLines())
                .any { !it.resolved }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (editor, _) = e.editorAndFile() ?: return
        val service = project.service<ReviewCommentService>()
        service.markForRemoval(service.commentsAt(editor.document, editor.selectedLines()))
    }
}
