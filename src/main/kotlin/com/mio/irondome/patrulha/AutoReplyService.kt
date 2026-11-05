package com.mio.irondome.patrulha

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.interceptor.AgentResult
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentSettings
import com.mio.irondome.interceptor.AgentTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O modo automático da Patrulha: nos PRs ligados, confere de tempos em tempos as conversas que o revisor abriu e,
 * quando alguém responde, a IA avalia a resposta e responde no GitHub, em nome do revisor, até a conversa chegar a
 * uma conclusão, quando ela é resolvida. A IA pode devolver a conversa ao revisor, e cada conversa tem no máximo
 * [AutoReply.MAX_ROUNDS] rodadas. Uma conversa por vez, porque a IA roda uma tarefa por vez.
 */
@Service(Service.Level.PROJECT)
@State(name = "IronDomeAutoReply", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AutoReplyService(private val project: Project) : PersistentStateComponent<AutoReplyService.AutoReplyState>, Disposable {

    class AutoReplyState {
        /** PRs com o modo automático ligado, pelo número. */
        var pullRequests: MutableSet<String> = mutableSetOf()

        /** Ids dos comentários do dev que a IA já tratou: a próxima rodada só começa com um comentário novo. */
        var handled: MutableSet<Long> = mutableSetOf()

        /** Rodadas da IA por conversa, pela chave de [AutoReply.key]. */
        var rounds: MutableMap<String, Int> = mutableMapOf()

        /** Onde está cada conversa, pela chave de [AutoReply.key], com o nome de [AutoStatus]. */
        var statuses: MutableMap<String, String> = mutableMapOf()
    }

    private var state = AutoReplyState()
    private val lock = Any()
    private val checking = AtomicBoolean(false)
    private var schedule: ScheduledFuture<*>? = null

    override fun getState(): AutoReplyState = synchronized(lock) { state }

    override fun loadState(loaded: AutoReplyState) {
        synchronized(lock) { state = loaded }
    }

    fun isOn(number: Int): Boolean = synchronized(lock) { number.toString() in state.pullRequests }

    fun status(number: Int, discussion: Discussion): AutoStatus? =
        synchronized(lock) { state.statuses[AutoReply.key(number, discussion)] }?.let { name -> AutoStatus.entries.firstOrNull { it.name == name } }

    /** Liga ou desliga o modo automático no PR [number]; ligar já confere as conversas. */
    fun setOn(number: Int, on: Boolean) {
        synchronized(lock) { if (on) state.pullRequests += number.toString() else state.pullRequests -= number.toString() }
        changed()
        if (on) {
            ensureScheduled()
            ApplicationManager.getApplication().executeOnPooledThread(::tick)
        }
    }

    /** Começa a conferir de tempos em tempos, se algum PR está ligado. */
    fun ensureScheduled() {
        synchronized(lock) {
            if (schedule != null || state.pullRequests.isEmpty()) return
            schedule = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(::tick, POLL_MINUTES, POLL_MINUTES, TimeUnit.MINUTES)
        }
    }

    override fun dispose() {
        synchronized(lock) {
            schedule?.cancel(false)
            schedule = null
        }
    }

    /** Uma passada por todos os PRs ligados. Fora da EDT; uma passada por vez. */
    private fun tick() {
        if (project.isDisposed || !checking.compareAndSet(false, true)) return
        try {
            val numbers = synchronized(lock) { state.pullRequests.mapNotNull(String::toIntOrNull) }
            if (numbers.isEmpty() || !AgentSettings.ready) return
            val patrulha = PatrulhaService.getInstance(project)
            val context = patrulha.context()?.takeIf { it.account != null } ?: return
            val client = patrulha.client(context)
            val open = client.openPullRequests(context.repo).associateBy { it.number }
            for (number in numbers) {
                if (project.isDisposed) return
                val pr = open[number]
                if (pr == null) {
                    setOn(number, false)
                    notify("Respostas da IA desligadas no PR #$number", "O PR foi fechado ou saiu da lista de abertos.", NotificationType.INFORMATION)
                    continue
                }
                if (!check(context, client, pr)) return
            }
        } catch (e: Exception) {
            LOG.warn("O modo automático da Patrulha falhou", e)
        } finally {
            checking.set(false)
        }
    }

    /** Responde às conversas do PR que esperam a IA; false quando a IA está ocupada, para tentar na próxima passada. */
    private fun check(context: RepoContext, client: GitHubClient, pr: PullRequest): Boolean {
        val me = context.account?.name ?: return true
        val patrulha = PatrulhaService.getInstance(project)
        val discussions = patrulha.discussions(context, pr.number)
        val pending = synchronized(lock) {
            val stopped = state.statuses.filterValues { it == AutoStatus.CONCLUDED.name || it == AutoStatus.LIMIT.name }.keys
            AutoReply.pending(pr.number, discussions, me, state.handled, stopped)
        }
        for (discussion in pending) {
            if (!isOn(pr.number) || project.isDisposed) return true
            val key = AutoReply.key(pr.number, discussion)
            val round = synchronized(lock) { (state.rounds[key] ?: 0) + 1 }
            if (round > AutoReply.MAX_ROUNDS) {
                settle(discussion, key, AutoStatus.LIMIT)
                notify(
                    "PR #${pr.number}: a conversa passou de ${AutoReply.MAX_ROUNDS} rodadas",
                    "A IA parou de responder a @${discussion.comments.last().author} em ${where(discussion)}. Continue por você.",
                    NotificationType.WARNING,
                    discussion,
                )
                continue
            }
            val result = answer(client, context, pr, discussion, me, round) ?: return false
            if (!result.ok) {
                settle(discussion, key, AutoStatus.NEEDS_YOU)
                if (!result.cancelled) {
                    notify("A IA não respondeu no PR #${pr.number}", "Em ${where(discussion)}: ${result.error}. A conversa ficou para você.", NotificationType.WARNING, discussion)
                }
                continue
            }
            val answer = AutoReply.parse(result.answer)
            if (answer == null) {
                settle(discussion, key, AutoStatus.NEEDS_YOU)
                notify("A IA não decidiu no PR #${pr.number}", "A resposta sobre ${where(discussion)} não veio no formato pedido. A conversa ficou para você.", NotificationType.WARNING, discussion)
                continue
            }
            publish(context, client, pr, discussion, key, round, me, answer)
        }
        if (pending.isNotEmpty()) patrulha.discussions(context, pr.number)
        return true
    }

    /** Roda a IA sobre a conversa e espera o resultado; nulo se ela não pôde começar (ocupada ou sem configuração). */
    private fun answer(client: GitHubClient, context: RepoContext, pr: PullRequest, discussion: Discussion, me: String, round: Int): AgentResult? {
        val patrulha = PatrulhaService.getInstance(project)
        val code = discussion.path?.let { runCatching { client.fileContent(context.repo, it, pr.headSha) }.getOrNull() }
        val request = patrulha.record(pr.number)?.requests?.firstOrNull { it.commentId == discussion.first.id }
        val content = AutoReply.instructions(pr, discussion, me, code, request, round)
        val runner = AgentRunner.getInstance(project)
        val instructions = runner.writeInstructions("pr-${pr.number}-conversa-${discussion.first.id}.md", content) ?: return null
        val done = CountDownLatch(1)
        var result: AgentResult? = null
        var started = false
        ApplicationManager.getApplication().invokeAndWait({
            started = runner.run(
                AgentTask(
                    "Resposta a @${discussion.comments.last().author} no PR #${pr.number}",
                    "Responda à conversa de revisão seguindo as instruções em $instructions e responda só com o JSON pedido.",
                    write = false,
                    origin = PatrulhaTab.TITLE,
                ),
                unattended = true,
            ) {
                result = it
                done.countDown()
            }
        }, ModalityState.defaultModalityState())
        if (!started) return null
        done.await(AgentSettings.timeoutMinutes + 5L, TimeUnit.MINUTES)
        return result
    }

    private fun publish(
        context: RepoContext,
        client: GitHubClient,
        pr: PullRequest,
        discussion: Discussion,
        key: String,
        round: Int,
        me: String,
        answer: AutoAnswer,
    ) {
        val to = discussion.comments.last()
        if (answer.decision == AutoDecision.HUMAN) {
            settle(discussion, key, AutoStatus.NEEDS_YOU)
            notify(
                "PR #${pr.number}: @${to.author} espera você",
                "Em ${where(discussion)}, a IA não respondeu: ${answer.reason.ifBlank { "a conversa pede uma decisão sua." }}",
                NotificationType.WARNING,
                discussion,
            )
            return
        }
        val posted = try {
            client.replyToThread(discussion.nodeId, answer.reply + AutoReply.footer(me), null)
        } catch (e: Exception) {
            LOG.warn("A resposta automática não foi publicada", e)
            settle(discussion, key, AutoStatus.NEEDS_YOU)
            notify("A resposta da IA não foi publicada no PR #${pr.number}", "Em ${where(discussion)}: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR, discussion)
            return
        }
        synchronized(lock) { state.rounds[key] = round }
        val concluded = answer.decision == AutoDecision.CONCLUDE
        val resolveError = if (concluded) runCatching { client.resolveThread(discussion.nodeId) }.exceptionOrNull() else null
        settle(discussion, key, if (concluded) AutoStatus.CONCLUDED else AutoStatus.WAITING)
        val title = if (concluded) "IA concluiu a conversa com @${to.author} no PR #${pr.number}" else "IA respondeu @${to.author} no PR #${pr.number}"
        val content = listOfNotNull(
            "Em ${where(discussion)}, rodada $round de ${AutoReply.MAX_ROUNDS}.",
            answer.reason.takeIf { it.isNotBlank() },
            "A resposta ficou no seu rascunho de revisão: publique o rascunho para o dev ver.".takeIf { posted.pending },
            resolveError?.let { "Não deu para resolver a conversa no GitHub: ${it.message ?: it.javaClass.simpleName}." },
            "O monitor segue esperando o dev.".takeIf { !concluded },
        ).joinToString(" ")
        notify(title, content, NotificationType.INFORMATION, discussion, posted.url)
    }

    /** O comentário do dev foi tratado: a conversa fica em [status] até um comentário novo. */
    private fun settle(discussion: Discussion, key: String, status: AutoStatus) {
        synchronized(lock) {
            state.handled += discussion.comments.last().id
            state.statuses[key] = status.name
        }
        changed()
    }

    /** O painel e os cartões redesenham com o estado novo. */
    private fun changed() {
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(PatrulhaService.TOPIC).recordsChanged()
        }, project.disposed)
    }

    private fun where(discussion: Discussion) =
        "${discussion.path.orEmpty()}, ${discussion.line?.let { "linha $it" } ?: "arquivo inteiro"}"

    private fun notify(title: String, content: String, type: NotificationType, discussion: Discussion? = null, url: String = "") {
        val link = url.ifBlank { discussion?.comments?.last()?.url.orEmpty() }
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"), type)
            .setIcon(IronDomeIcons.patrulha)
            .apply { if (link.isNotBlank()) addAction(NotificationAction.createSimpleExpiring("Abrir no GitHub") { BrowserUtil.browse(link) }) }
            .notify(project)
    }

    companion object {
        /** De quanto em quanto tempo o monitor confere as conversas. */
        const val POLL_MINUTES = 2L
        private val LOG = logger<AutoReplyService>()

        fun getInstance(project: Project): AutoReplyService = project.service()
    }
}

/** Retoma o monitor ao abrir o projeto, nos PRs que ficaram ligados. */
class AutoReplyStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        AutoReplyService.getInstance(project).ensureScheduled()
    }
}
