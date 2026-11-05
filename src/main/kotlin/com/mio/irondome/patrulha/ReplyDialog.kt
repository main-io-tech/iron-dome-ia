package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import javax.swing.JComponent

/**
 * Responde a [to], de [discussion]: na conversa da linha, ou, nos comentários gerais e nas revisões, com um
 * comentário novo no PR que cita o original. [draft] diz se a resposta da linha vai para o rascunho da revisão.
 */
internal class ReplyDialog(
    project: Project,
    private val number: Int,
    private val discussion: Discussion,
    private val to: DiscussionComment,
    private val draft: Boolean,
) : DialogWrapper(project, true) {

    private val original = JBTextArea(to.body.trim()).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = UIUtil.getPanelBackground()
    }
    private val reply = JBTextArea(5, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Sua resposta a @${to.author}"
    }

    val text: String get() = reply.text

    init {
        title = "Responder a @${to.author} no PR #$number"
        setOKButtonText(if (discussion.repliesInPlace && draft) "Responder no Rascunho" else "Responder")
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = reply

    override fun doValidate(): ValidationInfo? = if (reply.text.isBlank()) ValidationInfo("Escreva a resposta.", reply) else null

    override fun createCenterPanel(): JComponent {
        val where = when (discussion.kind) {
            DiscussionKind.THREAD -> listOfNotNull(discussion.path, discussion.line?.let { "linha $it" } ?: "arquivo inteiro").joinToString(", ")
            DiscussionKind.CONVERSATION -> "conversa do PR"
            DiscussionKind.REVIEW -> "texto da revisão"
        }
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>@${escape(to.author)}</b> · ${escape(Discussions.formatDate(to.createdAt))} · ${escape(where)}</html>"))
            .addComponent(JBScrollPane(original).apply { preferredSize = JBUI.size(600, 110) })
            .addComponent(TitledSeparator("Resposta"))
            .addComponent(JBScrollPane(reply).apply { preferredSize = JBUI.size(600, 110) })
        val notice = when {
            !discussion.repliesInPlace ->
                "O GitHub não tem respostas nos comentários gerais nem nas revisões: a resposta vai como comentário novo na conversa do PR, " +
                    "citando o de @${to.author}, e todos já veem."
            draft -> "O PR tem um rascunho de revisão seu: a resposta entra nele e só aparece para os outros quando você publicar o rascunho."
            else -> "A resposta entra na conversa da linha e todos já veem."
        }
        builder.addComponent(NoticeBox(AllIcons.General.Information, Tone.BLUE).apply { text = notice })
        return builder.panel
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
