package com.mio.review.listeners

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.mio.review.ReviewCommentService

class ReviewSaveListener : FileDocumentManagerListener {

    /** Chamado também quando nenhum documento foi alterado, o que cobre o Ctrl+S logo após comentar. */
    override fun beforeAllDocumentsSaving() {
        services().forEach { it.exportPending() }
    }

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        services().forEach { it.exportFile(file) }
    }

    private fun services(): List<ReviewCommentService> =
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .map { it.service<ReviewCommentService>() }
}
