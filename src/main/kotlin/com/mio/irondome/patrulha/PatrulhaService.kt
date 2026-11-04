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
            addParameters(*WORK_PATHS)
        }
        val files = gitOutput(handler).filter { it.length > 3 }.map { it.substring(3) }
        return LocalChanges(context.repository.currentBranchName, files)
    }

    /**
     * Guarda as alterações locais no stash, se [stash], e faz o checkout do PR. Lembra de onde o usuário veio
     * para [leave], a não ser que ele já esteja vindo de outro PR, quando vale o ponto de volta original.
     */
    fun enter(context: RepoContext, number: Int, stash: Boolean): EnterResult {
        val repository = context.repository
        val origin = repository.currentBranchName?.let { it to true } ?: repository.currentRevision?.let { it to false }
        var stashSha = ""
        var stashMessage = ""
        if (stash) {
            stashMessage = "iron-dome: antes do PR #$number, em ${origin?.first?.take(40) ?: "HEAD"}"
            val before = stashTop(context)
            git(GitLineHandler(project, repository.root, GitCommand.STASH).apply {
                addParameters("push", "--include-untracked", "-m", stashMessage)
                endOptions()
                addParameters(*WORK_PATHS)
            })
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
        val root = project.basePath?.replace('\\', '/')
        val ordered = repositories.sortedByDescending { it.root.path == root }
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
     * Envia os rascunhos como uma revisão do PR e grava os pedidos no `.md`. [event] é `REQUEST_CHANGES`
     * ou `COMMENT`. Não tira os rascunhos do Radar: isso é com quem chama, na EDT.
     */
    fun send(context: RepoContext, pr: PullRequest, drafts: List<ReviewComment>, general: String, event: String): SendResult {
        val client = client(context)
        val planned = ReviewPlan.plan(drafts, client.files(context.repo, pr.number))
        val inline = planned.filter { it.placement == Placement.INLINE }
        val body = ReviewPlan.reviewBody(general, planned).ifBlank { DEFAULT_BODY }
        val review = client.createReview(
            context.repo, pr.number, pr.headSha, body, event,
            inline.map { GitHubClient.InlineComment(it.comment.filePath, it.startLine, it.line!!, it.body) },
        )
        val posted = client.reviewComments(context.repo, pr.number, review.id).toMutableList()
        planned.filter { it.placement == Placement.FILE }.forEach { request ->
            posted += client.createFileComment(context.repo, pr.number, pr.headSha, request.comment.filePath, request.body)
        }
        val sentAt = LocalDateTime.now().toString()
        val requests = planned.map { request ->
            val comment = posted.firstOrNull { ReviewPlan.marker(request.comment.id) in it.body }
            changeRequest(request.comment, pr.headSha, comment, review.url, sentAt)
        }
        val existing = record(pr.number)
        val known = existing?.requests.orEmpty()
        write(
            PullRequestRecord(
                pr.number, pr.title, pr.url, pr.headRef, pr.baseRef,
                known + requests.map { request -> if (known.any { it.id == request.id }) request.copy(id = uniqueId(request.id, known)) else request },
                existing?.checkedAt,
            ),
        )
        return SendResult(review.url, requests)
    }

    private fun changeRequest(comment: ReviewComment, commit: String, posted: GitHubClient.PostedComment?, reviewUrl: String, sentAt: String) =
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
        )

    private fun uniqueId(id: String, known: List<ChangeRequest>): String {
        var index = 2
        while (known.any { it.id == "$id-$index" }) index++
        return "$id-$index"
    }

    /** Confere o código no head atual e as conversas no PR, e regrava o `.md`. */
    fun verify(context: RepoContext, number: Int): PullRequestRecord {
        val record = record(number) ?: throw PatrulhaException("Nenhum pedido enviado ao PR #$number.")
        val client = client(context)
        val pr = client.pullRequest(context.repo, number)
        val threads = client.reviewThreads(context.repo, number)
        val me = client.currentUser()
        val contents = mutableMapOf<Pair<String, String>, String?>()
        fun content(path: String, ref: String) = contents.getOrPut(path to ref) { client.fileContent(context.repo, path, ref) }
        val remote = client.pullRequestComments(context.repo, number).associateBy { it.id }

        val checked = record.requests.map { RemoteSync.apply(it, remote) }.map { request ->
            if (request.deleted) return@map request
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
    class SyncResult(val number: Int, val edited: List<String>, val deleted: List<String>) {
        val changed: Boolean get() = edited.isNotEmpty() || deleted.isNotEmpty()
    }

    /** Traz para o `.md` o que o revisor editou ou apagou nos comentários do PR direto no GitHub. */
    fun sync(context: RepoContext, number: Int): SyncResult {
        val record = record(number) ?: return SyncResult(number, emptyList(), emptyList())
        if (record.requests.none { it.commentId != null && !it.deleted }) return SyncResult(number, emptyList(), emptyList())
        val remote = client(context).pullRequestComments(context.repo, number).associateBy { it.id }
        val synced = record.requests.map { RemoteSync.apply(it, remote) }
        val changed = synced.zip(record.requests).filter { (now, before) -> now != before }.map { it.first }
        if (changed.isNotEmpty()) write(record.copy(requests = synced))
        return SyncResult(number, changed.filterNot { it.deleted }.map { it.id }, changed.filter { it.deleted }.map { it.id })
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

    fun record(number: Int): PullRequestRecord? =
        recordFile(number)?.takeIf(Files::exists)?.let { runCatching { ChangeRequestMarkdown.parse(Files.readString(it)) }.getOrNull() }

    fun recordFile(number: Int): Path? = recordsDir()?.resolve(PullRequestRecord.fileName(number))

    fun recordsDir(): Path? = project.basePath?.let { Path.of(it, ReviewCommentService.REVIEW_DIR, PullRequestRecord.DIR) }

    private fun write(record: PullRequestRecord): PullRequestRecord {
        val file = recordFile(record.number) ?: throw PatrulhaException("O projeto não tem pasta no disco.")
        Files.createDirectories(file.parent)
        Files.writeString(file, ChangeRequestMarkdown.render(record))
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
        private val WORK_PATHS = arrayOf(".", ":(exclude)${ReviewCommentService.REVIEW_DIR}", ":(exclude).idea")

        fun getInstance(project: Project): PatrulhaService = project.service()
    }
}
