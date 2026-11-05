package com.mio.irondome.radar

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.IronDomeIcons
import java.awt.BorderLayout
import java.awt.Dimension
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
        val pullRequest = service.activePullRequest
        val dialog = TargetCommentDialog(
            project,
            title = when {
                pullRequest != 0 && target.isDirectory -> "Comentário para o PR #$pullRequest sobre o pacote"
                pullRequest != 0 -> "Comentário para o PR #$pullRequest sobre o arquivo inteiro"
                target.isDirectory -> "Marcar pacote como alvo"
                else -> "Marcar arquivo inteiro como alvo"
            },
            subject = if (target.isDirectory) "$path/" else path,
            hint = hintFor(target.isDirectory, pullRequest),
            initialText = "",
            initialDiscuss = CommentForm.lastDiscuss,
            initialSuggestion = "",
            pullRequest = pullRequest,
            codeContext = target.takeUnless { it.isDirectory },
            okText = if (pullRequest != 0) "Adicionar ao PR" else "Marcar Alvo",
        )
        if (!dialog.showAndGet()) return
        if (pullRequest == 0) CommentForm.lastDiscuss = dialog.discuss
        service.addTargetComment(target, dialog.text, dialog.discuss, dialog.suggestion)
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
            title = if (comment.pullRequest != 0) "Editar ${comment.id} do PR #${comment.pullRequest}" else "Editar alvo ${comment.id}",
            subject = if (isPackage) "${comment.filePath}/" else comment.filePath,
            hint = hintFor(isPackage, comment.pullRequest),
            initialText = comment.text,
            initialDiscuss = comment.discuss,
            initialSuggestion = comment.suggestion,
            pullRequest = comment.pullRequest,
            codeContext = service.findFile(comment.filePath)?.takeUnless { isPackage },
            okText = "Atualizar",
        )
        if (dialog.showAndGet()) service.update(comment, dialog.text, dialog.discuss, dialog.suggestion)
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

    private fun hintFor(isPackage: Boolean, pullRequest: Int) = when {
        pullRequest != 0 && isPackage ->
            "Vai no texto geral da revisão do PR #$pullRequest, em Enviar ao GitHub, na aba Patrulha."
        pullRequest != 0 ->
            "Vai como comentário do arquivo no PR #$pullRequest (ou no texto geral, se o PR não altera o arquivo), em Enviar ao GitHub."
        isPackage -> "Vale para todos os arquivos do pacote. Vai para o .md com Disparar Todos (Ctrl+S)."
        else -> "Vale para o arquivo todo. Vai para o .md com Disparar Este Arquivo ou Disparar Todos (Ctrl+S)."
    }
}

private class TargetCommentDialog(
    project: Project,
    title: String,
    private val subject: String,
    private val hint: String,
    initialText: String,
    initialDiscuss: Boolean,
    initialSuggestion: String,
    pullRequest: Int,
    /** O arquivo comentado, para o código de exemplo ser escrito no contexto dele; nulo em pacotes. */
    codeContext: VirtualFile?,
    okText: String,
) : DialogWrapper(project) {

    private val forPullRequest = pullRequest != 0
    private val textArea = JBTextArea(initialText, 6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = if (forPullRequest) "O que o autor do PR deve mudar e por quê" else "Descreva a correção que a IA deve fazer"
    }
    private val code: SuggestionEditor? = when {
        !forPullRequest -> null
        codeContext != null -> SuggestionEditor.atEnd(project, codeContext, initialSuggestion)
        else -> SuggestionEditor.plain(project, FileTypes.PLAIN_TEXT, initialSuggestion)
    }
    private val applyOption = JBRadioButton("Aplicar direto", !initialDiscuss).apply {
        toolTipText = "A IA pode alterar os arquivos sem consultar você"
    }
    private val discussOption = JBRadioButton("Discutir antes de alterar", initialDiscuss).apply {
        toolTipText = "A IA apresenta a análise e a proposta e só altera depois que você concordar"
    }

    val text: String get() = textArea.text.trim()
    val discuss: Boolean get() = !forPullRequest && discussOption.isSelected
    val suggestion: String get() = code?.text.orEmpty()

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
        val icon = if (forPullRequest) IronDomeIcons.patrulha else ReviewIcons.addComment
        add(
            JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                add(JBLabel(subject, icon, SwingConstants.LEFT).apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
                add(JBLabel(hint).apply { foreground = ReviewColors.mutedForeground }, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        add(
            if (code != null) {
                JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    add(
                        JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                            add(JBLabel("Código de exemplo (opcional)").apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
                            add(
                                JBLabel("Escreva no trecho destacado como no editor, com Ctrl+Espaço; o arquivo não muda.").apply {
                                    foreground = ReviewColors.mutedForeground
                                },
                                BorderLayout.SOUTH,
                            )
                        },
                        BorderLayout.NORTH,
                    )
                    add(code.field, BorderLayout.CENTER)
                }
            } else {
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                    add(applyOption)
                    add(discussOption)
                }
            },
            BorderLayout.SOUTH,
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doValidate(): ValidationInfo? = when {
        text.isNotEmpty() -> null
        forPullRequest -> ValidationInfo("Descreva o que o autor do PR deve mudar", textArea)
        else -> ValidationInfo("Descreva o que a IA deve fazer", textArea)
    }
}
