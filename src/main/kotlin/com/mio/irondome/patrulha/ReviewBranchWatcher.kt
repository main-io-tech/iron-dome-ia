package com.mio.irondome.patrulha

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.plural
import com.mio.irondome.radar.ReviewCommentService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * O modo revisão do PR só vale na branch em que começou. Quando o usuário troca de branch por fora da
 * Patrulha (pelo Git da IDE ou pelo terminal), a revisão é encerrada, para os comentários do editor
 * voltarem ao Radar, e o aviso oferece voltar ao PR ou devolver o stash guardado na entrada.
 */
class ReviewBranchWatcher(private val project: Project) : GitRepositoryChangeListener {

    override fun repositoryChanged(repository: GitRepository) {
        ApplicationManager.getApplication().invokeLater({ check(repository) }, project.disposed)
    }

    private fun check(repository: GitRepository) {
        val radar = project.service<ReviewCommentService>()
        val number = radar.activePullRequest
        if (number == 0) return
        val patrulha = PatrulhaService.getInstance(project)
        val context = patrulha.context()?.takeIf { it.repository == repository } ?: return
        val expected = patrulha.reviewRef(number)
        val current = PatrulhaService.refOf(repository) ?: return
        if (current == expected) return

        radar.stopPullRequestReview(stillOnPullRequest = false)
        patrulha.setShownInEditor(number, false)
        IdeEditorReview.restore(project)
        val point = patrulha.returnPoint()?.takeIf { it.pullRequest == number }
        patrulha.forgetReturnPoint()

        val drafts = radar.pullRequestComments(number).size
        val currentName = if (current.startsWith("@")) "o commit ${current.drop(1).take(7)}" else "a branch $current"
        // De volta onde estava antes do PR: dá para devolver o stash ali mesmo.
        val stashBack = point != null && point.stash.isNotEmpty() && current == (if (point.branch) point.ref else "@${point.ref}")
        val content = listOfNotNull(
            "Você trocou para $currentName: o modo revisão do PR #$number foi encerrado e os comentários feitos no editor voltam para o Radar.",
            plural(drafts, "comentário não enviado continua", "comentários não enviados continuam").takeIf { drafts > 0 }?.let { "$it na Patrulha." },
            point?.takeIf { it.stash.isNotEmpty() && !stashBack }?.let { "Suas alterações continuam no stash \"${it.stashMessage}\"." },
            point?.takeIf { stashBack }?.let { "Suas alterações de ${it.ref} estão no stash \"${it.stashMessage}\"." },
        ).joinToString(" ")
        val actions = buildList {
            point?.takeIf { stashBack }?.let { back ->
                add(NotificationAction.createSimpleExpiring("Devolver as Alterações do Stash") { restoreStash(context, back) })
            }
            add(NotificationAction.createSimpleExpiring("Voltar ao PR #$number") { resume(context, number, expected) })
        }
        notify("Revisão do PR #$number encerrada", content, NotificationType.INFORMATION, actions)
    }

    private fun resume(context: RepoContext, number: Int, ref: String) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val patrulha = PatrulhaService.getInstance(project)
        object : Task.Backgroundable(project, "Voltando ao PR #$number", true) {
            override fun run(indicator: ProgressIndicator) = patrulha.switchTo(context, ref)

            override fun onSuccess() {
                patrulha.setReviewRef(ref)
                project.service<ReviewCommentService>().startPullRequestReview(number)
                patrulha.setShownInEditor(number, true)
                IdeEditorReview.suspend(project)
            }

            override fun onThrowable(error: Throwable) {
                notify("Não deu para voltar ao PR #$number", error.message ?: error.javaClass.simpleName, NotificationType.ERROR)
            }
        }.queue()
    }

    private fun restoreStash(context: RepoContext, point: ReturnPoint) {
        val patrulha = PatrulhaService.getInstance(project)
        object : Task.Backgroundable(project, "Devolvendo as alterações do stash", true) {
            private var warning: String? = null

            override fun run(indicator: ProgressIndicator) {
                warning = patrulha.restoreStash(context, point)
            }

            override fun onSuccess() {
                notify(
                    if (warning == null) "As alterações voltaram" else "O stash não voltou",
                    warning ?: "As alterações guardadas antes do PR #${point.pullRequest} voltaram para ${point.ref}.",
                    if (warning == null) NotificationType.INFORMATION else NotificationType.WARNING,
                )
            }
        }.queue()
    }

    private fun notify(title: String, content: String, type: NotificationType, actions: List<NotificationAction> = emptyList()) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, type)
            .setIcon(IronDomeIcons.patrulha)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }
}
