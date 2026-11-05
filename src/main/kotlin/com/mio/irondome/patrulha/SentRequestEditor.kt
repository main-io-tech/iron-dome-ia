package com.mio.irondome.patrulha

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.SuggestionEditor
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Edita um pedido já enviado: o texto e o código sugerido, no comentário do GitHub e no `.md` do PR. */
object SentRequestEditor {

    fun edit(project: Project, number: Int, request: ChangeRequest) {
        val patrulha = PatrulhaService.getInstance(project)
        val context = patrulha.context()
        if (context == null) {
            notify(project, "Não foi possível editar ${request.id}", "O projeto não tem um remote do GitHub.", NotificationType.ERROR)
            return
        }
        val file = project.service<ReviewCommentService>().findFile(request.path)?.takeUnless { it.isDirectory }
        val dialog = SentRequestDialog(project, number, request, file)
        if (!dialog.showAndGet()) return
        val text = dialog.text
        val suggestion = dialog.suggestion
        object : Task.Backgroundable(project, "Atualizando ${request.id} no PR #$number", true) {
            private var warning: String? = null

            override fun run(indicator: ProgressIndicator) {
                warning = patrulha.editRequest(context, number, request.id, text, suggestion)
            }

            override fun onSuccess() {
                notify(
                    project,
                    "${request.id} atualizado no PR #$number",
                    warning ?: "O comentário no GitHub e o .md do PR têm o texto novo.",
                    if (warning != null) NotificationType.WARNING else NotificationType.INFORMATION,
                )
            }

            override fun onThrowable(error: Throwable) {
                notify(project, "Não foi possível atualizar ${request.id}", error.message ?: error.javaClass.simpleName, NotificationType.ERROR)
            }
        }.queue()
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, type)
            .setIcon(IronDomeIcons.patrulha)
            .notify(project)
    }
}

private class SentRequestDialog(
    private val project: Project,
    number: Int,
    private val request: ChangeRequest,
    private val file: VirtualFile?,
) : DialogWrapper(project) {

    private val textArea = JBTextArea(request.text, 6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "O que o autor do PR deve mudar e por quê"
    }
    private val suggestOption = JBCheckBox(
        if (request.target == CommentTarget.LINES) "Sugerir código" else "Código de exemplo",
        request.suggestion.isNotBlank(),
    )
    private var code: SuggestionEditor? = null
    private val codePanel = JPanel(BorderLayout()).apply { isVisible = false }

    val text: String get() = textArea.text.trim()
    val suggestion: String get() = if (suggestOption.isSelected) code?.text.orEmpty() else ""

    init {
        title = "Editar ${request.id} do PR #$number"
        setOKButtonText("Atualizar no GitHub")
        suggestOption.addActionListener { showCode(suggestOption.isSelected) }
        init()
        showCode(suggestOption.isSelected)
    }

    private fun showCode(show: Boolean) {
        if (show && code == null) {
            code = createCode().also { codePanel.add(it.field, BorderLayout.CENTER) }
        }
        codePanel.isVisible = show
        pack()
    }

    /** Nas linhas, a cópia do arquivo traz o trecho onde ele está agora, ou já com a sugestão enviada. */
    private fun createCode(): SuggestionEditor {
        val file = file ?: return SuggestionEditor.plain(project, FileTypes.PLAIN_TEXT, request.suggestion)
        if (request.target != CommentTarget.LINES) return SuggestionEditor.atEnd(project, file, request.suggestion)
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return SuggestionEditor.plain(project, file.fileType, request.suggestion)
        val lines = SnippetLocator.locate(document.text, request.snippet, request.startLine - 1..request.endLine - 1)
        return SuggestionEditor.forLines(project, file, lines, request.suggestion)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel("${request.path}, ${request.location}", IronDomeIcons.patrulha, SwingConstants.LEFT).apply { font = JBFont.label().asBold() })
                add(
                    JBLabel(
                        if (request.commentId != null) "O comentário no GitHub é trocado pelo texto novo; as respostas da conversa continuam."
                        else "Este pedido está no texto geral da revisão: só o .md muda, e o GitHub se edita pelo navegador.",
                    ).apply {
                        componentStyle = UIUtil.ComponentStyle.SMALL
                        foreground = UIUtil.getContextHelpForeground()
                    },
                )
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(suggestOption, BorderLayout.NORTH)
                add(codePanel, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doValidate(): ValidationInfo? =
        if (text.isEmpty()) ValidationInfo("Descreva o que o autor do PR deve mudar", textArea) else null
}
