package com.mio.irondome.patrulha

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mio.irondome.radar.CommentTarget
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class CodeStatus(val label: String) {
    NOT_CHECKED("Não verificado"),
    NO_NEW_COMMITS("Sem commits novos"),
    UNCHANGED("Sem alteração"),
    MOVED("Só mudou de lugar"),
    CHANGED("Alterado"),
    /** O head tem o código sugerido no pedido. */
    AS_SUGGESTED("Como sugerido"),
    FILE_REMOVED("Arquivo removido"),
    NOT_APPLICABLE("Não se aplica"),
}

enum class ThreadStatus(val label: String) {
    NONE("Sem conversa"),
    NOT_CHECKED("Não verificada"),
    OPEN("Aberta"),
    REPLIED("Respondida"),
    RESOLVED("Resolvida"),
}

/** O comentário do pedido no GitHub, comparado com o `.md` na última sincronização. */
enum class RemoteStatus(val label: String) {
    SYNCED("Sincronizado"),
    EDITED("Editado no GitHub"),
    DELETED("Apagado no GitHub"),
}

/**
 * Pedido de alteração enviado a um PR. Linhas 1-based, como no GitHub; 0 fora de [CommentTarget.LINES].
 * [commit] é o head do PR no envio; [commentId] é o comentário no GitHub, nulo quando o pedido foi no
 * texto geral da revisão (pacotes e arquivos fora do PR).
 */
data class ChangeRequest(
    val id: String,
    val path: String,
    val target: CommentTarget,
    val startLine: Int,
    val endLine: Int,
    val snippet: String,
    val text: String,
    val commit: String,
    val commentId: Long?,
    val url: String?,
    val sentAt: String,
    val code: CodeStatus = CodeStatus.NOT_CHECKED,
    val codeCommit: String? = null,
    val thread: ThreadStatus = if (commentId == null) ThreadStatus.NONE else ThreadStatus.NOT_CHECKED,
    val lastReply: String? = null,
    /** Código de exemplo enviado com o pedido; vazio quando não houve. */
    val suggestion: String = "",
    val remote: RemoteStatus = RemoteStatus.SYNCED,
    /** Quando o comentário foi alterado no GitHub, como o GitHub informa (`updated_at`). */
    val remoteUpdatedAt: String? = null,
    /** Na revisão pendente do GitHub: só quem revisa vê, até publicar. */
    val pending: Boolean = false,
) {
    /** Apagado no GitHub: fica no `.md` como registro, mas fora das contas e das verificações. */
    val deleted: Boolean get() = remote == RemoteStatus.DELETED

    val location: String
        get() = when (target) {
            CommentTarget.LINES -> if (startLine == endLine) "linha $startLine" else "linhas $startLine–$endLine"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.PACKAGE -> "pacote inteiro"
        }

    /** Feito por pelo menos um dos sinais: o código mudou ou a conversa foi resolvida. */
    val likelyDone: Boolean
        get() = !deleted && (
            code == CodeStatus.CHANGED || code == CodeStatus.AS_SUGGESTED || code == CodeStatus.FILE_REMOVED ||
                thread == ThreadStatus.RESOLVED
            )

    /** Nenhum sinal de que foi feito, depois de pelo menos uma verificação com commits novos. */
    val likelyPending: Boolean
        get() = !deleted && !likelyDone && (code == CodeStatus.UNCHANGED || code == CodeStatus.MOVED)
}

