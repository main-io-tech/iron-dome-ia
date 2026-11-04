package com.mio.irondome.patrulha

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.messages.Topic
import com.mio.irondome.base.rootPath
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHCompatibilityUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** Repositório do projeto no GitHub, com o remote de onde veio e a conta do IntelliJ que o acessa. */
class RepoContext(val repository: GitRepository, val remote: String, val repo: GitHubRepo, val account: GithubAccount?)

class PatrulhaException(message: String) : Exception(message)

/** Alterações não commitadas fora de `iron-dome/` e `.idea/`, como no `git status --porcelain`. */
class LocalChanges(val branch: String?, val files: List<String>)

/** O que fazer com as alterações não commitadas antes do checkout do PR. */
enum class LocalChangesChoice {
    /** Não há alterações: o checkout vai direto. */
    NONE,
    /** Guarda no stash, que volta ao encerrar a revisão. */
    STASH,
    /** Apaga as alterações (as que o stash guardaria), sem stash: não voltam. */
    DISCARD,
}

/**
 * Onde o usuário estava antes de entrar no PR: a branch (ou o commit, se estava sem branch) e o stash com
 * as alterações que não estavam commitadas, vazio quando não havia.
 */
class ReturnPoint(var pullRequest: Int = 0, var ref: String = "", var branch: Boolean = true, var stash: String = "", var stashMessage: String = "")

/**
 * Pedidos de alteração enviados aos PRs: rede e Git de forma síncrona, então chame fora da EDT, exceto
 * [context], [records], [record] e [returnPoint], que só leem estado local.
 */
