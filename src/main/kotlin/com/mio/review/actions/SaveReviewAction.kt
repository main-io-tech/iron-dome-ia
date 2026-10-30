package com.mio.review.actions

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.mio.review.ReviewCommentService
import java.nio.file.Path

/** Salva os documentos e regrava o `.md` da revisão aberta. */
class SaveReviewAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val written = project.service<ReviewCommentService>().exportAll()
        FileDocumentManager.getInstance().saveAllDocuments()
        notifySaved(project, written)
    }

    private fun notifySaved(project: Project, written: List<Path>) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("mio-review-ia")
        if (written.isEmpty()) {
            group.createNotification("Nenhuma revisão aberta com comentários.", NotificationType.INFORMATION)
                .notify(project)
            return
        }
        val names = written.joinToString("<br>") { it.fileName.toString() }
        val notification = group.createNotification(
            "Revisão salva em ${ReviewCommentService.REVIEW_DIR}/",
            names,
            NotificationType.INFORMATION,
        )
        written.take(3).forEach { path ->
            notification.addAction(NotificationAction.createSimpleExpiring("Abrir ${path.fileName}") {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            })
        }
        notification.notify(project)
    }
}