/** Os pedidos de um PR, gravados em `iron-dome/pull-requests/pr-<número>.md`. */
data class PullRequestRecord(
    val number: Int,
    val title: String,
    val url: String,
    val headRef: String,
    val baseRef: String,
    val requests: List<ChangeRequest>,
    val checkedAt: String? = null,
    /** A revisão pendente (rascunho) no GitHub, com os pedidos [ChangeRequest.pending]; nula sem rascunho. */
    val pendingReviewId: Long? = null,
    /** O id da revisão pendente no GraphQL, para acrescentar comentários a ela. */
    val pendingReviewNodeId: String? = null,
) {
    /** Os pedidos que contam para atendido ou pendente: publicados, sem os apagados no GitHub. */
    val active: List<ChangeRequest> get() = requests.filterNot { it.deleted || it.pending }

    val drafts: List<ChangeRequest> get() = requests.filter { it.pending && !it.deleted }

    /** Sem a revisão pendente: os rascunhos viraram publicados, ou foram descartados com ela. */
    fun withoutDraft(published: Boolean): PullRequestRecord = copy(
        requests = requests.mapNotNull { request ->
            when {
                !request.pending -> request
                published -> request.copy(pending = false)
                else -> null
            }
        },
        pendingReviewId = null,
        pendingReviewNodeId = null,
    )

    companion object {
        const val DIR = "pull-requests"

        fun fileName(number: Int) = "pr-$number.md"

        fun numberOf(fileName: String): Int? = Regex("""pr-(\d+)\.md""").matchEntire(fileName)?.groupValues?.get(1)?.toInt()
    }
}

object ChangeRequestMarkdown {

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private const val RECORD_TAG = "iron-dome-pr"
    private const val REQUEST_TAG = "iron-dome-pedido"
    private val META = Regex("""^<!-- (\S+) (\{.*}) -->$""")

    private class RecordMeta(
        val number: Int, val title: String, val url: String, val head: String, val base: String, val checkedAt: String?,
        val pendingReview: Long? = null,
        val pendingReviewNode: String? = null,
    )

    private class RequestMeta(
        val id: String, val path: String, val target: String, val start: Int, val end: Int, val snippet: String,
        val text: String, val commit: String, val comment: Long?, val url: String?, val sentAt: String,
        val code: String, val codeCommit: String?, val thread: String, val lastReply: String?,
        val suggestion: String? = null,
        val remote: String? = null,
        val remoteUpdatedAt: String? = null,
        val pending: Boolean? = null,
    )

    fun render(record: PullRequestRecord): String = buildString {
        appendLine("# Pedidos de alteração do PR #${record.number}: ${record.title.oneLine()}")
        appendLine()
        val recordMeta = RecordMeta(
            record.number, record.title, record.url, record.headRef, record.baseRef, record.checkedAt,
            record.pendingReviewId, record.pendingReviewNodeId,
        )
        appendLine("<!-- $RECORD_TAG ${gson.toJson(recordMeta)} -->")
        appendLine()
        appendLine("> Pedidos de alteração enviados ao pull request #${record.number} (`${record.headRef}` → `${record.baseRef}`). A aba")
        appendLine("> Patrulha regrava este arquivo a cada verificação do código e das conversas no GitHub.")
        appendLine()
        appendLine("PR: ${record.url}")
        appendLine()
        appendLine(record.checkedAt?.let { "Verificado em ${formatDate(it)}." } ?: "Ainda não verificado.")
        appendLine()
        appendLine("| Pedido | Onde | Código | Conversa |")
        appendLine("| --- | --- | --- | --- |")
        record.requests.forEach { request ->
            val id = when {
                request.deleted -> "~~${request.id}~~ (${request.remote.label})"
                request.pending -> "${request.id} (rascunho)"
                else -> request.id
            }
            appendLine("| $id | `${request.path}`, ${request.location} | ${request.code.label} | ${request.thread.label} |")
        }
        record.requests.forEach { request ->
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## ${request.id} — `${request.path}`, ${request.location}")
            appendLine()
            appendLine("<!-- $REQUEST_TAG ${gson.toJson(meta(request))} -->")
            appendLine()
            appendLine("**Pedido:**")
            appendLine()
            appendLine(request.text.trim())
            if (request.snippet.isNotBlank()) {
                appendLine()
                appendCode(request.snippet, request.path)
            }
            if (request.suggestion.isNotBlank()) {
                appendLine()
                appendLine("**Código sugerido:**")
                appendLine()
                appendCode(request.suggestion, request.path)
            }
            appendLine()
            appendLine("- **Enviado:** ${formatDate(request.sentAt)}, no commit `${request.commit.take(7)}`${request.url?.let { " · [ver no GitHub]($it)" }.orEmpty()}")
            if (request.pending && !request.deleted) appendLine("- **No GitHub:** rascunho da revisão, só você vê até publicar")
            if (request.remote != RemoteStatus.SYNCED) {
                appendLine("- **No GitHub:** ${request.remote.label}${request.remoteUpdatedAt?.let { " em ${formatDate(it)}" }.orEmpty()}")
            }
            appendLine("- **Código:** ${request.code.label}${request.codeCommit?.let { ", no commit `${it.take(7)}`" }.orEmpty()}")
            appendLine("- **Conversa no PR:** ${request.thread.label}${request.lastReply?.let { ". Última resposta: $it" }.orEmpty()}")
        }
    }

