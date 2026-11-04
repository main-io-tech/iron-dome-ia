package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Pill
import com.mio.irondome.base.Tone
import com.mio.irondome.radar.CommentTarget
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/** Confirma o envio dos rascunhos como uma revisão do PR, mostrando onde cada um vai entrar. */
internal class SendReviewDialog(
    project: Project,
    private val pr: PullRequest,
    private val planned: List<PlannedRequest>,
    /** Aviso quando o código local não é o head do PR e as linhas podem não bater. */
    private val warning: String?,
    /** O PR é da conta do GitHub em uso: o GitHub não deixa aprovar nem solicitar alterações. */
    private val ownPr: Boolean = false,
) : DialogWrapper(project, true) {

    private val approve = JBRadioButton("Aprovar, com os comentários como sugestões")
    private val requestChanges = JBRadioButton("Solicitar alterações, bloqueando o merge até serem atendidas", !ownPr)
    private val commentOnly = JBRadioButton("Só comentar, sem aprovar nem bloquear o merge", ownPr)
    private val asDraftBox = JBCheckBox("Enviar como rascunho no GitHub: só você vê, e dá para editar e apagar antes de publicar", true).apply {
        addActionListener { updateMode() }
    }

    val asDraft: Boolean get() = asDraftBox.isSelected
    private val general = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Texto geral da revisão (opcional)"
    }

    val verdict: ReviewVerdict
        get() = when {
            approve.isSelected -> ReviewVerdict.APPROVE
            requestChanges.isSelected -> ReviewVerdict.REQUEST_CHANGES
            else -> ReviewVerdict.COMMENT
        }

    val event: String get() = verdict.event

    val generalText: String get() = general.text

    init {
        title = "Enviar Pedidos ao PR #${pr.number}"
        ButtonGroup().apply {
            add(approve)
            add(requestChanges)
            add(commentOnly)
        }
        init()
        updateMode()
    }

    /** No rascunho, o tipo da revisão é escolhido ao publicar. */
    private fun updateMode() {
        approve.isEnabled = !asDraft && !ownPr
        requestChanges.isEnabled = !asDraft && !ownPr
        commentOnly.isEnabled = !asDraft
        setOKButtonText(if (asDraft) "Enviar Rascunho" else "Publicar no GitHub")
    }

    override fun getPreferredFocusedComponent(): JComponent = general

    override fun createCenterPanel(): JComponent {
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        planned.forEach { list.add(row(it)) }
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>#${pr.number}</b> ${escape(pr.title)}<br>${escape("@${pr.author} · ${pr.headRef} → ${pr.baseRef}")}</html>"))
        warning?.let { builder.addComponent(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { text = it }) }
        return builder
            .addComponent(TitledSeparator("Pedidos (${planned.size})"))
            .addComponent(JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                preferredSize = JBUI.size(640, minOf(60 + planned.size * 52, 300))
            })
            .addComponent(comment("Comentários de linha só valem nas linhas que o PR altera; os demais vão no arquivo inteiro ou no texto geral."))
            .addComponent(TitledSeparator("Revisão"))
            .addComponent(asDraftBox)
            .addComponent(comment("Os rascunhos ficam na revisão pendente do GitHub, que o autor do PR não vê. Publique pela Patrulha (Publicar Rascunho) ou pelo GitHub."))
            .addComponent(approve)
            .addComponent(requestChanges)
            .addComponent(commentOnly)
            .addComponent(
                comment(
                    if (ownPr) "O PR é seu: o GitHub não deixa aprovar nem solicitar alterações no próprio PR, só comentar."
                    else "O GitHub não deixa aprovar nem solicitar alterações no próprio PR: nesse caso, use Só comentar.",
                ),
            )
            .addComponent(JBScrollPane(general).apply { preferredSize = JBUI.size(640, 80) })
            .addComponent(comment("Os pedidos ficam em iron-dome/pull-requests/pr-${pr.number}.md para você verificar depois se foram atendidos."))
            .panel
    }

    private fun row(request: PlannedRequest): JComponent {
        val comment = request.comment
        val where = when (comment.target) {
            CommentTarget.LINES -> if (comment.startLine == comment.endLine) "linha ${comment.startLine + 1}" else "linhas ${comment.startLine + 1}–${comment.endLine + 1}"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.PACKAGE -> "pacote inteiro"
        }
        val (label, tone) = when (request.placement) {
            Placement.INLINE -> "Na linha" to Tone.GREEN
            Placement.FILE -> "No arquivo" to Tone.BLUE
            Placement.BODY -> "No texto geral" to Tone.GRAY
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel(comment.id).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBLabel("${comment.filePath}, $where"))
            add(Pill(label, tone))
            if (comment.suggestion.isNotBlank()) {
                add(
                    if (request.placement == Placement.INLINE) Pill("Sugestão aplicável", Tone.PURPLE).apply {
                        toolTipText = "No GitHub, o autor aplica o código sugerido com Commit suggestion"
                    }
                    else Pill("Código de exemplo", Tone.PURPLE).apply {
                        toolTipText = "Fora do diff o GitHub não aplica sugestões: o código vai como exemplo"
                    },
                )
            }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(6)
            add(header)
            add(comment(comment.text.trim().lines().first().take(140)).apply { border = JBUI.Borders.emptyLeft(8) })
        }
    }

    private fun comment(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
