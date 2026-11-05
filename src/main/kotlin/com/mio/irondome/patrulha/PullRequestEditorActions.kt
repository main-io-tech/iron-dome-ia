package com.mio.irondome.patrulha

import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAwareAction

/**
 * As ações do editor sobre os comentários do PR em revisão: os não enviados, os pedidos enviados e as conversas.
 * Aparecem na barra do canto do editor e no menu do botão direito, só durante a revisão de um PR.
 */
abstract class PullRequestEditorAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    final override fun update(e: AnActionEvent) {
        val patrulha = e.project?.let(PatrulhaService::getInstance)
        val number = patrulha?.reviewing() ?: 0
        e.presentation.isEnabledAndVisible = number != 0
        if (number != 0) update(e, patrulha!!, number)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val patrulha = e.project?.let(PatrulhaService::getInstance) ?: return
        val number = patrulha.reviewing().takeIf { it != 0 } ?: return
        perform(patrulha, number)
    }

    protected abstract fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int)

    protected abstract fun perform(patrulha: PatrulhaService, number: Int)
}

/** Minimiza todos os cartões do PR numa barra de uma linha, ou expande todos quando já estão minimizados. */
class MinimizePullRequestCommentsAction : PullRequestEditorAction() {

    override fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int) {
        val minimize = patrulha.hasExpandedInEditor(number)
        e.presentation.text = if (minimize) "Minimizar Comentários do PR" else "Expandir Comentários do PR"
        e.presentation.description = if (minimize) {
            "Recolhe cada comentário do PR #$number no editor numa barra de uma linha; clique no cabeçalho de um para abrir só ele"
        } else {
            "Abre todos os comentários do PR #$number no editor"
        }
        e.presentation.icon = if (minimize) AllIcons.Actions.Collapseall else AllIcons.Actions.Expandall
    }

    override fun perform(patrulha: PatrulhaService, number: Int) =
        patrulha.setAllExpandedInEditor(number, !patrulha.hasExpandedInEditor(number))
}

/** Tira do editor todos os comentários do PR, ou traz de volta; pressionado enquanto eles aparecem. */
class TogglePullRequestCommentsAction : PullRequestEditorAction(), Toggleable {

    override fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int) {
        val shown = patrulha.isAnyShownInEditor(number)
        Toggleable.setSelected(e.presentation, shown)
        e.presentation.text = if (shown) "Ocultar Comentários do PR" else "Mostrar Comentários do PR"
        e.presentation.description = if (shown) {
            "Tira do editor os comentários do PR #$number (os não enviados, os pedidos enviados e as conversas), sem apagar nenhum"
        } else {
            "Volta a mostrar no editor os comentários do PR #$number"
        }
        e.presentation.icon = if (shown) ExecutionUtil.getLiveIndicator(AllIcons.Actions.Show) else AllIcons.Actions.Show
    }

    override fun perform(patrulha: PatrulhaService, number: Int) =
        patrulha.setAllShownInEditor(number, !patrulha.isAnyShownInEditor(number))
}

/** Traz de volta ao editor os pedidos e as conversas do PR ocultados um a um pelo Ocultar do cartão. */
class ShowHiddenPullRequestCommentsAction : PullRequestEditorAction() {

    override fun update(e: AnActionEvent, patrulha: PatrulhaService, number: Int) {
        val hidden = patrulha.hiddenInEditor(number)
        e.presentation.isEnabledAndVisible = hidden > 0
        e.presentation.text = "Mostrar Comentários Ocultados do PR ($hidden)"
        e.presentation.description = "Volta a mostrar no editor os comentários do PR #$number que você ocultou um a um pelo Ocultar do cartão"
        e.presentation.icon = AllIcons.Actions.ToggleVisibility
    }

    override fun perform(patrulha: PatrulhaService, number: Int) = patrulha.showHiddenInEditor(number)
}
