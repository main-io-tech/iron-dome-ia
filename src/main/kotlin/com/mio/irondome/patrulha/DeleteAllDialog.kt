package com.mio.irondome.patrulha

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.plural
import javax.swing.JComponent

/**
 * Confirma o Excluir Todos de um PR: os não enviados e os rascunhos do GitHub saem sempre; os publicados saem
 * do `.md` e, só se marcado, também do GitHub, o que apaga as conversas deles.
 */
internal class DeleteAllDialog(
    project: Project,
    number: Int,
    private val unsent: Int,
    private val drafts: Int,
    private val published: Int,
    /** Apagados no GitHub, que só estão no `.md` como registro. */
    private val others: Int,
) : DialogWrapper(project, true) {

    private val onGitHub = JBCheckBox("Apagar também no GitHub os ${plural(published, "comentário publicado", "comentários publicados")} (some com as conversas)", false)

    val publishedOnGitHub: Boolean get() = published > 0 && onGitHub.isSelected

    init {
        title = "Excluir Todos os Comentários do PR #$number"
        setOKButtonText("Excluir Todos")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
        listOfNotNull(
            "${plural(unsent, "comentário ainda não enviado", "comentários ainda não enviados")}, só no IntelliJ".takeIf { unsent > 0 },
            "${plural(drafts, "rascunho", "rascunhos")} no GitHub: o rascunho da revisão é descartado lá".takeIf { drafts > 0 },
            "${plural(published, "pedido publicado", "pedidos publicados")}: sai do .md".takeIf { published > 0 },
            "${plural(others, "pedido apagado", "pedidos apagados")} no GitHub, que só estava no .md".takeIf { others > 0 },
        ).ifEmpty { listOf("Nada a excluir neste PR.") }
            .forEach { builder.addComponent(JBLabel("•  $it")) }
        if (published > 0) {
            builder.addVerticalGap(8).addComponent(onGitHub)
            builder.addComponent(JBLabel("Sem marcar, os publicados continuam no PR e só deixam de ser acompanhados aqui.").apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        return builder.panel
    }
}
