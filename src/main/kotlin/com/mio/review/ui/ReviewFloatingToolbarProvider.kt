package com.mio.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.mio.review.ReviewCommentService
import com.mio.review.ReviewCommentsListener

/**
 * Mostra "Salvar Este Arquivo" e "Salvar Todos" no canto superior direito do editor enquanto há
 * comentários não salvos; cada ação decide se aparece conforme o arquivo aberto.
 */
class ReviewFloatingToolbarProvider : AbstractFloatingToolbarProvider(ACTION_GROUP) {

    override val autoHideable: Boolean = false

    override fun isApplicable(dataContext: DataContext): Boolean {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return false
        return ReviewGutter.isCommentable(editor)
    }

    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        val project = dataContext.getData(CommonDataKeys.EDITOR)?.project ?: return
        val service = project.service<ReviewCommentService>()
        val update = {
            if (service.hasPendingChanges()) component.scheduleShow() else component.scheduleHide()
        }
        project.messageBus.connect(parentDisposable)
            .subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { update() })
        update()
    }

    companion object {
        const val ACTION_GROUP = "MioReview.FloatingToolbar"
    }
}
