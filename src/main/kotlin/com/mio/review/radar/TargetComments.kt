package com.mio.review.radar

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Comentários sobre um arquivo ou pacote inteiro. Sem trecho para embutir o formulário no editor,
 * eles são escritos e editados num diálogo.
 */
object TargetComments {

    fun mark(project: Project, target: VirtualFile) {
        val service = project.service<ReviewCommentService>()
        val path = service.targetPath(target) ?: return
        val dialog = TargetCommentDialog(
            project,
            title = if (target.isDirectory) "Marcar pacote como alvo" else "Marcar arquivo inteiro como alvo",
            subject = if (target.isDirectory) "$path/" else path,
            hint = hintFor(target.isDirectory),
            initialText = "",
            initialDiscuss = CommentForm.lastDiscuss,
            okText = "Comentar",
        )
        if (!dialog.showAndGet()) return
        CommentForm.lastDiscuss = dialog.discuss
        service.addTargetComment(target, dialog.text, dialog.discuss)
    }

    /** Trechos são editados no próprio editor, em [editor] ou no arquivo aberto na linha do comentário. */
    fun edit(project: Project, comment: ReviewComment, editor: Editor? = null) {
        val service = project.service<ReviewCommentService>()
        if (!service.isEditable(comment)) return
        if (comment.target == CommentTarget.LINES) {
            (editor ?: openAt(project, comment))?.let { CommentForm.edit(it, comment) }
            return
        }
        val isPackage = comment.target == CommentTarget.PACKAGE
        val dialog = TargetCommentDialog(
            project,
            title = "Editar comentário ${comment.id}",
            subject = if (isPackage) "${comment.filePath}/" else comment.filePath,
            hint = hintFor(isPackage),
            initialText = comment.text,
            initialDiscuss = comment.discuss,
            okText = "Atualizar",
        )
        if (dialog.showAndGet()) service.update(comment, dialog.text, dialog.discuss)
    }

    /** Mostra o pacote na árvore do projeto. */
    fun reveal(project: Project, path: String) {
        val dir = project.service<ReviewCommentService>().findFile(path) ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        toolWindow.activate { ProjectView.getInstance(project).select(null, dir, true) }
    }

    private fun openAt(project: Project, comment: ReviewComment): Editor? {
        val service = project.service<ReviewCommentService>()
        val file = service.findFile(comment.filePath) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = service.currentLines(comment, document).first
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, line, 0), true)
    }

    private fun hintFor(isPackage: Boolean) =
        if (isPackage) "Vale para todos os arquivos do pacote. Vai para o .md com Salvar Todos (Ctrl+S)."
        else "Vale para o arquivo todo. Vai para o .md ao salvar o arquivo ou com Salvar Todos (Ctrl+S)."
}

private class TargetCommentDialog(
    project: Project,
    title: String,
    private val subject: String,
    private val hint: String,
    initialText: String,
    initialDiscuss: Boolean,
    okText: String,
) : DialogWrapper(project) {

    private val textArea = JBTextArea(initialText, 6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Descreva a correção que a IA deve fazer"
    }
    private val applyOption = JBRadioButton("Aplicar direto", !initialDiscuss).apply {
        toolTipText = "A IA pode alterar os arquivos sem consultar você"
    }
    private val discussOption = JBRadioButton("Discutir antes de alterar", initialDiscuss).apply {
        toolTipText = "A IA apresenta a análise e a proposta e só altera depois que você concordar"
    }

    val text: String get() = textArea.text.trim()
    val discuss: Boolean get() = discussOption.isSelected

    init {
        this.title = title
        setOKButtonText(okText)
        ButtonGroup().apply {
            add(applyOption)
            add(discussOption)
        }
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(
            JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                add(JBLabel(subject, ReviewIcons.addComment, SwingConstants.LEFT).apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
                add(JBLabel(hint).apply { foreground = ReviewColors.mutedForeground }, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                add(applyOption)
                add(discussOption)
            },
            BorderLayout.SOUTH,
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doValidate(): ValidationInfo? =
        if (text.isEmpty()) ValidationInfo("Descreva o que a IA deve fazer", textArea) else null
}
