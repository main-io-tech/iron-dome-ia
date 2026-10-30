package com.mio.review.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.mio.review.ui.CommentForm

class AddReviewCommentAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.editorAndFile() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (editor, _) = e.editorAndFile() ?: return
        CommentForm.open(editor, editor.selectedLines())
    }
}
