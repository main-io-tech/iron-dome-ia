package com.mio.irondome.defesa

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.mio.irondome.base.plural
import com.mio.irondome.base.rootPath
import com.mio.irondome.impacto.ChangeRisk
import com.mio.irondome.impacto.CommentSource
import com.mio.irondome.impacto.ImpactComment
import com.mio.irondome.impacto.ImpactComments
import com.mio.irondome.impacto.TestCoverage
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService
import java.nio.charset.StandardCharsets

/** Um motivo para pensar antes de commitar, já com a explicação. */
data class CommitAlert(val text: String, val level: DefenseLevel)

/**
 * As regras da defesa do commit, sem IDE no meio: alvos pendentes nos arquivos commitados, classes de risco sem
 * teste alterado no mesmo commit e arquivos que costumam mudar juntos mas ficaram de fora.
 */
object CommitRules {

    private const val MAX_PARTNERS = 3

    fun isTest(path: String): Boolean {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        return "/test/" in "/$path" || "/tests/" in "/$path" || TEST_NAME.containsMatchIn(name)
    }

    fun alerts(
        paths: List<String>,
        comments: List<ImpactComment>,
        snapshots: Map<String, ClassSnapshot>,
        history: ChangeHistory,
    ): List<CommitAlert> = buildList {
        comments.filter { comment -> paths.any(comment::covers) }
            .groupBy { it.path }
            .forEach { (path, pending) ->
                val ids = pending.joinToString(", ") { it.id }
                val what = if (pending.all { it.source == CommentSource.PATRULHA }) "pedido(s) do PR" else plural(pending.size, "alvo não resolvido", "alvos não resolvidos")
                add(CommitAlert("$what em ${path.substringAfterLast('/')} ($ids)", DefenseLevel.YELLOW))
            }
        val testTouched = paths.any(::isTest)
        if (!testTouched) {
            paths.mapNotNull { snapshots[it] }
                .filter { it.risk.level == ChangeRisk.HIGH || (it.risk.level == ChangeRisk.MEDIUM && it.coverage == TestCoverage.NONE) }
                .forEach { snapshot ->
                    add(
                        CommitAlert(
                            "${snapshot.name} tem risco ${snapshot.risk.explain().replaceFirstChar(Char::lowercase)}, e nenhum teste foi alterado neste commit",
                            if (snapshot.risk.level == ChangeRisk.HIGH) DefenseLevel.RED else DefenseLevel.YELLOW,
                        ),
                    )
                }
        }
        history.missingPartners(paths).distinctBy { it.second.path }.take(MAX_PARTNERS).forEach { (path, partner) ->
            add(
                CommitAlert(
                    "${path.substringAfterLast('/')} muda junto com ${partner.path.substringAfterLast('/')} em ${partner.percent}% dos commits " +
                        "(${partner.together} de ${partner.total}), mas ele ficou de fora",
                    DefenseLevel.YELLOW,
                ),
            )
        }
    }

    private val TEST_NAME = Regex("""(Test|Tests|IT|Spec)$|^Test[A-Z].*""")
}

class CommitGuardFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = CommitGuard(panel)
}