@Service(Service.Level.PROJECT)
@State(name = "IronDomePatrulha", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PatrulhaService(private val project: Project) : PersistentStateComponent<PatrulhaService.PatrulhaState> {

    class SendResult(val reviewUrl: String, val requests: List<ChangeRequest>)

    class PatrulhaState {
        var returnPoint: ReturnPoint? = null

        /** Onde o modo revisão começou: a branch ou `@<sha>`, sem branch. Vazio nos estados antigos. */
        var reviewRef: String = ""

        /** PRs com o histórico de pedidos no editor, pelo número. */
        var historyPrs: MutableSet<String> = mutableSetOf()

        /** Pedidos ocultos do editor e expandidos nele, como `12:P3`. */
        var hiddenRequests: MutableSet<String> = mutableSetOf()
        var expandedRequests: MutableSet<String> = mutableSetOf()
    }

    /** O que [enter] fez: o stash criado agora (vazio se não houve) e se ele vai voltar em [leave]. */
    class EnterResult(val stash: String, val stashMessage: String, val restorable: Boolean)

    private var state = PatrulhaState()

    private val filesCache = ConcurrentHashMap<Int, PullRequestFiles>()
    /** O registro de cada PR já interpretado, com a data e o tamanho do `.md` de onde veio. */
    private val recordCache = ConcurrentHashMap<Int, Pair<Pair<Long, Long>, PullRequestRecord>>()

    /** Os arquivos já lidos do PR, se ainda valem para o head [headSha]. */
    fun cachedFiles(number: Int, headSha: String): PullRequestFiles? = filesCache[number]?.takeIf { it.headSha == headSha }

    /** Os arquivos alterados no PR e a base do diff, do GitHub; guardados até o head do PR mudar. */
    fun files(context: RepoContext, pr: PullRequest): PullRequestFiles {
        cachedFiles(pr.number, pr.headSha)?.let { return it }
        val client = client(context)
        val files = client.files(context.repo, pr.number)
        val mergeBase = pr.baseSha.takeIf { it.isNotEmpty() }
            ?.let { base -> runCatching { client.mergeBase(context.repo, base, pr.headSha) }.getOrDefault(base) }
            .orEmpty()
        return PullRequestFiles(pr.headSha, mergeBase, files).also { filesCache[pr.number] = it }
    }

    /**
     * Os arquivos do PR [number] quando o checkout é o head dele, lendo do GitHub se ainda não vieram; nulo fora do
     * head ou sem conta do GitHub. Faz rede: chame fora da EDT.
     */
    fun checkedOutFiles(number: Int): PullRequestFiles? {
        val context = context()?.takeIf { it.account != null } ?: return null
        val head = context.repository.currentRevision ?: return null
        cachedFiles(number, head)?.let { return it }
        val pr = client(context).pullRequest(context.repo, number)
        return if (pr.headSha == head) files(context, pr) else null
    }

    override fun getState(): PatrulhaState = state

    override fun loadState(state: PatrulhaState) {
        this.state = state
    }

    fun isHistoryShown(number: Int): Boolean = number.toString() in state.historyPrs

    /** Os `.md` dos PRs com o histórico no editor; lê só esses, porque o editor pede a cada redesenho. */
    fun historyRecords(): List<PullRequestRecord> = state.historyPrs.mapNotNull { it.toIntOrNull()?.let(::record) }

    /** Liga ou desliga os pedidos enviados ao PR [number] como cartões no editor. */
    fun setHistoryShown(number: Int, shown: Boolean) {
        val changed = if (shown) state.historyPrs.add(number.toString()) else state.historyPrs.remove(number.toString())
        if (changed) historyChanged(number)
    }

    fun isRequestHidden(number: Int, id: String): Boolean = key(number, id) in state.hiddenRequests

    /** O cartão está no editor: o histórico do PR está ligado e o pedido não foi ocultado. */
    fun isInEditor(number: Int, id: String): Boolean = isHistoryShown(number) && !isRequestHidden(number, id)

    fun setRequestsHidden(number: Int, ids: Collection<String>, hidden: Boolean) {
        val keys = ids.map { key(number, it) }
        val changed = if (hidden) state.hiddenRequests.addAll(keys) else state.hiddenRequests.removeAll(keys.toSet())
        if (changed) historyChanged(number)
    }

    fun isRequestExpanded(number: Int, id: String): Boolean = key(number, id) in state.expandedRequests

    fun toggleRequestExpanded(number: Int, id: String) {
        val key = key(number, id)
        if (!state.expandedRequests.remove(key)) state.expandedRequests += key
        historyChanged(number)
    }

    /** Redesenha os cartões do PR [number] nos editores abertos, por exemplo depois que o `.md` mudou no disco. */
    fun historyChanged(number: Int) {
        val paths = record(number)?.requests?.map { it.path }.orEmpty()
        project.service<ReviewCommentService>().refreshPaths(paths)
        project.messageBus.syncPublisher(TOPIC).recordsChanged()
    }

    private fun key(number: Int, id: String) = "$number:$id"

    /**
     * Troca o texto e o código sugerido de um pedido já enviado, no comentário do GitHub e no `.md`. Devolve
     * um aviso quando o pedido está no texto geral da revisão, que só pode ser editado pelo GitHub.
     */
    fun editRequest(context: RepoContext, number: Int, id: String, text: String, suggestion: String): String? {
        val record = record(number) ?: throw PatrulhaException("Nenhum pedido enviado ao PR #$number.")
        val request = record.requests.firstOrNull { it.id == id } ?: throw PatrulhaException("O pedido $id não está mais no PR #$number.")
        if (request.deleted) throw PatrulhaException("O comentário de $id foi apagado no GitHub: não há o que editar lá.")
        val updated = request.copy(
            text = text.trim(),
            suggestion = suggestion.trimEnd().takeUnless { it == request.snippet.trimEnd() }.orEmpty(),
            remote = RemoteStatus.SYNCED,
            remoteUpdatedAt = null,
        )
        val commentId = request.commentId
        val warning = if (commentId != null) {
            val client = client(context)
            val current = try {
                client.reviewComment(context.repo, commentId)
            } catch (e: GitHubException) {
                if (e.status != 404) throw e
                write(record.copy(requests = record.requests.map { if (it.id == id) it.copy(remote = RemoteStatus.DELETED) else it }))
                throw PatrulhaException("O comentário de $id foi apagado no GitHub enquanto você editava: o pedido ficou como apagado.")
            }
            val placement = if (current.subjectType == "file") Placement.FILE else Placement.INLINE
            client.updateReviewComment(context.repo, commentId, ReviewPlan.bodyFor(updated.toComment(number), placement))
            null
        } else {
            "O pedido $id está no texto geral da revisão: o .md foi atualizado, mas no GitHub edite o texto da revisão pelo navegador."
        }
        write(record.copy(requests = record.requests.map { if (it.id == id) updated else it }))
        return warning
    }

    fun returnPoint(): ReturnPoint? = state.returnPoint

    /** Onde o modo revisão do PR [number] vale; sair dali encerra a revisão. */
    fun reviewRef(number: Int): String = state.reviewRef.ifEmpty { branchName(number) }

    fun setReviewRef(ref: String?) {
        state.reviewRef = ref.orEmpty()
    }

    /** Troca para [ref], uma branch ou `@<sha>`, como na volta para a revisão de um PR. */
    fun switchTo(context: RepoContext, ref: String) {
        val root = context.repository.root
        git(GitLineHandler(project, root, GitCommand.CHECKOUT).apply { addParameters(ref.removePrefix("@")) })
        context.repository.update()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    /** Devolve o stash de [point], quando o usuário saiu do PR por conta própria; devolve o aviso, se falhar. */
    fun restoreStash(context: RepoContext, point: ReturnPoint): String? {
        val warning = runCatching { popStash(context, point.stash) }.exceptionOrNull()?.let {
            "As alterações continuam no stash \"${point.stashMessage}\": ${it.message}"
        }
        context.repository.update()
        VfsUtil.markDirtyAndRefresh(false, true, true, context.repository.root)
        return warning
    }

    fun forgetReturnPoint() {
        state.returnPoint = null
    }

    fun localChanges(context: RepoContext): LocalChanges {
        val handler = GitLineHandler(project, context.repository.root, GitCommand.STATUS).apply {
            setSilent(true)
            addParameters("--porcelain")
            endOptions()
            addParameters(*workPaths(context))
        }
        val files = gitOutput(handler).filter { it.length > 3 }.map { it.substring(3) }
        return LocalChanges(context.repository.currentBranchName, files)
    }

    /**
     * Guarda no stash ou descarta as alterações locais, conforme [local], e faz o checkout do PR. Lembra de onde
     * o usuário veio para [leave], a não ser que ele já esteja vindo de outro PR, quando vale o ponto de volta original.
     */
    fun enter(context: RepoContext, number: Int, local: LocalChangesChoice): EnterResult {
        val repository = context.repository
        val origin = repository.currentBranchName?.let { it to true } ?: repository.currentRevision?.let { it to false }
        var stashSha = ""
        var stashMessage = ""
        if (local == LocalChangesChoice.DISCARD) discardLocalChanges(context)
        if (local == LocalChangesChoice.STASH) {
            stashMessage = "iron-dome: antes do PR #$number, em ${origin?.first?.take(40) ?: "HEAD"}"
            val before = stashTop(context)
            try {
                git(GitLineHandler(project, repository.root, GitCommand.STASH).apply {
                    addParameters("push", "--include-untracked", "-m", stashMessage)
                    endOptions()
                    addParameters(*workPaths(context))
                })
            } catch (e: PatrulhaException) {
                if (stashTop(context) == before) throw e
                throw PatrulhaException(
                    "${e.message} O stash \"$stashMessage\" chegou a ser criado, mas as alterações continuam nos arquivos: " +
                        "apague esse stash (Git > Unstash Changes) para não ficar com uma cópia repetida.",
                )
            }
            stashSha = stashTop(context)?.takeIf { it != before }.orEmpty()
        }
        val previous = state.returnPoint
        val restorable = previous == null && origin != null
        state.returnPoint = when {
            previous != null -> previous.apply { pullRequest = number }
            origin != null -> ReturnPoint(number, origin.first, origin.second, stashSha, stashMessage.takeIf { stashSha.isNotEmpty() }.orEmpty())
            else -> null
        }
        try {
            checkout(context, number)
        } catch (e: PatrulhaException) {
            if (stashSha.isNotEmpty()) runCatching { popStash(context, stashSha) }
            if (previous == null) state.returnPoint = null
            throw e
        }
        return EnterResult(stashSha, stashMessage, restorable && stashSha.isNotEmpty())
    }

    /** Volta para a branch de antes do PR e devolve o stash; devolve o aviso, se o stash não pôde voltar. */
    fun leave(context: RepoContext): String? {
        val point = state.returnPoint ?: return null
        val root = context.repository.root
        git(GitLineHandler(project, root, GitCommand.CHECKOUT).apply { addParameters(point.ref) })
        state.returnPoint = null
        val warning = if (point.stash.isEmpty()) {
            null
        } else {
            runCatching { popStash(context, point.stash) }.exceptionOrNull()?.let {
                "As alterações continuam no stash \"${point.stashMessage}\": ${it.message}"
            }
        }
        context.repository.update()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        return warning
    }

    private fun discardLocalChanges(context: RepoContext) {
        val root = context.repository.root
        discard(root.toNioPath(), workPaths(context)) { args ->
            val command = when (args.first()) {
                "reset" -> GitCommand.RESET
                "checkout" -> GitCommand.CHECKOUT
                else -> GitCommand.LS_FILES
            }
            gitOutput(GitLineHandler(project, root, command).apply {
                setSilent(command == GitCommand.LS_FILES)
                addParameters(args.drop(1))
            }).joinToString("\n")
        }
        context.repository.update()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    /** [KEPT_OUT] que o `.gitignore` ignora, pelo `git check-ignore`; com o Git falhando, nenhum. */
    private fun workPaths(context: RepoContext): Array<String> {
        val ignored = gitOutput(GitLineHandler(project, context.repository.root, GitCommand.CHECK_IGNORE).apply {
            setSilent(true)
            endOptions()
            addParameters(*KEPT_OUT)
        }, failOnError = false).map { it.trim().trimEnd('/') }.toSet()
        return workPaths(ignored)
    }

    private fun stashTop(context: RepoContext): String? =
        gitOutput(GitLineHandler(project, context.repository.root, GitCommand.REV_PARSE).apply {
            setSilent(true)
            addParameters("-q", "--verify", "refs/stash")
        }, failOnError = false).firstOrNull()?.trim()?.ifEmpty { null }

    private fun popStash(context: RepoContext, sha: String) {
        val root = context.repository.root
        val list = gitOutput(GitLineHandler(project, root, GitCommand.STASH).apply {
            setSilent(true)
            addParameters("list", "--format=%H")
        })
        val index = list.indexOfFirst { it.trim() == sha }
        if (index < 0) throw PatrulhaException("o stash não está mais na lista")
        git(GitLineHandler(project, root, GitCommand.STASH).apply { addParameters("pop", "stash@{$index}") })
    }

    private fun gitOutput(handler: GitLineHandler, failOnError: Boolean = true): List<String> {
        val result = Git.getInstance().runCommand(handler)
        if (failOnError && !result.success()) throw PatrulhaException("Git: ${result.errorOutputAsJoinedString.ifBlank { "o comando falhou" }}")
        return if (result.success()) result.output else emptyList()
    }

    fun context(): RepoContext? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val root = project.rootPath()
        // O repositório do projeto escolhido no Iron Dome: o da própria pasta ou o que a contém.
        val ordered = repositories.sortedWith(
            compareByDescending<GitRepository> { it.root.path == root }.thenByDescending { root != null && root.startsWith(it.root.path + "/") },
        )
        for (repository in ordered) {
            val remotes = repository.remotes.sortedByDescending { it.name == "origin" }
            for (remote in remotes) {
                val repo = remote.urls.firstNotNullOfOrNull(GitHubRepo::fromRemoteUrl) ?: continue
                return RepoContext(repository, remote.name, repo, accountFor(repo))
            }
        }
        return null
    }

    private fun accountFor(repo: GitHubRepo): GithubAccount? {
        val accounts = GHAccountsUtil.accounts.filter { it.server.host.equals(repo.host, ignoreCase = true) }
        val preferred = GHAccountsUtil.getSingleOrDefaultAccount(project)
        return preferred?.takeIf { it in accounts } ?: accounts.firstOrNull()
    }

    /** Pode abrir o login do GitHub se o token tiver expirado. */
    fun client(context: RepoContext): GitHubClient {
        val account = context.account
            ?: throw PatrulhaException("Nenhuma conta do GitHub em ${context.repo.host}. Entre em Settings > Version Control > GitHub.")
        val token = GHCompatibilityUtil.getOrRequestToken(account, project)
            ?: throw PatrulhaException("Sem token para a conta ${account.name}. Entre de novo em Settings > Version Control > GitHub.")
        return GitHubClient(account.server.toApiUrl(), account.server.toGraphQLUrl(), token)
    }

    /** Traz o head do PR e faz o checkout dele na branch local `pr-<número>`. */
    fun checkout(context: RepoContext, number: Int) {
        val root = context.repository.root
        val ref = "refs/remotes/${context.remote}/pull/$number"
        git(GitLineHandler(project, root, GitCommand.FETCH).apply { addParameters(context.remote, "+refs/pull/$number/head:$ref") })
        git(GitLineHandler(project, root, GitCommand.CHECKOUT).apply { addParameters("-B", branchName(number), ref) })
        context.repository.update()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    private fun git(handler: GitLineHandler) {
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw PatrulhaException("Git: ${result.errorOutputAsJoinedString.ifBlank { "o comando falhou" }}")
    }

    /**
     * Envia os rascunhos ao PR e grava os pedidos no `.md`. Com [asDraft], eles vão para a revisão pendente do
     * GitHub (só quem revisa vê, até [publishDraft]), acrescentados à que já existir; senão, numa revisão
     * publicada com [event], o [ReviewVerdict.event]. Não tira os rascunhos do Radar: isso é com quem
     * chama, na EDT.
     */
    fun send(context: RepoContext, pr: PullRequest, drafts: List<ReviewComment>, general: String, event: String, asDraft: Boolean): SendResult {
        val client = client(context)
        val planned = ReviewPlan.plan(drafts, client.files(context.repo, pr.number))
        var existing = record(pr.number)?.let { reconcileDraft(client, context, it).record }
        val sentAt = LocalDateTime.now().toString()
        val posted = mutableListOf<GitHubClient.PostedComment>()
        val reviewUrl: String
        if (asDraft) {
            val (url, pending) = sendToDraft(client, context, pr, planned, general, existing, posted)
            reviewUrl = url
            existing = (existing ?: PullRequestRecord(pr.number, pr.title, pr.url, pr.headRef, pr.baseRef, emptyList()))
                .copy(pendingReviewId = pending.id, pendingReviewNodeId = pending.nodeId)
        } else {
            val inline = planned.filter { it.placement == Placement.INLINE }
            val review = try {
                client.createReview(
                    context.repo, pr.number, pr.headSha,
                    ReviewPlan.reviewBody(general, planned).ifBlank { if (event == ReviewVerdict.APPROVE.event) "" else DEFAULT_BODY }, event,
                    inline.map { GitHubClient.InlineComment(it.comment.filePath, it.startLine, it.line!!, it.body) },
                )
            } catch (e: GitHubException) {
                throw when {
                    e.status == 422 && existing?.pendingReviewId != null ->
                        PatrulhaException("Há um rascunho deste PR no GitHub: publique ou descarte o rascunho antes de publicar outra revisão.")
                    e.status == 422 -> refused(e)
                    else -> e
                }
            }
            reviewUrl = review.url
            posted += client.reviewComments(context.repo, pr.number, review.id)
            planned.filter { it.placement == Placement.FILE }.forEach { request ->
                posted += client.createFileComment(context.repo, pr.number, pr.headSha, request.comment.filePath, request.body)
            }
        }
        val requests = planned.map { request ->
            val comment = posted.firstOrNull { ReviewPlan.marker(request.comment.id) in it.body }
            changeRequest(request.comment, pr.headSha, comment, reviewUrl, sentAt, pending = asDraft)
        }
        val known = existing?.requests.orEmpty()
        write(
            PullRequestRecord(
                pr.number, pr.title, pr.url, pr.headRef, pr.baseRef,
                known + requests.map { request -> if (known.any { it.id == request.id }) request.copy(id = uniqueId(request.id, known)) else request },
                existing?.checkedAt,
                existing?.pendingReviewId,
                existing?.pendingReviewNodeId,
            ),
        )
        return SendResult(reviewUrl, requests)
    }

    /**
     * Põe [planned] na revisão pendente: cria uma, ou acrescenta à que [existing] já tem. Comentários de arquivo
     * que o GitHub não aceitar no rascunho vão para o texto dele.
     */
    private fun sendToDraft(
        client: GitHubClient,
        context: RepoContext,
        pr: PullRequest,
        planned: List<PlannedRequest>,
        general: String,
        existing: PullRequestRecord?,
        posted: MutableList<GitHubClient.PostedComment>,
    ): Pair<String, GitHubClient.Review> {
        val current = existing?.pendingReviewId?.let { client.review(context.repo, pr.number, it) }?.takeIf { it.pending }
        val inline = planned.filter { it.placement == Placement.INLINE }
        val inBody = planned.filter { it.placement == Placement.BODY }
        val review = if (current == null) {
            try {
                client.createReview(
                    context.repo, pr.number, pr.headSha, ReviewPlan.reviewBody(general, inBody), null,
                    inline.map { GitHubClient.InlineComment(it.comment.filePath, it.startLine, it.line!!, it.body) },
                ).also { posted += client.reviewComments(context.repo, pr.number, it.id) }
            } catch (e: GitHubException) {
                throw if (e.status == 422) {
                    PatrulhaException("Você já tem um rascunho de revisão neste PR, aberto pelo GitHub: publique ou descarte lá antes de enviar por aqui.")
                } else e
            }
        } else {
            inline.forEach { posted += client.addPendingComment(current.nodeId, it.comment.filePath, it.startLine, it.line, it.body) }
            current
        }
        val fallback = planned.filter { it.placement == Placement.FILE }.mapNotNull { request ->
            runCatching { client.addPendingComment(review.nodeId, request.comment.filePath, null, null, request.body) }
                .onSuccess { posted += it }
                .exceptionOrNull()
                ?.let { PlannedRequest(request.comment, Placement.BODY, ReviewPlan.bodyFor(request.comment, Placement.BODY), null, null) }
        }
        // Num rascunho novo, o texto geral e os pedidos de texto já foram na criação; falta só o que não coube no arquivo.
        val extra = if (current == null) ReviewPlan.reviewBody("", fallback) else ReviewPlan.reviewBody(general, inBody + fallback)
        if (extra.isNotBlank()) {
            val body = client.review(context.repo, pr.number, review.id)?.body.orEmpty()
            client.updateReviewBody(context.repo, pr.number, review.id, listOf(body.trim(), extra).filter { it.isNotBlank() }.joinToString("\n\n"))
        }
        return review.url to review
    }

    /** O rascunho como está no GitHub: publicado lá, os pedidos viram publicados; descartado, saem do `.md`. */
    private class DraftState(val record: PullRequestRecord, val comments: List<GitHubClient.PostedComment>, val published: Boolean, val discarded: Boolean)

    private fun reconcileDraft(client: GitHubClient, context: RepoContext, record: PullRequestRecord): DraftState {
        val id = record.pendingReviewId ?: return DraftState(record, emptyList(), published = false, discarded = false)
        val review = client.review(context.repo, record.number, id)
        return when {
            review == null -> DraftState(record.withoutDraft(published = false), emptyList(), published = false, discarded = true)
            !review.pending -> DraftState(record.withoutDraft(published = true), emptyList(), published = true, discarded = false)
            else -> DraftState(record, client.reviewComments(context.repo, record.number, id), published = false, discarded = false)
        }
    }

    /** Publica o rascunho do PR com [event], com [general] antes do texto que ele já tinha. Devolve o link da revisão. */
    fun publishDraft(context: RepoContext, number: Int, event: String, general: String): String {
        val record = record(number) ?: throw PatrulhaException("Nenhum pedido enviado ao PR #$number.")
        val id = record.pendingReviewId ?: throw PatrulhaException("O PR #$number não tem rascunho no GitHub.")
        val client = client(context)
        val current = client.review(context.repo, number, id)
        if (current == null) {
            write(record.withoutDraft(published = false))
            throw PatrulhaException("O rascunho do PR #$number foi descartado no GitHub: os pedidos dele saíram do .md.")
        }
        if (!current.pending) {
            write(record.withoutDraft(published = true))
            return current.url
        }
        val body = listOf(general.trim(), current.body.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
            .ifBlank { if (event == ReviewVerdict.APPROVE.event) "" else DEFAULT_BODY }
        val submitted = try {
            client.submitReview(context.repo, number, id, event, body)
        } catch (e: GitHubException) {
            throw if (e.status == 422) refused(e) else e
        }
        write(record.withoutDraft(published = true))
        return submitted.url.ifBlank { current.url }
    }

    /**
     * Publica o veredito do PR, com [body] no texto geral. O rascunho que estiver no GitHub vai junto, na mesma
     * revisão; para levar também os comentários não enviados, mande-os antes ao rascunho com [send]. Devolve o
     * link da revisão.
     */
    fun submitVerdict(context: RepoContext, pr: PullRequest, verdict: ReviewVerdict, body: String): String {
        val client = client(context)
        val draft = record(pr.number)?.let { reconcileDraft(client, context, it) }
        if (draft != null && (draft.published || draft.discarded)) write(draft.record)
        if (draft?.record?.pendingReviewId != null) return publishDraft(context, pr.number, verdict.event, body)
        val text = body.trim()
        if (verdict.needsBody && text.isEmpty()) throw PatrulhaException("Escreva o texto da revisão: o GitHub exige texto para ${verdict.label.lowercase()} sem comentários.")
        val review = try {
            client.createReview(context.repo, pr.number, pr.headSha, text, verdict.event, emptyList())
        } catch (e: GitHubException) {
            throw if (e.status == 422) refused(e) else e
        }
        return review.url.ifBlank { pr.url }
    }

    private fun refused(e: GitHubException) = PatrulhaException(
        "O GitHub recusou a revisão (${e.message}). Ele não deixa aprovar nem solicitar alterações no próprio PR, " +
            "nem publicar outra revisão com um rascunho seu aberto pelo GitHub.",
    )

    /** Descarta o rascunho no GitHub, com todos os comentários dele, e tira os pedidos dele do `.md`. Devolve quantos saíram. */
    fun discardDraft(context: RepoContext, number: Int): Int {
        val record = record(number) ?: return 0
        val id = record.pendingReviewId ?: return 0
        client(context).deletePendingReview(context.repo, number, id)
        val removed = record.drafts.size
        write(record.withoutDraft(published = false))
        return removed
    }

    /**
     * Apaga todos os pedidos do PR: descarta o rascunho no GitHub e, com [publishedOnGitHub], apaga também os
     * comentários publicados (e as conversas deles). O `.md` do PR sai. Devolve quantos pedidos saíram.
     */
    fun deleteAll(context: RepoContext?, number: Int, publishedOnGitHub: Boolean): Int {
        val record = record(number) ?: return 0
        if (record.pendingReviewId != null || publishedOnGitHub) {
            val repo = context ?: throw PatrulhaException("O projeto não tem um remote do GitHub.")
            val client = client(repo)
            record.pendingReviewId?.let { client.deletePendingReview(repo.repo, number, it) }
            if (publishedOnGitHub) {
                record.requests.filter { !it.pending && !it.deleted }.mapNotNull { it.commentId }
                    .forEach { client.deleteReviewComment(repo.repo, it) }
            }
        }
        record.requests.forEach {
            state.hiddenRequests.remove(key(number, it.id))
            state.expandedRequests.remove(key(number, it.id))
        }
        recordFile(number)?.let { file ->
            Files.deleteIfExists(file)
            LocalFileSystem.getInstance().refreshNioFiles(listOf(file), true, false, null)
        }
        project.messageBus.syncPublisher(TOPIC).recordsChanged()
        if (isHistoryShown(number)) ApplicationManager.getApplication().invokeLater({ historyChanged(number) }, project.disposed)
        return record.requests.size
    }

    private fun changeRequest(
        comment: ReviewComment,
        commit: String,
        posted: GitHubClient.PostedComment?,
        reviewUrl: String,
        sentAt: String,
        pending: Boolean,
    ) =
        ChangeRequest(
            id = comment.id,
            path = comment.filePath,
            target = comment.target,
            startLine = if (comment.target == CommentTarget.LINES) comment.startLine + 1 else 0,
            endLine = if (comment.target == CommentTarget.LINES) comment.endLine + 1 else 0,
            snippet = comment.snippet,
            text = comment.text.trim(),
            commit = commit,
            commentId = posted?.id,
            url = posted?.url?.ifBlank { null } ?: reviewUrl.ifBlank { null },
            sentAt = sentAt,
            suggestion = comment.suggestion,
            pending = pending,
        )

    private fun uniqueId(id: String, known: List<ChangeRequest>): String {
        var index = 2
        while (known.any { it.id == "$id-$index" }) index++
        return "$id-$index"
    }

    /** Confere o código no head atual e as conversas no PR, e regrava o `.md`. */
    fun verify(context: RepoContext, number: Int): PullRequestRecord {
        val client = client(context)
        val draft = reconcileDraft(client, context, record(number) ?: throw PatrulhaException("Nenhum pedido enviado ao PR #$number."))
        val record = draft.record
        val pr = client.pullRequest(context.repo, number)
        val threads = client.reviewThreads(context.repo, number)
        val me = client.currentUser()
        val contents = mutableMapOf<Pair<String, String>, String?>()
        fun content(path: String, ref: String) = contents.getOrPut(path to ref) { client.fileContent(context.repo, path, ref) }
        val remote = (client.pullRequestComments(context.repo, number) + draft.comments).associateBy { it.id }

        val checked = record.requests.map { RemoteSync.apply(it, remote) }.map { request ->
            // Rascunhos o autor ainda não viu: não há o que conferir no código nem na conversa.
            if (request.deleted || request.pending) return@map request
            val needsCode = request.target != CommentTarget.PACKAGE && pr.headSha != request.commit
            val head = if (needsCode) content(request.path, pr.headSha) else null
            val sent = if (needsCode && request.target == CommentTarget.FILE) content(request.path, request.commit) else null
            val code = CodeCheck.check(request, pr.headSha, head, sent)
            val (thread, reply) = ThreadCheck.check(request, threads, me)
            request.copy(code = code, codeCommit = pr.headSha.takeIf { code != CodeStatus.NOT_APPLICABLE }, thread = thread, lastReply = reply)
        }
        return write(
            record.copy(
                title = pr.title, url = pr.url, headRef = pr.headRef, baseRef = pr.baseRef,
                requests = checked, checkedAt = LocalDateTime.now().toString(),
            ),
        )
    }

    /** O que a sincronização encontrou de novo: pedidos editados e apagados no GitHub desde a última vez. */
    class SyncResult(
        val number: Int,
        val edited: List<String>,
        val deleted: List<String>,
        /** O rascunho foi publicado ou descartado pelo GitHub, fora do plugin. */
        val draftPublished: Boolean = false,
        val draftDiscarded: Boolean = false,
    ) {
        val changed: Boolean get() = edited.isNotEmpty() || deleted.isNotEmpty() || draftPublished || draftDiscarded
    }

    /**
     * Traz para o `.md` o que mudou direto no GitHub: comentários editados ou apagados pelo revisor e o rascunho
     * publicado ou descartado lá.
     */
    fun sync(context: RepoContext, number: Int): SyncResult {
        val original = record(number) ?: return SyncResult(number, emptyList(), emptyList())
        if (original.pendingReviewId == null && original.requests.none { it.commentId != null && !it.deleted }) {
            return SyncResult(number, emptyList(), emptyList())
        }
        val client = client(context)
        val draft = reconcileDraft(client, context, original)
        val record = draft.record
        val remote = (client.pullRequestComments(context.repo, number) + draft.comments).associateBy { it.id }
        val synced = record.requests.map { RemoteSync.apply(it, remote) }
        val changed = synced.zip(record.requests).filter { (now, before) -> now != before }.map { it.first }
        if (changed.isNotEmpty() || record != original) write(record.copy(requests = synced))
        return SyncResult(
            number,
            changed.filterNot { it.deleted }.map { it.id },
            changed.filter { it.deleted }.map { it.id },
            draft.published,
            draft.discarded,
        )
    }

    fun discussions(context: RepoContext, number: Int): List<Discussion> = client(context).discussions(context.repo, number)

    /** Para onde vai a resposta numa conversa de linha. */
    enum class ReplyTarget { PUBLISHED, DRAFT }

    /**
     * Responde a [to], de [discussion]. Na conversa de linha, a resposta entra nela: no rascunho da revisão, se o
     * PR tem um aberto pelo plugin, para sair junto com ele, ou publicada. Nos
     * comentários gerais e nas revisões, vira comentário novo no PR, citando [to].
     */
    fun reply(context: RepoContext, number: Int, discussion: Discussion, to: DiscussionComment, body: String): Pair<DiscussionComment, ReplyTarget> {
        val client = client(context)
        if (!discussion.repliesInPlace) {
            return client.addConversationComment(context.repo, number, Discussions.quote(to, body)) to ReplyTarget.PUBLISHED
        }
        val draft = record(number)?.let { original ->
            reconcileDraft(client, context, original).also { if (it.record != original) write(it.record) }.record
        }?.pendingReviewNodeId
        val posted = client.replyToThread(discussion.nodeId, body.trim(), draft)
        return posted to if (posted.pending) ReplyTarget.DRAFT else ReplyTarget.PUBLISHED
    }
    /**
     * Tira o pedido do `.md`; com [onGitHub], apaga também o comentário no PR, o que some com a conversa dele.
     * Pedidos no texto geral da revisão só saem do `.md`.
     */
    fun deleteRequest(context: RepoContext?, number: Int, id: String, onGitHub: Boolean) {
        val record = record(number) ?: return
        val request = record.requests.firstOrNull { it.id == id } ?: return
        val commentId = request.commentId
        if (onGitHub && commentId != null && !request.deleted) {
            val repo = context ?: throw PatrulhaException("O projeto não tem um remote do GitHub.")
            client(repo).deleteReviewComment(repo.repo, commentId)
        }
        state.hiddenRequests.remove(key(number, id))
        state.expandedRequests.remove(key(number, id))
        write(record.copy(requests = record.requests.filterNot { it.id == id }))
    }

    fun records(): List<PullRequestRecord> {
        val dir = recordsDir()?.takeIf(Files::isDirectory) ?: return emptyList()
        return Files.list(dir).use { files ->
            files.toList().filter { PullRequestRecord.numberOf(it.fileName.toString()) != null }
                .mapNotNull { file -> runCatching { ChangeRequestMarkdown.parse(Files.readString(file)) }.getOrNull() }
        }.sortedByDescending { it.number }
    }

    /**
     * O registro do PR, lido do `.md` só quando o arquivo mudou: as abas pedem o registro a cada atualização,
     * várias vezes na EDT, e ler e interpretar o arquivo toda vez trava a interface.
     */
    fun record(number: Int): PullRequestRecord? {
        val file = recordFile(number)?.takeIf(Files::exists) ?: return null.also { recordCache.remove(number) }
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() to Files.size(file) }.getOrNull()
        recordCache[number]?.takeIf { stamp != null && it.first == stamp }?.let { return it.second }
        val record = runCatching { ChangeRequestMarkdown.parse(Files.readString(file)) }.getOrNull() ?: return null
        if (stamp != null) recordCache[number] = stamp to record
        return record
    }
    fun recordFile(number: Int): Path? = recordsDir()?.resolve(PullRequestRecord.fileName(number))

    fun recordsDir(): Path? = project.rootPath()?.let { Path.of(it, ReviewCommentService.REVIEW_DIR, PullRequestRecord.DIR) }

    private fun write(record: PullRequestRecord): PullRequestRecord {
        val file = recordFile(record.number) ?: throw PatrulhaException("O projeto não tem pasta no disco.")
        Files.createDirectories(file.parent)
        Files.writeString(file, ChangeRequestMarkdown.render(record))
        recordCache.remove(record.number)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(file), true, false, null)
        project.messageBus.syncPublisher(TOPIC).recordsChanged()
        if (isHistoryShown(record.number)) {
            ApplicationManager.getApplication().invokeLater({ historyChanged(record.number) }, project.disposed)
        }
        return record
    }

    fun interface Listener {
        fun recordsChanged()
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        const val DEFAULT_BODY = "Pedidos de alteração enviados pelo Iron Dome IA."

        fun branchName(number: Int) = "pr-$number"

        /** A branch atual ou, sem branch, `@<sha>` do commit. */
        fun refOf(repository: GitRepository): String? = repository.currentBranchName ?: repository.currentRevision?.let { "@$it" }

        /** Revisões do Radar e pedidos dos PRs ficam fora do stash, e as configurações da IDE também. */
        private val KEPT_OUT = arrayOf(ReviewCommentService.REVIEW_DIR, ".idea")

        /**
         * Os caminhos do stash e do status: o projeto menos [KEPT_OUT]. Os que estão em [ignored] ficam sem o
         * exclude: o `stash --include-untracked` já os deixa de fora, e com o exclude ele falha ("paths are
         * ignored by one of your .gitignore files") depois de já ter criado o stash.
         */
        fun workPaths(ignored: Set<String>): Array<String> =
            arrayOf(".") + KEPT_OUT.filterNot { it in ignored }.map { ":(exclude)$it" }

        /**
         * Apaga o que o stash guardaria em [paths]: tira tudo do index, volta os versionados ao HEAD e remove os
         * não versionados, com as pastas que ficarem vazias; os ignorados pelo `.gitignore` ficam. [git] roda
         * `git` com os argumentos, na raiz [root], e devolve a saída.
         */
        fun discard(root: Path, paths: Array<String>, git: (List<String>) -> String) {
            git(listOf("reset", "-q", "--") + paths)
            git(listOf("checkout", "--") + paths)
            val untracked = git(listOf("ls-files", "--others", "--exclude-standard", "-z", "--") + paths)
                .split('\u0000').map { it.trim('\n', '\r') }.filter { it.isNotBlank() }
            val base = root.toAbsolutePath().normalize()
            untracked.forEach { relative ->
                val file = base.resolve(relative).normalize()
                if (!file.startsWith(base)) return@forEach
                Files.deleteIfExists(file)
                var dir = file.parent
                while (dir != null && dir != base && Files.isDirectory(dir) && Files.list(dir).use { !it.findAny().isPresent }) {
                    Files.delete(dir)
                    dir = dir.parent
                }
            }
        }

        fun getInstance(project: Project): PatrulhaService = project.service()
    }
}
