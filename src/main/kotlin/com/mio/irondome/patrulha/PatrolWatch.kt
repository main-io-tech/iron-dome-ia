package com.mio.irondome.patrulha

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.defesa.DefenseSettings
import com.mio.irondome.defesa.DefenseStatus
import com.mio.irondome.radar.EditorCard
import com.mio.irondome.radar.EditorCardProvider
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Patrulha de plantão: de tempos em tempos consulta os PRs abertos de que você é autor ou revisor pedido, e avisa
 * de comentários novos, CI quebrada e pedidos de revisão. Ao entrar numa branch com PR aberto, traz as conversas dos
 * revisores para o editor. Só usa o token já guardado: nunca abre o login sozinha.
 */
@Service(Service.Level.PROJECT)
class PatrolWatch(private val project: Project) : Disposable {

    private data class Seen(val comments: Int, val ci: CiState?, val requested: Boolean)

    private val seen = ConcurrentHashMap<Int, Seen>()
    private val polling = AtomicBoolean(false)
    private val alerts = ArrayDeque<String>()
    @Volatile
    private var me: String? = null
    @Volatile
    private var openByBranch: Map<String, PullRequest> = emptyMap()
    @Volatile
    private var future: ScheduledFuture<*>? = null
    @Volatile
    private var lastBranch: String? = null

    /** As conversas abertas do PR da branch atual, marcadas no editor; nulo fora de uma branch com PR. */
    @Volatile
    var threads: Pair<Int, List<Discussion>>? = null
        private set
    val expanded: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val hidden: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override fun dispose() {
        future?.cancel(false)
    }

    fun start() {
        schedule()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(DefenseSettings.Listener.TOPIC, DefenseSettings.Listener { schedule() })
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
            ApplicationManager.getApplication().executeOnPooledThread { branchChanged(repository) }
        })
    }

    fun pollNow() {
        ApplicationManager.getApplication().executeOnPooledThread { poll(manual = true) }
    }

    private fun schedule() {
        future?.cancel(false)
        future = null
        if (!DefenseSettings.patrolPolling) return
        val interval = DefenseSettings.patrolIntervalMinutes * 60L
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ poll(manual = false) }, FIRST_POLL_S, interval, TimeUnit.SECONDS)
    }

    private fun poll(manual: Boolean) {
        if (project.isDisposed || !polling.compareAndSet(false, true)) return
        try {
            val patrulha = PatrulhaService.getInstance(project)
            val context = patrulha.context()
            val client = context?.let(patrulha::quietClient)
            if (context == null || client == null) {
                if (manual) notify("Patrulha sem acesso ao GitHub", "Entre na conta do GitHub em Settings > Version Control > GitHub.", NotificationType.WARNING)
                return
            }
            val login = me ?: client.currentUser().also { me = it }
            val open = client.openPullRequests(context.repo)
            openByBranch = open.associateBy { it.headRef }
            val watched = open.filter { pr -> pr.author.equals(login, true) || pr.requestedReviewers.any { it.equals(login, true) } }.take(MAX_WATCHED)
            val firstRound = seen.isEmpty()
            var ciFailing = 0
            var reviewRequests = 0
            watched.forEach { listed ->
                val pr = runCatching { client.pullRequest(context.repo, listed.number) }.getOrDefault(listed)
                val mine = pr.author.equals(login, true)
                val requested = pr.requestedReviewers.any { it.equals(login, true) }
                val ci = if (mine) runCatching { client.ciState(context.repo, pr.headSha) }.getOrNull() else null
                val total = pr.comments + pr.reviewComments
                val before = seen.put(pr.number, Seen(total, ci, requested))
                if (requested) reviewRequests++
                if (ci == CiState.FAILURE) ciFailing++
                if (requested && before?.requested != true) {
                    alert(pr, "Pediram sua revisão no PR #${pr.number}", "${pr.title} · de ${pr.author}", NotificationType.INFORMATION)
                }
                if (before == null) return@forEach
                if (ci == CiState.FAILURE && before.ci != CiState.FAILURE) {
                    alert(pr, "CI quebrou no PR #${pr.number}", "${pr.title}: alguma verificação falhou no commit ${pr.headSha.take(7)}.", NotificationType.WARNING)
                }
                if (ci == CiState.SUCCESS && before.ci == CiState.FAILURE) {
                    alert(pr, "CI voltou a passar no PR #${pr.number}", pr.title, NotificationType.INFORMATION)
                }
                if (total > before.comments) {
                    alert(pr, "${plural(total - before.comments, "comentário novo", "comentários novos")} no PR #${pr.number}", pr.title, NotificationType.INFORMATION)
                }
            }
            seen.keys.retainAll(watched.map { it.number }.toSet())
            DefenseStatus.getInstance(project).patrol(ciFailing, reviewRequests, synchronized(alerts) { alerts.toList() })
            if (firstRound) branchChanged(context.repository)
            if (manual && watched.isEmpty()) notify("Patrulha em dia", "Nenhum PR aberto seu ou com revisão pedida a você.", NotificationType.INFORMATION)
        } catch (e: Exception) {
            LOG.info("A Patrulha de plantão não conseguiu consultar o GitHub", e)
            if (manual) notify("A Patrulha não consultou o GitHub", e.message ?: e.javaClass.simpleName, NotificationType.WARNING)
        } finally {
            polling.set(false)
        }
    }

    /** Entrou numa branch com PR aberto: avisa e, se configurado, marca no editor as conversas abertas dos revisores. */
    private fun branchChanged(repository: GitRepository) {
        val branch = repository.currentBranchName
        if (branch == lastBranch) return
        lastBranch = branch
        clearThreads()
        if (branch == null || project.service<ReviewCommentService>().activePullRequest != 0) return
        val pr = openByBranch[branch] ?: return
        val patrulha = PatrulhaService.getInstance(project)
        val context = patrulha.context() ?: return
        var marked = 0
        if (DefenseSettings.patrolAutoFetch) {
            val client = patrulha.quietClient(context)
            val open = runCatching { client?.discussions(context.repo, pr.number) }.getOrNull().orEmpty()
                .filter { it.kind == DiscussionKind.THREAD && it.path != null && it.line != null && !it.resolved && !it.outdated }
            if (open.isNotEmpty()) {
                threads = pr.number to open
                marked = open.size
                refresh(open.mapNotNull { it.path })
            }
        }
        val content = buildString {
            append("${pr.title} · de ${pr.author}.")
            if (marked > 0) append(" ${plural(marked, "conversa aberta dos revisores está marcada", "conversas abertas dos revisores estão marcadas")} no editor.")
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification("A branch $branch tem o PR #${pr.number} aberto", content, NotificationType.INFORMATION)
            .setIcon(IronDomeIcons.patrulha)
            .addAction(NotificationAction.createSimpleExpiring("Abrir na Patrulha") { ReviewToolWindowFactory.showTab(project, PatrulhaTab.TITLE) })
            .addAction(NotificationAction.createSimpleExpiring("Abrir no GitHub") { BrowserUtil.browse(pr.url) })
            .notify(project)
    }

    fun toggle(id: Long, path: String) {
        if (!expanded.add(id)) expanded.remove(id)
        refresh(listOf(path))
    }

    fun hide(id: Long, path: String) {
        hidden += id
        refresh(listOf(path))
    }

    private fun clearThreads() {
        val previous = threads ?: return
        threads = null
        refresh(previous.second.mapNotNull { it.path })
    }

    private fun refresh(paths: List<String>) {
        ApplicationManager.getApplication().invokeLater({ project.service<ReviewCommentService>().refreshPaths(paths.distinct()) }, project.disposed)
    }

    private fun alert(pr: PullRequest, title: String, content: String, type: NotificationType) {
        synchronized(alerts) {
            alerts.addLast(title)
            while (alerts.size > MAX_ALERTS) alerts.removeFirst()
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, type)
            .setIcon(IronDomeIcons.patrulha)
            .addAction(NotificationAction.createSimpleExpiring("Abrir na Patrulha") { ReviewToolWindowFactory.showTab(project, PatrulhaTab.TITLE) })
            .addAction(NotificationAction.createSimpleExpiring("Abrir no GitHub") { BrowserUtil.browse(pr.url) })
            .notify(project)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, type)
            .setIcon(IronDomeIcons.patrulha)
            .notify(project)
    }

    companion object {
        private const val FIRST_POLL_S = 20L
        private const val MAX_WATCHED = 10
        private const val MAX_ALERTS = 5
        private val LOG = logger<PatrolWatch>()

        fun getInstance(project: Project): PatrolWatch = project.service()
    }
}

