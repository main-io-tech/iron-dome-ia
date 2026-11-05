package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * O veredito do PR: aprovar, solicitar alterações ou só comentar, com o texto geral. Os comentários não enviados
 * ([unsent]) e os do rascunho no GitHub ([drafts]) vão junto, na mesma revisão.
 */
internal class VerdictDialog(
    project: Project,
    private val pr: PullRequest,
    initial: ReviewVerdict,
    private val unsent: Int,
    private val drafts: Int,
    private val ownPr: Boolean,
) : DialogWrapper(project, true) {

    private val options = ReviewVerdict.entries.associateWith { verdict ->
        JBRadioButton(
            when (verdict) {
                ReviewVerdict.APPROVE -> "Aprovar: o PR pode ser mergeado"
                ReviewVerdict.REQUEST_CHANGES -> "Solicitar alterações: bloqueia o merge até serem atendidas"
                ReviewVerdict.COMMENT -> "Só comentar, sem aprovar nem bloquear o merge"
            },
        ).apply {
            isEnabled = !ownPr || verdict.allowedOnOwnPr
            addActionListener { updateTexts() }
        }
    }
    private val body = JBTextArea(5, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    val verdict: ReviewVerdict get() = options.entries.first { it.value.isSelected }.key

    val text: String get() = body.text

    init {
        ButtonGroup().apply { options.values.forEach(::add) }
        options.getValue(if (ownPr && !initial.allowedOnOwnPr) ReviewVerdict.COMMENT else initial).isSelected = true
        init()
        updateTexts()
    }

    private val comments get() = unsent + drafts

    private fun updateTexts() {
        val verdict = verdict
        val (dialogTitle, button) = when (verdict) {
            ReviewVerdict.APPROVE -> "Aprovar o PR #${pr.number}" to "Aprovar"
            ReviewVerdict.REQUEST_CHANGES -> "Solicitar Alterações no PR #${pr.number}" to "Solicitar Alterações"
            ReviewVerdict.COMMENT -> "Comentar no PR #${pr.number}" to "Comentar"
        }
        title = dialogTitle
        setOKButtonText(button)
        body.emptyText.text = when {
            verdict.needsBody && comments == 0 -> "O que precisa mudar e por quê (obrigatório)"
            verdict == ReviewVerdict.APPROVE -> "Texto da aprovação (opcional), como \"Ficou ótimo!\""
            else -> "Texto geral da revisão (opcional)"
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = body

    override fun doValidate(): ValidationInfo? =
        if (verdict.needsBody && comments == 0 && body.text.isBlank()) {
            ValidationInfo("Sem comentários na revisão, o GitHub exige um texto para ${verdict.label.lowercase()}.", body)
        } else {
            null
        }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>#${pr.number}</b> ${escape(pr.title)}<br>${escape("@${pr.author} · ${pr.headRef} → ${pr.baseRef}")}</html>"))
        if (ownPr) {
            builder.addComponent(
                NoticeBox(AllIcons.General.Information, Tone.BLUE).apply {
                    text = "O PR é seu: o GitHub não deixa aprovar nem solicitar alterações no próprio PR, só comentar."
                },
            )
        }
        builder.addComponent(TitledSeparator("Veredito"))
        options.values.forEach { builder.addComponent(it) }
        builder.addComponent(TitledSeparator("Texto"))
            .addComponent(JBScrollPane(body).apply { preferredSize = JBUI.size(600, 100) })
        val along = listOfNotNull(
            unsent.takeIf { it > 0 }?.let { "${plural(it, "comentário não enviado", "comentários não enviados")} deste PR" },
            drafts.takeIf { it > 0 }?.let { "o rascunho no GitHub (${plural(it, "comentário", "comentários")})" },
        )
        builder.addComponent(
            comment(
                if (along.isEmpty()) "A revisão vai só com o veredito e o texto."
                else "Vão junto, na mesma revisão: ${along.joinToString(" e ")}. Depois de publicada, o autor do PR vê tudo.",
            ),
        )
        return builder.panel
    }

    private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

    private fun comment(text: String) = JBLabel("<html>${escape(text)}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
