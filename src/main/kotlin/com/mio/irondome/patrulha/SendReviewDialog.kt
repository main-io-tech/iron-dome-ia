package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
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
import com.mio.irondome.radar.ReviewComment
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/** O que fazer com cada comentário no envio: enviar agora, guardar para outro envio ou excluir. */
internal enum class SendChoice(val label: String) {
    SEND("Enviar"),
    LATER("Depois"),
    DELETE("Excluir"),
}

/**
 * Confirma o envio dos rascunhos como uma revisão do PR, mostrando onde cada um vai entrar. Cada comentário pode
 * ser enviado agora, ficar para depois ou ser excluído.
 */
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

    private val choices = planned.map { ChoiceButtons(it) }
    private val summary = JBLabel()

    val toSend: List<ReviewComment> get() = chosen(SendChoice.SEND)

    val toDelete: List<ReviewComment> get() = chosen(SendChoice.DELETE)

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

    private fun chosen(choice: SendChoice) = choices.filter { it.choice == choice }.map { it.request.comment }

    /** No rascunho, o tipo da revisão é escolhido ao publicar; sem nada a enviar, a revisão não vale. */
    private fun updateMode() {
        val sending = toSend.size
        val deleting = toDelete.size
        val later = choices.size - sending - deleting
        asDraftBox.isEnabled = sending > 0
        general.isEnabled = sending > 0
        approve.isEnabled = sending > 0 && !asDraft && !ownPr
        requestChanges.isEnabled = sending > 0 && !asDraft && !ownPr
        commentOnly.isEnabled = sending > 0 && !asDraft
        summary.text = listOfNotNull(
            "$sending para enviar",
            "$later para depois".takeIf { later > 0 },
            "$deleting para excluir".takeIf { deleting > 0 },
        ).joinToString(" · ")
        val sendText = if (asDraft) "Enviar Rascunho" else "Publicar no GitHub"
        setOKButtonText(
            when {
                sending == 0 && deleting == 0 -> sendText
                sending == 0 -> "Excluir $deleting"
                deleting == 0 -> "$sendText ($sending)"
                else -> "$sendText ($sending) e Excluir $deleting"
            },
        )
        isOKActionEnabled = sending + deleting > 0
    }

    private fun chooseAll(choice: SendChoice) {
        choices.forEach { it.select(choice) }
        updateMode()
    }

    /** Enviar, Depois e Excluir de um comentário. */
    private inner class ChoiceButtons(val request: PlannedRequest) {
        private val buttons = SendChoice.entries.associateWith { choice ->
            JBRadioButton(choice.label, choice == SendChoice.SEND).apply { addActionListener { updateMode() } }
        }
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            val group = ButtonGroup()
            buttons.values.forEach {
                group.add(it)
                add(it)
            }
        }

        val choice: SendChoice get() = buttons.entries.first { it.value.isSelected }.key

        fun select(choice: SendChoice) {
            buttons.getValue(choice).isSelected = true
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = general

    override fun createCenterPanel(): JComponent {
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        choices.forEach { list.add(row(it)) }
        val bulk = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            add(summary.apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(ActionLink("Enviar todos") { chooseAll(SendChoice.SEND) })
            add(ActionLink("Todos para depois") { chooseAll(SendChoice.LATER) })
            add(ActionLink("Excluir todos") { chooseAll(SendChoice.DELETE) })
        }
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>#${pr.number}</b> ${escape(pr.title)}<br>${escape("@${pr.author} · ${pr.headRef} → ${pr.baseRef}")}</html>"))
        warning?.let { builder.addComponent(NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { text = it }) }
        return builder
            .addComponent(TitledSeparator("Pedidos (${planned.size})"))
            .addComponent(bulk)
            .addComponent(comment("Depois guarda o comentário para outro envio. Excluir apaga o comentário, que ainda não foi enviado ao GitHub."))
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

    private fun row(choice: ChoiceButtons): JComponent {
        val request = choice.request
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
        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(comment(comment.text.trim().lines().first().take(140)).apply { border = JBUI.Borders.emptyLeft(8) })
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(info, BorderLayout.CENTER)
            add(choice.panel, BorderLayout.EAST)
        }
    }

    private fun comment(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