class PatrolStartup : ProjectActivity {
    override suspend fun execute(project: Project) = PatrolWatch.getInstance(project).start()
}

class PatrolNowAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { PatrolWatch.getInstance(it).pollNow() }
    }
}

/** As conversas abertas dos revisores do PR da branch atual, como cartões recolhidos nas linhas comentadas. */
class PullRequestThreadCards : EditorCardProvider {

    override fun cards(project: Project, path: String, document: Document): List<EditorCard> {
        val watch = PatrolWatch.getInstance(project)
        val (number, threads) = watch.threads ?: return emptyList()
        return threads.filter { it.path == path && it.first.id !in watch.hidden }.map { discussion ->
            val line = ((discussion.line ?: 1) - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
            val comment = ReviewComment(
                id = "@${discussion.first.author}",
                filePath = path,
                startLine = line,
                endLine = line,
                text = text(discussion),
                pullRequest = number,
            ).apply { expanded = discussion.first.id in watch.expanded }
            EditorCard(
                comment = comment,
                lines = line..line,
                badge = "Revisor",
                tone = Tone.PURPLE,
                subtitle = listOfNotNull(
                    "Conversa aberta no PR #$number",
                    plural(discussion.replies.size, "resposta", "respostas").takeIf { discussion.replies.isNotEmpty() },
                    Discussions.formatDate(discussion.lastActivity),
                ).joinToString(" · "),
                onToggle = { watch.toggle(discussion.first.id, path) },
                onHide = { watch.hide(discussion.first.id, path) },
            )
        }
    }

    private fun text(discussion: Discussion): String = buildString {
        append(discussion.first.body.trim())
        discussion.replies.forEach { reply ->
            append("\n\n> **@${reply.author}:** ")
            append(reply.body.trim().lines().joinToString(" "))
        }
    }
}
