package com.mio.review.comando

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.mio.review.radar.ReviewCommentService

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

/**
 * Grava o `.md` só quando o usuário salva explicitamente: Ctrl+S (Save All) grava os comentários de
 * todos os arquivos, e Save Document só os do arquivo em foco.
 */
class ReviewSaveActionListener : AnActionListener {

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        val id = ActionManager.getInstance().getId(action)
        if (id != SAVE_ALL && id != SAVE_DOCUMENT) return
        val project = event.project?.takeIf { !it.isDisposed } ?: return
        val service = project.service<ReviewCommentService>()
        if (id == SAVE_ALL) {
            service.exportOnSave()
            return
        }
        val file = event.getData(CommonDataKeys.EDITOR)?.document?.let(FileDocumentManager.getInstance()::getFile)
            ?: event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: return
        service.exportOnSave(file)
    }

    private companion object {
        const val SAVE_ALL = "SaveAll"
        const val SAVE_DOCUMENT = "SaveDocument"
    }
}
