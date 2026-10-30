package com.mio.review.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.mio.review.ReviewCommentService

class RemoveResolvedCommentsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.service<ReviewCommentService>().resolvedComments().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = e.project?.service<ReviewCommentService>() ?: return
        service.dismiss(service.resolvedComments())
    }
}
