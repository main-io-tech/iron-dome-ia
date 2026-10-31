package com.mio.review.radar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

/** Comenta o arquivo ou o pacote selecionado inteiro, pela árvore do projeto, pela aba ou pelo editor. */
class MarkTargetAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            project != null && file != null && project.service<ReviewCommentService>().targetPath(file) != null
        if (file != null) {
            e.presentation.text =
                if (file.isDirectory) "Iron Dome IA: Marcar Pacote como Alvo" else "Iron Dome IA: Marcar Arquivo Inteiro como Alvo"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        TargetComments.mark(project, file)
    }
}