    private fun StringBuilder.appendCode(code: String, path: String) {
        val fence = if ("```" in code) "````" else "```"
        appendLine("$fence${path.substringAfterLast('.', "")}")
        appendLine(code.trimEnd())
        appendLine(fence)
    }

    fun parse(markdown: String): PullRequestRecord? {
        val lines = markdown.lines()
        val recordMeta = lines.firstNotNullOfOrNull { line -> metaOf(line, RECORD_TAG) }
            ?.let { gson.fromJson(it, RecordMeta::class.java) } ?: return null
        val requests = lines.mapNotNull { line -> metaOf(line, REQUEST_TAG) }
            .map { json -> request(gson.fromJson(json, RequestMeta::class.java)) }
        return PullRequestRecord(
            recordMeta.number, recordMeta.title, recordMeta.url, recordMeta.head, recordMeta.base, requests, recordMeta.checkedAt,
            recordMeta.pendingReview, recordMeta.pendingReviewNode,
        )
    }

    private fun metaOf(line: String, tag: String): String? =
        META.matchEntire(line.trim())?.takeIf { it.groupValues[1] == tag }?.groupValues?.get(2)
            ?.takeIf { runCatching { JsonParser.parseString(it) }.isSuccess }

    private fun meta(request: ChangeRequest) = RequestMeta(
        request.id, request.path, request.target.name, request.startLine, request.endLine, request.snippet, request.text,
        request.commit, request.commentId, request.url, request.sentAt, request.code.name, request.codeCommit,
        request.thread.name, request.lastReply, request.suggestion.ifBlank { null },
        request.remote.name.takeIf { request.remote != RemoteStatus.SYNCED }, request.remoteUpdatedAt,
        request.pending.takeIf { it },
    )

    private fun request(meta: RequestMeta) = ChangeRequest(
        id = meta.id,
        path = meta.path,
        target = enumOr(meta.target, CommentTarget.LINES),
        startLine = meta.start,
        endLine = meta.end,
        snippet = meta.snippet.orEmpty(),
        text = meta.text.orEmpty(),
        commit = meta.commit.orEmpty(),
        commentId = meta.comment,
        url = meta.url,
        sentAt = meta.sentAt.orEmpty(),
        code = enumOr(meta.code, CodeStatus.NOT_CHECKED),
        codeCommit = meta.codeCommit,
        thread = enumOr(meta.thread, if (meta.comment == null) ThreadStatus.NONE else ThreadStatus.NOT_CHECKED),
        lastReply = meta.lastReply,
        suggestion = meta.suggestion.orEmpty(),
        remote = enumOr(meta.remote, RemoteStatus.SYNCED),
        remoteUpdatedAt = meta.remoteUpdatedAt,
        pending = meta.pending == true,
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    /** Datas locais do plugin ou, como as do GitHub, com fuso (`2026-11-03T20:00:00Z`), no fuso da máquina. */
    private fun formatDate(iso: String): String =
        runCatching { LocalDateTime.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
            .map { it.format(DATE) }
            .getOrDefault(iso)

    private fun String.oneLine() = lines().joinToString(" ") { it.trim() }.trim()
}
