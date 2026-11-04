package com.mio.irondome.interceptor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.mio.irondome.radar.ReviewCommentService
import java.nio.file.Path

/**
 * Dispara todos os alvos, como o Disparar Todos, e entrega a ordem de interceptação para a IA executar:
 * ela corrige o código de cada alvo e marca `[x]` no `.md`, o que o Radar mostra como interceptado.
 */
class FireWithAiAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val open = project.service<ReviewCommentService>().activeComments().count { !it.resolved && !it.removing }
        val busy = AgentRunner.getInstance(project).running != null
        val ready = AgentSettings.ready
        e.presentation.isVisible = open > 0 || busy
        e.presentation.isEnabled = open > 0 && !busy && ready
        e.presentation.text = if (busy) "IA Trabalhando…" else "Disparar com IA"
        e.presentation.description = if (ready) {
            "Dispara os alvos e pede à IA (${AgentSettings.provider.title}) que execute a ordem de interceptação"
        } else {
            NOT_READY
        }
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        fire(e.project ?: return)
    }

    companion object {
        /** Dica dos botões de IA desligados. */
        const val NOT_READY = "Configure e teste a IA na aba Interceptador para habilitar"

        fun fire(project: Project) {
            val service = project.service<ReviewCommentService>()
            val written = service.exportAll().firstOrNull() ?: return
            val relative = relativeTo(project.basePath, written)
            val runner = AgentRunner.getInstance(project)
            runner.run(
                AgentTask(
                    "Disparar ${written.fileName}",
                    "Execute a ordem de interceptação em $relative: siga as instruções do arquivo, corrija o código de cada alvo " +
                        "e marque [x] os que forem atendidos. Nos alvos marcados para discutir antes, só apresente a análise e a proposta, sem alterar.",
                    write = true,
                ),
                "Abrir ${written.fileName}" to { _ -> runner.open(written) },
            )
        }

        private fun relativeTo(base: String?, file: Path): String =
            base?.let { runCatching { Path.of(it).relativize(file).toString().replace('\\', '/') }.getOrNull() } ?: file.toString()
    }
}
