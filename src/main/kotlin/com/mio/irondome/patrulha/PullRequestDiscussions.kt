package com.mio.irondome.patrulha

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Comentário de alguém no PR; [pending] é o que ainda está no rascunho da revisão de quem revisa. */
data class DiscussionComment(
    val id: Long,
    val author: String,
    val body: String,
    val createdAt: String,
    val url: String = "",
    val pending: Boolean = false,
)

enum class DiscussionKind {
    /** Conversa numa linha ou no arquivo inteiro, com respostas. */
    THREAD,
    /** Comentário geral na conversa do PR. */
    CONVERSATION,
    /** O texto geral de uma revisão publicada, com o veredito. */
    REVIEW,
}

/**
 * Uma conversa do PR no GitHub, de qualquer pessoa. Em [DiscussionKind.THREAD], [nodeId] é o id da conversa no
 * GraphQL, para responder nela, e [line] é nula no comentário do arquivo inteiro. Linhas 1-based; [startLine]
 * só existe nos comentários de várias linhas.
 */
data class Discussion(
    val kind: DiscussionKind,
    val comments: List<DiscussionComment>,
    val nodeId: String = "",
    val path: String? = null,
    val line: Int? = null,
    val startLine: Int? = null,
    val resolved: Boolean = false,
    val outdated: Boolean = false,
    /** `APPROVED`, `CHANGES_REQUESTED`, `COMMENTED` ou `DISMISSED`, nas revisões. */
    val reviewState: String? = null,
) {
    val first: DiscussionComment get() = comments.first()
    val replies: List<DiscussionComment> get() = comments.drop(1)
    val lastActivity: String get() = comments.maxOf { it.createdAt }
    val key: String get() = "${kind.name.lowercase()}:${first.id}"

    /** Na conversa da linha a resposta entra nela; nos comentários gerais e nas revisões, vira comentário novo no PR. */
    val repliesInPlace: Boolean get() = kind == DiscussionKind.THREAD
}

/**
 * Alguém que comentou no PR: [comments] conta os comentários com texto, em conversas, respostas e revisões;
 * [verdict] é o da última revisão dele com veredito, `APPROVED` ou `CHANGES_REQUESTED`.
 */
data class Participant(val login: String, val comments: Int, val verdict: String?)

object Discussions {

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private const val QUOTE_MAX = 600
    private val VERDICTS = setOf("APPROVED", "CHANGES_REQUESTED", "DISMISSED")

    /** Quem comentou ou deu veredito no PR, de quem mais comentou para quem menos. Rascunhos não contam. */
    fun participants(discussions: List<Discussion>): List<Participant> {
        val counts = discussions.flatMap { it.comments }
            .filter { !it.pending && it.body.isNotBlank() }
            .groupingBy { it.author }
            .eachCount()
        val verdicts = discussions
            .filter { it.kind == DiscussionKind.REVIEW && it.reviewState in VERDICTS }
            .groupBy { it.first.author }
            .mapValues { (_, reviews) -> reviews.maxBy { it.first.createdAt }.reviewState.takeUnless { it == "DISMISSED" } }
        return (counts.keys + verdicts.keys)
            .map { Participant(it, counts[it] ?: 0, verdicts[it]) }
            .sortedWith(compareByDescending<Participant> { it.comments }.thenBy { it.login.lowercase() })
    }

    /**
     * As conversas do `pullRequest` da consulta do [GitHubClient.discussions], as de atividade mais recente
     * primeiro. Conversas que só existem no rascunho de quem revisa e revisões sem texto nem veredito ficam de fora.
     */
    fun parse(pullRequest: JsonObject): List<Discussion> {
        val threads = pullRequest.nodes("reviewThreads").mapNotNull { thread ->
            val comments = thread.nodes("comments").map(::comment)
            if (comments.isEmpty() || comments.first().pending) return@mapNotNull null
            val current = thread.int("line") != null
            Discussion(
                kind = DiscussionKind.THREAD,
                comments = comments,
                nodeId = thread.text("id").orEmpty(),
                path = thread.text("path"),
                line = thread.int("line") ?: thread.int("originalLine"),
                startLine = if (current) thread.int("startLine") else thread.int("originalStartLine"),
                resolved = thread.bool("isResolved"),
                outdated = thread.bool("isOutdated"),
            )
        }
        val conversation = pullRequest.nodes("comments").map { Discussion(DiscussionKind.CONVERSATION, listOf(comment(it))) }
        val reviews = pullRequest.nodes("reviews").mapNotNull { review ->
            val state = review.text("state").orEmpty()
            val body = review.text("body").orEmpty()
            if (state == "PENDING" || (state == "COMMENTED" && body.isBlank())) return@mapNotNull null
            Discussion(
                DiscussionKind.REVIEW,
                listOf(comment(review).copy(createdAt = review.text("submittedAt") ?: review.text("createdAt").orEmpty())),
                reviewState = state,
            )
        }
        return (threads + conversation + reviews).sortedByDescending { it.lastActivity }
    }

    /** As conversas que não começaram num pedido do `.md`: as desses aparecem com o próprio pedido. */
    fun others(discussions: List<Discussion>, record: PullRequestRecord?): List<Discussion> {
        val tracked = record?.requests.orEmpty().mapNotNull { it.commentId }.toSet()
        return discussions.filterNot { it.kind == DiscussionKind.THREAD && it.first.id in tracked }
    }

    /** A conversa do pedido [request] no PR, se ele tem comentário no GitHub. */
    fun of(request: ChangeRequest, discussions: List<Discussion>): Discussion? {
        val id = request.commentId ?: return null
        return discussions.firstOrNull { it.kind == DiscussionKind.THREAD && it.first.id == id }
    }

    /** O comentário novo que responde a [to] fora de uma conversa de linha: cita o original e menciona o autor. */
    fun quote(to: DiscussionComment, reply: String): String {
        val original = to.body.trim().let { if (it.length > QUOTE_MAX) it.take(QUOTE_MAX - 1) + "…" else it }
        val quoted = original.lines().joinToString("\n") { "> $it".trimEnd() }
        return "> **@${to.author}** escreveu:\n$quoted\n\n${reply.trim()}"
    }

    fun isMine(comment: DiscussionComment, me: String?): Boolean = me != null && comment.author.equals(me, ignoreCase = true)

    /** `03/11/2026 20:00`, no fuso da máquina. */
    fun formatDate(iso: String): String =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DATE) }.getOrDefault(iso)

    fun comment(json: JsonObject) = DiscussionComment(
        id = json.get("databaseId")?.takeUnless { it is JsonNull }?.asLong ?: 0,
        author = (json.get("author") as? JsonObject)?.text("login") ?: "fantasma",
        body = json.text("body").orEmpty(),
        createdAt = json.text("createdAt").orEmpty(),
        url = json.text("url").orEmpty(),
        pending = json.text("state") == "PENDING",
    )

    private fun JsonObject.nodes(key: String): List<JsonObject> =
        getAsJsonObject(key)?.getAsJsonArray("nodes")?.filter(JsonElement::isJsonObject)?.map { it.asJsonObject }.orEmpty()

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.asString

    private fun JsonObject.int(key: String): Int? = get(key)?.takeUnless { it is JsonNull }?.asInt

    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeUnless { it is JsonNull }?.asBoolean == true
}
