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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O modo automático da Patrulha: nos PRs ligados, confere de tempos em tempos as conversas que o revisor abriu e,
 * quando alguém responde, a IA avalia a resposta e responde no GitHub, em nome do revisor, até a conversa chegar a
 * uma conclusão, quando ela é resolvida. A IA pode devolver a conversa ao revisor, e cada conversa tem no máximo
 * [AutoReply.MAX_ROUNDS] rodadas. Uma conversa por vez, porque a IA roda uma tarefa por vez.
 *
 * O que ele faz fica em [activity], para a aba mostrar: o passo de agora, as contas das conversas, por que parou e o
 * histórico das conferências.
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
    /** O que a aba mostra de cada PR; só na memória, recomeça ao abrir o projeto. */
    private val activities = mutableMapOf<Int, AutoActivity>()

    override fun getState(): AutoReplyState = synchronized(lock) { state }

    override fun loadState(loaded: AutoReplyState) {
        synchronized(lock) { state = loaded }
    }

    fun isOn(number: Int): Boolean = synchronized(lock) { number.toString() in state.pullRequests }

    fun status(number: Int, discussion: Discussion): AutoStatus? =
        synchronized(lock) { state.statuses[AutoReply.key(number, discussion)] }?.let { name -> AutoStatus.entries.firstOrNull { it.name == name } }

    fun activity(number: Int): AutoActivity = synchronized(lock) { activities[number] ?: AutoActivity() }

    /** Uma conferência está em andamento. */
    val isChecking: Boolean get() = checking.get()

    /** Liga ou desliga o modo automático no PR [number]; ligar já confere as conversas. */
    fun setOn(number: Int, on: Boolean) {
        synchronized(lock) { if (on) state.pullRequests += number.toString() else state.pullRequests -= number.toString() }
        if (on) {
            update(number) { it.copy(step = "Começando a conferir…", problem = null) }
            ensureScheduled()
            if (!checkNow()) update(number) { it.copy(step = null, nextAt = System.currentTimeMillis() + POLL_MINUTES * 60_000) }
        } else {
            update(number) { it.copy(step = null, nextAt = null) }
        }
    }

    /** Confere os PRs ligados agora, sem esperar a próxima conferência; false se uma já está em andamento. */
    fun checkNow(): Boolean {
        if (checking.get()) return false
        ApplicationManager.getApplication().executeOnPooledThread(::tick)
        return true
    }

    /** Começa a conferir de tempos em tempos, se algum PR está ligado. */
    fun ensureScheduled() {
        val numbers = synchronized(lock) {
            if (schedule != null || state.pullRequests.isEmpty()) return
            schedule = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(::tick, FIRST_DELAY_SECONDS, POLL_MINUTES * 60, TimeUnit.SECONDS)
            state.pullRequests.mapNotNull(String::toIntOrNull)
        }
        val next = System.currentTimeMillis() + FIRST_DELAY_SECONDS * 1000
        numbers.forEach { number -> update(number) { if (it.nextAt == null) it.copy(nextAt = next) else it } }
    }

    override fun dispose() {
        synchronized(lock) {
            schedule?.cancel(false)
            schedule = null
        }
    }

    /** Uma conferência de todos os PRs ligados. Fora da EDT; uma por vez. */
    private fun tick() {
        if (project.isDisposed || !checking.compareAndSet(false, true)) return
        val numbers = synchronized(lock) { state.pullRequests.mapNotNull(String::toIntOrNull) }
        try {
            if (numbers.isEmpty()) return
            if (!AgentSettings.ready) {
                numbers.forEach { blocked(it, "A IA não está configurada ou testada no Arsenal: configure para as respostas voltarem.") }
                return
            }
            numbers.forEach { number -> update(number) { it.copy(step = "Lendo os PRs abertos no GitHub…") } }
            val patrulha = PatrulhaService.getInstance(project)
            val context = patrulha.context()?.takeIf { it.account != null }
            if (context == null) {
                numbers.forEach { blocked(it, "Sem conta do GitHub para este repositório: entre em Settings > Version Control > GitHub.") }
                return
            }
            val client = patrulha.client(context)
            val open = client.openPullRequests(context.repo).associateBy { it.number }
            for (number in numbers) {
                if (project.isDisposed || !isOn(number)) continue
                val pr = open[number]
                if (pr == null) {
                    event(number, AutoEventKind.BLOCKED, "O PR foi fechado ou saiu da lista de abertos: respostas da IA desligadas.")
                    setOn(number, false)
                    notify("Respostas da IA desligadas no PR #$number", "O PR foi fechado ou saiu da lista de abertos.", NotificationType.INFORMATION)
                    continue
                }
                try {
                    if (!check(context, client, pr)) {
                        blocked(number, "A IA está ocupada com outra tarefa: tento de novo na próxima conferência.")
                        break
                    }
                } catch (e: Exception) {
                    LOG.warn("A conferência do PR #$number falhou", e)
                    failed(number, "Não deu para conferir: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            LOG.warn("O modo automático da Patrulha falhou", e)
            numbers.forEach { failed(it, "Não deu para ler o GitHub: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            val next = System.currentTimeMillis() + POLL_MINUTES * 60_000
            numbers.filter(::isOn).forEach { number -> update(number) { it.copy(step = null, nextAt = next) } }
            checking.set(false)
            changed()
        }
    }

    /** Responde às conversas do PR que esperam a IA; false quando a IA está ocupada, para tentar na próxima conferência. */
    private fun check(context: RepoContext, client: GitHubClient, pr: PullRequest): Boolean {
        val me = context.account?.name ?: return true
        val number = pr.number
        update(number) { it.copy(step = "Lendo as suas conversas no GitHub…") }
        val patrulha = PatrulhaService.getInstance(project)
        val discussions = patrulha.discussions(context, number)
        val pending = synchronized(lock) {
            val stopped = state.statuses.filterValues { it == AutoStatus.CONCLUDED.name || it == AutoStatus.LIMIT.name }.keys
            AutoReply.pending(number, discussions, me, state.handled, stopped)
        }
        val (mine, waiting) = AutoReply.tracked(discussions, me)
        update(number) { it.copy(checkedAt = System.currentTimeMillis(), mine = mine, waiting = waiting, forAi = pending.size, problem = null) }
        if (pending.isEmpty()) {
            event(
                number,
                AutoEventKind.CHECKED,
                when {
                    mine == 0 -> "Nada novo: você não tem conversas abertas neste PR."
                    else -> "Nada novo: ${countText(mine, "conversa sua aberta", "conversas suas abertas")}, " +
                        "${countText(waiting, "aguardando", "aguardando")} o dev."
                },
            )
            return true
        }
        for ((index, discussion) in pending.withIndex()) {
            if (!isOn(number) || project.isDisposed) return true
            val key = AutoReply.key(number, discussion)
            val to = discussion.comments.last()
            val round = synchronized(lock) { (state.rounds[key] ?: 0) + 1 }
            if (round > AutoReply.MAX_ROUNDS) {
                settle(discussion, key, AutoStatus.LIMIT)
                val text = "Parou em ${where(discussion)}: a conversa com @${to.author} passou de ${AutoReply.MAX_ROUNDS} rodadas. Continue por você."
                event(number, AutoEventKind.LIMIT, text, to.url)
                notify("PR #$number: a conversa passou de ${AutoReply.MAX_ROUNDS} rodadas", text, NotificationType.WARNING, to.url)
                continue
            }
            update(number) {
                it.copy(step = "IA avaliando a resposta de @${to.author} em ${where(discussion)} (rodada $round de ${AutoReply.MAX_ROUNDS})…")
            }
            val result = answer(client, context, pr, discussion, me, round) ?: return false
            when {
                result.cancelled -> {
                    settle(discussion, key, AutoStatus.NEEDS_YOU)
                    event(number, AutoEventKind.NEEDS_YOU, "Você cancelou a IA em ${where(discussion)}: a resposta a @${to.author} ficou com você.", to.url)
                }
                !result.ok -> {
                    settle(discussion, key, AutoStatus.NEEDS_YOU)
                    val text = "A IA falhou em ${where(discussion)}: ${result.error}. A resposta a @${to.author} ficou com você."
                    event(number, AutoEventKind.ERROR, text, to.url)
                    notify("A IA não respondeu no PR #$number", text, NotificationType.WARNING, to.url)
                }
                else -> {
                    val answer = AutoReply.parse(result.answer)
                    if (answer == null) {
                        settle(discussion, key, AutoStatus.NEEDS_YOU)
                        val text = "A IA não decidiu no formato pedido em ${where(discussion)}: a resposta a @${to.author} ficou com você."
                        event(number, AutoEventKind.ERROR, text, to.url)
                        notify("A IA não decidiu no PR #$number", text, NotificationType.WARNING, to.url)
                    } else {
                        publish(client, pr, discussion, key, round, me, answer)
                    }
                }
            }
            update(number) { it.copy(forAi = pending.size - index - 1) }
        }
        update(number) { it.copy(step = "Relendo as conversas…") }
        val after = patrulha.discussions(context, number)
        val (mineAfter, waitingAfter) = AutoReply.tracked(after, me)
        update(number) { it.copy(mine = mineAfter, waiting = waitingAfter) }
        return true
    }

    /** Roda a IA sobre a conversa e espera o resultado; nulo se ela não pôde começar (ocupada ou sem configuração). */
    private fun answer(client: GitHubClient, context: RepoContext, pr: PullRequest, discussion: Discussion, me: String, round: Int): AgentResult? {
        val patrulha = PatrulhaService.getInstance(project)
        val code = discussion.path?.let { runCatching { client.fileContent(context.repo, it, pr.headSha) }.getOrNull() }
        val request = patrulha.record(pr.number)?.requests?.firstOrNull { it.commentId == discussion.first.id }
        val content = AutoReply.instructions(pr, discussion, me, code, request, round, ReviewerGuidelines.text, ReviewRules.getInstance(project).read())
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

    private fun publish(client: GitHubClient, pr: PullRequest, discussion: Discussion, key: String, round: Int, me: String, answer: AutoAnswer) {
        val number = pr.number
        val to = discussion.comments.last()
        if (answer.decision == AutoDecision.HUMAN) {
            settle(discussion, key, AutoStatus.NEEDS_YOU)
            val text = "Em ${where(discussion)}, @${to.author} espera você: ${answer.reason.ifBlank { "a conversa pede uma decisão sua." }}"
            event(number, AutoEventKind.NEEDS_YOU, text, to.url)
            notify("PR #$number: @${to.author} espera você", text, NotificationType.WARNING, to.url)
            return
        }
        update(number) { it.copy(step = "Publicando a resposta a @${to.author} em ${where(discussion)}…") }
        val posted = try {
            client.replyToThread(discussion.nodeId, answer.reply + AutoReply.footer(me), null)
        } catch (e: Exception) {
            LOG.warn("A resposta automática não foi publicada", e)
            settle(discussion, key, AutoStatus.NEEDS_YOU)
            val text = "A resposta a @${to.author} em ${where(discussion)} não foi publicada: ${e.message ?: e.javaClass.simpleName}"
            event(number, AutoEventKind.ERROR, text, to.url)
            notify("A resposta da IA não foi publicada no PR #$number", text, NotificationType.ERROR, to.url)
            return
        }
        synchronized(lock) { state.rounds[key] = round }
        val concluded = answer.decision == AutoDecision.CONCLUDE
        val resolveError = if (concluded) {
            update(number) { it.copy(step = "Resolvendo a conversa em ${where(discussion)}…") }
            runCatching { client.resolveThread(discussion.nodeId) }.exceptionOrNull()
        } else {
            null
        }
        settle(discussion, key, if (concluded) AutoStatus.CONCLUDED else AutoStatus.WAITING)
        val text = listOfNotNull(
            if (concluded) "Concluiu a conversa com @${to.author} em ${where(discussion)}" + (if (resolveError == null) " e a resolveu." else ".")
            else "Respondeu @${to.author} em ${where(discussion)} (rodada $round de ${AutoReply.MAX_ROUNDS}).",
            answer.reason.takeIf { it.isNotBlank() },
            "A resposta ficou no seu rascunho de revisão: publique o rascunho para o dev ver.".takeIf { posted.pending },
            resolveError?.let { "Não deu para resolver a conversa no GitHub: ${it.message ?: it.javaClass.simpleName}." },
        ).joinToString(" ")
        event(number, if (concluded) AutoEventKind.CONCLUDED else AutoEventKind.REPLIED, text, posted.url.ifBlank { to.url })
        notify(
            if (concluded) "IA concluiu a conversa com @${to.author} no PR #$number" else "IA respondeu @${to.author} no PR #$number",
            text,
            NotificationType.INFORMATION,
            posted.url.ifBlank { to.url },
        )
    }

    /** O comentário do dev foi tratado: a conversa fica em [status] até um comentário novo. */
    private fun settle(discussion: Discussion, key: String, status: AutoStatus) {
        synchronized(lock) {
            state.handled += discussion.comments.last().id
            state.statuses[key] = status.name
        }
        changed()
    }

    /** A conferência do PR não seguiu por [reason], que fica no status e no histórico. */
    private fun blocked(number: Int, reason: String) {
        update(number) { it.copy(problem = reason) }
        event(number, AutoEventKind.BLOCKED, reason)
    }

    private fun failed(number: Int, reason: String) {
        update(number) { it.copy(problem = reason) }
        event(number, AutoEventKind.ERROR, reason)
    }

    private fun event(number: Int, kind: AutoEventKind, text: String, url: String = "") =
        update(number) { it.withEvent(AutoEvent(System.currentTimeMillis(), kind, text, url)) }

    private fun update(number: Int, change: (AutoActivity) -> AutoActivity) {
        synchronized(lock) { activities[number] = change(activities[number] ?: AutoActivity()) }
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

    private fun countText(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

    private fun notify(title: String, content: String, type: NotificationType, url: String = "") {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"), type)
            .setIcon(IronDomeIcons.patrulha)
            .apply { if (url.isNotBlank()) addAction(NotificationAction.createSimpleExpiring("Abrir no GitHub") { BrowserUtil.browse(url) }) }
            .notify(project)
    }

    companion object {
        /** De quanto em quanto tempo o monitor confere as conversas. */
        const val POLL_MINUTES = 2L
        /** A primeira conferência depois de abrir o projeto. */
        private const val FIRST_DELAY_SECONDS = 20L
        private val LOG = logger<AutoReplyService>()
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun getInstance(project: Project): AutoReplyService = project.service()

        /** `21:10`, no fuso da máquina. */
        fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

/** Retoma o monitor ao abrir o projeto, nos PRs que ficaram ligados. */
class AutoReplyStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        AutoReplyService.getInstance(project).ensureScheduled()
        // Uma revisão que continuou aberta de uma sessão anterior também deixa o modo revisão do GitHub desligado.
        if (PatrulhaService.getInstance(project).reviewing() != 0) IdeEditorReview.suspend(project)
    }
}
