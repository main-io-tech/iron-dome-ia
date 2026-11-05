package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import javax.swing.JComponent

/**
 * A linha de avaliação de quem revisa, nas palavras dele: o que cobra, o que deixa passar e o tom. Vai para a IA na
 * Revisão com IA e nas respostas automáticas. Nas preferências da IDE, valendo para todos os projetos.
 */
object ReviewerGuidelines {

    private const val KEY = "IronDome.Patrulha.ReviewerGuidelines"
    const val MAX_LENGTH = 6_000

    var text: String
        get() = PropertiesComponent.getInstance().getValue(KEY).orEmpty()
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value.trim().take(MAX_LENGTH), "")

    /** A seção das instruções nas instruções da IA; vazia sem instruções. */
    fun section(guidelines: String): String {
        val text = guidelines.trim()
        if (text.isEmpty()) return ""
        return buildString {
            appendLine("## Como o revisor avalia PRs")
            appendLine()
            appendLine("Instruções do próprio revisor: o que ele cobra, o que deixa passar e o tom que prefere. Siga-as acima das")
            appendLine("preferências do time e do padrão destas instruções, exceto as regras de não alterar arquivos e do formato da resposta.")
            appendLine()
            appendLine(text)
            appendLine()
        }
    }
}

/** Edita [ReviewerGuidelines]. */
internal class ReviewerGuidelinesDialog(project: Project) : DialogWrapper(project, true) {

    private val editor = JBTextArea(ReviewerGuidelines.text, 16, 80).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ex.: priorize bugs e segurança; não comente nomes e formatação; peça teste para toda regra de negócio nova"
    }

    val text: String get() = editor.text

    init {
        title = "Minhas Instruções para a IA na Patrulha"
        setOKButtonText("Salvar")
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = editor

    override fun doValidate(): ValidationInfo? =
        if (editor.text.trim().length > ReviewerGuidelines.MAX_LENGTH) {
            ValidationInfo("Até ${ReviewerGuidelines.MAX_LENGTH} caracteres: a IA lê tudo a cada revisão e resposta.", editor)
        } else {
            null
        }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(
            JBLabel(
                "<html>Escreva como você avalia um PR, como explicaria para um colega que vai revisar no seu lugar. " +
                    "Vale para a <b>Revisão com IA</b> e para as <b>respostas automáticas</b> nas conversas.</html>",
            ),
        )
        .addComponent(JBScrollPane(editor).apply { preferredSize = JBUI.size(680, 300) })
        .addComponent(
            NoticeBox(AllIcons.General.Information, Tone.BLUE).apply {
                text = "Ideias: o que bloqueia um PR para você (bug, falta de teste, quebra de contrato), o que é só sugestão, " +
                    "o que ignorar (estilo, nomes), padrões do projeto que você cobra, quando aceitar a explicação do dev e " +
                    "quando insistir, e o tom (direto, didático, curto). Fica nas preferências da IDE e vale para todos os projetos."
            },
        )
        .panel
}
