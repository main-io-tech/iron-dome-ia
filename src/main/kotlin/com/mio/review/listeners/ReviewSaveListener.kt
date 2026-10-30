package com.mio.review.listeners

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.mio.review.ReviewCommentService

/**
 * A IDE também salva sozinha (ao fechar a aba, trocar de janela, ficar ociosa...), então salvar
 * documentos não grava o `.md`: só guarda a posição atual dos comentários.
 */
class ReviewSaveListener : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .forEach { it.service<ReviewCommentService>().syncPositions(file) }
    }
}

/** Grava o `.md` só quando o usuário salva explicitamente, com Ctrl+S ou pelo menu. */
class ReviewSaveActionListener : AnActionListener {

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (ActionManager.getInstance().getId(action) !in SAVE_ACTIONS) return
        val project = event.project?.takeIf { !it.isDisposed } ?: return
        project.service<ReviewCommentService>().exportOnSave()
    }

    private companion object {
        val SAVE_ACTIONS = setOf("SaveAll", "SaveDocument")
    }
}
