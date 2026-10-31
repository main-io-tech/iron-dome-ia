package com.mio.review.comando

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
import com.mio.review.radar.ReviewCommentService
import com.mio.review.radar.editorAndFile
import java.nio.file.Path

/** Salva os documentos e regrava o `.md` da revisão aberta com os comentários de todos os arquivos. */
class SaveReviewAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    /** Na barra do editor, só aparece se houver pendências fora do arquivo aberto, que tem o próprio botão. */
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = project.service<ReviewCommentService>()
        val pending = service.pendingPaths()
        val current = e.editorAndFile()?.second?.let(service::relativePath)
        e.presentation.isVisible = current == null || (pending - current).isNotEmpty()
        e.presentation.isEnabled = true
        e.presentation.text = when (pending.size) {
            0, 1 -> "Salvar Todos"
            else -> "Salvar Todos (${pending.size} arquivos)"
        }
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val written = project.service<ReviewCommentService>().exportAll()
        FileDocumentManager.getInstance().saveAllDocuments()
        notifySaved(project, written.firstOrNull(), "Ordens de interceptação enviadas: revisão salva em ${ReviewCommentService.REVIEW_DIR}/")
    }
}

/** Salva o arquivo aberto e grava no `.md` só os comentários dele; os de outros arquivos continuam pendentes. */
class SaveFileReviewAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.editorAndFile()?.second
        e.presentation.isEnabledAndVisible = project != null && file != null &&
            project.service<ReviewCommentService>().hasPendingChanges(file)
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (editor, file) = e.editorAndFile() ?: return
        val service = project.service<ReviewCommentService>()
        val written = service.exportFile(file)
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val others = service.pendingPaths().size
        val title = buildString {
            append("Alvos de ${file.name} na mira: comentários salvos")
            if (others > 0) append(". ${if (others == 1) "1 outro arquivo continua" else "$others outros arquivos continuam"} com comentários não salvos")
        }
        notifySaved(project, written, title)
    }
}

private fun notifySaved(project: Project, written: Path?, title: String) {
    val group = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
    if (written == null) {
        group.createNotification("Céu limpo: nenhuma revisão aberta com comentários salvos.", NotificationType.INFORMATION).notify(project)
        return
    }
    val notification = group.createNotification(title, written.fileName.toString(), NotificationType.INFORMATION)
    notification.addAction(NotificationAction.createSimpleExpiring("Abrir ${written.fileName}") {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(written)
            ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    })
    notification.notify(project)
}
