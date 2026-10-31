package com.mio.review.impacto

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Analisa as dependências pelo menu de contexto: no editor, da classe sob o cursor; no Project view,
 * no Structure view e na barra de navegação, da classe selecionada; na aba do arquivo, da primeira
 * classe dele.
 */
class ShowDependencyDiagramAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (file, offset) = target(e) ?: return
        DependencyTab.open(project) { it.analyzeAt(file, offset) }
    }

    private fun target(e: AnActionEvent): Pair<PsiFile, Int?>? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR)
        val element = e.getData(CommonDataKeys.PSI_ELEMENT)?.takeIf { it !is PsiFile && it !is PsiDirectory }?.navigationElement
        val file = e.getData(CommonDataKeys.PSI_FILE)
            ?: element?.containingFile
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { PsiManager.getInstance(project).findFile(it) }
            ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (virtualFile.extension?.lowercase() !in SUPPORTED_EXTENSIONS) return null
        if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return null

        // No editor, PSI_ELEMENT é o alvo da referência sob o cursor, não a classe em que ele está.
        val offset = when {
            editor != null && FileDocumentManager.getInstance().getFile(editor.document) == virtualFile -> editor.caretModel.offset
            editor == null && element?.containingFile == file -> element.textOffset
            else -> null
        }
        return file to offset
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("java", "kt")
    }
}
