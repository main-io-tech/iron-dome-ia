package com.mio.irondome.interceptor

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import java.awt.datatransfer.StringSelection
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
        val runner = AgentRunner.getInstance(project)
        val busy = runner.running != null
        val ready = AgentSettings.ready
        val reviewing = runner.reviewingPullRequest()
        e.presentation.isVisible = open > 0 || busy
        e.presentation.isEnabled = open > 0 && !busy && ready && reviewing == 0
        e.presentation.text = if (busy) "IA Trabalhando…" else "Disparar Todos com IA"
        e.presentation.description = when {
            reviewing != 0 -> reviewingHint(reviewing)
            ready -> "Dispara os alvos e pede à IA (${AgentSettings.provider.title}) que execute a ordem de interceptação"
            else -> NOT_READY
        }
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        fire(e.project ?: return)
    }

    companion object {
        /** Dica dos botões de IA desligados. */
        const val NOT_READY = "Configure e teste a IA na aba Interceptador para habilitar"

        /** Dica dos botões de disparar com um PR em revisão. */
        fun reviewingHint(number: Int) =
            "Revisando o PR #$number: o código local é o do PR e a IA não o altera. Saia da revisão na aba Patrulha para disparar"

        fun fire(project: Project) {
            if (AgentRunner.getInstance(project).refusesWrite()) return
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
                    origin = ReviewToolWindowFactory.RADAR,
                ),
                "Abrir ${written.fileName}" to { _ -> runner.open(written) },
            )
        }

        /** Os alvos que podem ser disparados: da revisão aberta do Radar, ainda não interceptados nem cancelados. */
        fun fireable(comments: Collection<ReviewComment>): List<ReviewComment> =
            comments.filter { it.pullRequest == 0 && it.reviewId != 0 && !it.resolved && !it.removing }.distinct()

        /**
         * Dispara só [comments]: grava no `.md` os arquivos deles e pede à IA que execute só esses alvos. Sem a IA
         * configurada, copia o pedido para colar no assistente de IA que o usuário preferir.
         */
        fun fireTargets(project: Project, comments: Collection<ReviewComment>) {
            val targets = fireable(comments).sortedWith(ReviewComment.ORDER)
            if (targets.isEmpty() || AgentRunner.getInstance(project).refusesWrite()) return
            val service = project.service<ReviewCommentService>()
            val written = service.exportPaths(targets.map { it.filePath }) ?: return
            val relative = relativeTo(project.basePath, written)
            val ids = targets.joinToString(", ") { it.id }
            val what = if (targets.size == 1) "o alvo ${targets.single().id}" else "os alvos $ids"
            val prompt = "Execute somente $what da ordem de interceptação em $relative, seguindo as instruções do arquivo: corrija o " +
                "código e marque [x] nos atendidos. Não mexa nos outros alvos. Nos alvos marcados para discutir antes, só apresente a " +
                "análise e a proposta, sem alterar."
            val runner = AgentRunner.getInstance(project)
            if (!runner.available()) {
                CopyPasteManager.getInstance().setContents(StringSelection(prompt))
                NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
                    .createNotification(
                        "${if (targets.size == 1) "Alvo ${targets.single().id} disparado" else "${targets.size} alvos disparados"}: pedido copiado",
                        "Estão em ${written.fileName}. Cole na sua IA: \"$prompt\"",
                        NotificationType.INFORMATION,
                    )
                    .setIcon(IronDomeIcons.radar)
                    .addAction(NotificationAction.createSimple("Abrir ${written.fileName}") { runner.open(written) })
                    .notify(project)
                return
            }
            runner.run(
                AgentTask(
                    if (targets.size == 1) "Disparar ${targets.single().id}" else "Disparar $ids",
                    prompt,
                    write = true,
                    origin = ReviewToolWindowFactory.RADAR,
                ),
                "Abrir ${written.fileName}" to { _ -> runner.open(written) },
            )
        }

        private fun relativeTo(base: String?, file: Path): String =
            base?.let { runCatching { Path.of(it).relativize(file).toString().replace('\\', '/') }.getOrNull() } ?: file.toString()
    }
}