/** A defesa mais Iron Dome: intercepta o commit quando o plugin sabe de um risco, e explica o porquê. */
class CommitGuard(private val panel: CheckinProjectPanel) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        if (!DefenseSettings.commitGuard) return ReturnResult.COMMIT
        val project = panel.project
        val radar = project.service<ReviewCommentService>()
        val paths = panel.virtualFiles.mapNotNull(radar::relativePath).distinct()
        if (paths.isEmpty()) return ReturnResult.COMMIT
        val status = DefenseStatus.getInstance(project)
        val snapshots = paths.mapNotNull { path -> status.snapshotOf(path)?.let { path to it } }.toMap()
        val alerts = CommitRules.alerts(paths, pendingComments(project), snapshots, ChangeHistoryService.getInstance(project).current)
        if (alerts.isEmpty()) return ReturnResult.COMMIT
        val message = buildString {
            appendLine("O Iron Dome interceptou ${plural(alerts.size, "risco", "riscos")} neste commit:")
            appendLine()
            alerts.forEach { appendLine("${if (it.level == DefenseLevel.RED) "■" else "▲"} ${it.text}.") }
            appendLine()
            append("Revisar com a IA cancela o commit e pede a ela, só para ler, uma revisão das alterações.")
        }
        val choice = Messages.showDialog(
            project,
            message,
            "Iron Dome IA: Defesa do Commit",
            arrayOf("Commitar Mesmo Assim", "Revisar com a IA", "Cancelar"),
            0,
            if (alerts.any { it.level == DefenseLevel.RED }) Messages.getErrorIcon() else Messages.getWarningIcon(),
        )
        return when (choice) {
            0 -> ReturnResult.COMMIT
            1 -> {
                reviewWithAi(project, paths)
                ReturnResult.CANCEL
            }
            else -> ReturnResult.CANCEL
        }
    }

    override fun checkinSuccessful() {
        ChangeHistoryService.getInstance(panel.project).refresh()
    }

    private fun pendingComments(project: Project): List<ImpactComment> = try {
        ImpactComments.collect(project)
    } catch (_: LinkageError) {
        project.service<ReviewCommentService>().activeComments().filter { !it.resolved && !it.removing }.map {
            ImpactComment(
                it.id, CommentSource.RADAR, it.filePath, it.target,
                if (it.target == CommentTarget.LINES) it.startLine + 1 else 0,
                if (it.target == CommentTarget.LINES) it.endLine + 1 else 0,
                it.text,
            )
        }
    }

    private fun reviewWithAi(project: Project, paths: List<String>) {
        val runner = AgentRunner.getInstance(project)
        if (!runner.available()) {
            Messages.showWarningDialog(project, "Configure e teste a IA no Interceptador para revisar o commit com ela.", "IA Indisponível")
            return
        }
        val root = project.rootPath() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val diff = runCatching {
                val line = GeneralCommandLine(listOf("git", "diff", "HEAD", "--no-color", "--") + paths).withWorkDirectory(root).withCharset(StandardCharsets.UTF_8)
                ExecUtil.execAndGetOutput(line, GIT_TIMEOUT_MS).takeIf { it.exitCode == 0 }?.stdout.orEmpty()
            }.getOrDefault("")
            val content = CommitReview.instructions(paths, diff, ReviewFeedback.getInstance(project).promptHints(), ClassDossier.collect(project, paths))
            val instructions = runner.writeInstructions("revisao-commit.md", content) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                runner.run(
                    AgentTask(
                        "Revisão antes do commit",
                        "Revise as alterações descritas em $instructions, seguindo as instruções do arquivo, sem alterar nenhum arquivo.",
                        write = false,
                    ),
                )
            }, project.disposed)
        }
    }

    private companion object {
        const val GIT_TIMEOUT_MS = 15_000
    }
}

/** O pedido de revisão do diff antes do commit, no mesmo tom didático da revisão de PR. */
object CommitReview {

    private const val DIFF_MAX_LINES = 800

    fun instructions(paths: List<String>, diff: String, hints: List<String>, dossier: String): String = buildString {
        appendLine("# Revisão antes do commit")
        appendLine()
        appendLine("Você é um revisor de código experiente. O desenvolvedor vai commitar os arquivos abaixo e pediu uma última")
        appendLine("conferida. Leia o diff e, no projeto, o contexto que precisar. **Não altere nenhum arquivo.**")
        appendLine()
        appendLine("Responda em português do Brasil, em Markdown, com no máximo 8 itens, dos mais importantes para os menos.")
        appendLine("Cada item começa com a gravidade em negrito (**Bug:**, **Atenção:** ou **Sugestão:**), cita `arquivo:linha`,")
        appendLine("diz o que acontece, por que importa (um cenário concreto) e como corrigir. Sem nada a apontar, diga só")
        appendLine("\"Nada a interceptar\" e o porquê em uma frase.")
        if (hints.isNotEmpty()) {
            appendLine()
            appendLine("## Preferências do time")
            appendLine()
            hints.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## Arquivos (${paths.size})")
        appendLine()
        paths.forEach { appendLine("- `$it`") }
        if (diff.isNotBlank()) {
            val lines = diff.lines()
            appendLine()
            appendLine("```diff")
            lines.take(DIFF_MAX_LINES).forEach(::appendLine)
            if (lines.size > DIFF_MAX_LINES) appendLine("… (${lines.size - DIFF_MAX_LINES} linhas omitidas: leia os arquivos)")
            appendLine("```")
        }
        if (dossier.isNotBlank()) {
            appendLine()
            appendLine(dossier.trimEnd())
        }
    }
}
