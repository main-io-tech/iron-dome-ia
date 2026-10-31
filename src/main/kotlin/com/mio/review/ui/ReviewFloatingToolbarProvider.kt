package com.mio.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.mio.review.ReviewCommentService
import com.mio.review.ReviewCommentsListener
import com.mio.review.ui.toolwindow.ChangeDiff

/**
 * Barra no canto superior direito do editor: "Ver Diff" quando o arquivo tem alterações não
 * commitadas, e "Salvar Este Arquivo" e "Salvar Todos" enquanto há comentários não salvos. Cada
 * ação decide se aparece conforme o arquivo aberto.
 */
class ReviewFloatingToolbarProvider : AbstractFloatingToolbarProvider(ACTION_GROUP) {

    override val autoHideable: Boolean = false

    override fun isApplicable(dataContext: DataContext): Boolean {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return false
        return ReviewGutter.isCommentable(editor)
    }

    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val service = project.service<ReviewCommentService>()
        val update = {
            val changed = file != null && ChangeDiff.changeOf(project, file) != null
            if (service.hasPendingChanges() || changed) component.scheduleShow() else component.scheduleHide()
        }
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { update() })
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = ApplicationManager.getApplication().invokeLater({ update() }, project.disposed)
        })
        update()
    }

    companion object {
        const val ACTION_GROUP = "MioReview.FloatingToolbar"
    }
}
