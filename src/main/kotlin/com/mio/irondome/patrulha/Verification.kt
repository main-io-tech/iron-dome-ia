package com.mio.irondome.patrulha

import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment

/** O código do pedido no head atual do PR, comparado com o trecho de quando foi enviado. */
object CodeCheck {

    /**
     * [headContent] é o arquivo no head atual (nulo se foi removido); [sentContent], o arquivo no commit do
     * envio, só usado para pedidos de arquivo inteiro.
     */
    fun check(request: ChangeRequest, headSha: String, headContent: String?, sentContent: String?): CodeStatus = when {
        request.target == CommentTarget.PACKAGE -> CodeStatus.NOT_APPLICABLE
        headSha == request.commit -> CodeStatus.NO_NEW_COMMITS
        headContent == null -> CodeStatus.FILE_REMOVED
        request.target == CommentTarget.FILE ->
            if (sentContent == null) CodeStatus.NOT_APPLICABLE
            else if (normalize(sentContent) == normalize(headContent)) CodeStatus.UNCHANGED
            else if (containsSuggestion(request, headContent)) CodeStatus.AS_SUGGESTED
            else CodeStatus.CHANGED
        else -> lines(request, headContent)
    }

    private fun containsSuggestion(request: ChangeRequest, headContent: String): Boolean {
        if (request.suggestion.isBlank()) return false
        val suggestion = normalize(request.suggestion.trimEnd())
        val head = normalize(headContent)
        return (0..head.size - suggestion.size).any { index -> head.subList(index, index + suggestion.size) == suggestion }
    }

    private fun lines(request: ChangeRequest, headContent: String): CodeStatus {
        val snippet = normalize(request.snippet)
        if (snippet.isEmpty() || snippet.all { it.isBlank() }) return CodeStatus.NOT_APPLICABLE
        val head = normalize(headContent)
        val start = request.startLine - 1
        if (start >= 0 && start + snippet.size <= head.size && head.subList(start, start + snippet.size) == snippet) {
            return CodeStatus.UNCHANGED
        }
        if (containsSuggestion(request, headContent)) return CodeStatus.AS_SUGGESTED
        val moved = (0..head.size - snippet.size).any { index -> head.subList(index, index + snippet.size) == snippet }
        return if (moved) CodeStatus.MOVED else CodeStatus.CHANGED
    }

    /** Ignora diferenças de fim de linha e espaços no fim. */
    private fun normalize(text: String): List<String> = text.replace("\r\n", "\n").split('\n').map { it.trimEnd() }
}

/** O pedido como comentário do Radar do PR [number], para montar o texto do GitHub do mesmo jeito que no envio. */
fun ChangeRequest.toComment(number: Int) = ReviewComment(
    id = id,
    filePath = path,
    startLine = (startLine - 1).coerceAtLeast(0),
    endLine = (endLine - 1).coerceAtLeast(0),
    snippet = snippet,
    text = text,
    target = target,
    pullRequest = number,
    suggestion = suggestion,
)

/** O pedido comparado com o comentário dele no GitHub, que o revisor pode ter editado ou apagado lá. */
object RemoteSync {

    /**
     * [comments] são os comentários do PR no GitHub, pelo id. Sem o do pedido, ele foi apagado; com outro
     * texto, foi editado, e o `.md` passa a ter o texto do GitHub. Pedidos no texto geral da revisão não
     * têm comentário próprio e ficam como estão.
     */
    fun apply(request: ChangeRequest, comments: Map<Long, GitHubClient.PostedComment>): ChangeRequest {
        val id = request.commentId ?: return request
        if (request.deleted) return request
        val comment = comments[id] ?: return request.copy(remote = RemoteStatus.DELETED, remoteUpdatedAt = null)
        val (text, suggestion) = ReviewPlan.parseBody(comment.body)
        if (text == request.text.trim() && suggestion.trimEnd() == request.suggestion.trimEnd()) return request
        return request.copy(text = text, suggestion = suggestion.trimEnd(), remote = RemoteStatus.EDITED, remoteUpdatedAt = comment.updatedAt)
    }
}

/** Onde o trecho de um pedido está no código de agora, que pode ter mudado desde o envio. */
object SnippetLocator {

    /**
     * Linhas 0-based do trecho em [text], na ocorrência mais perto de [recorded] (as linhas do envio); sem
     * o trecho no texto, as linhas gravadas, limitadas ao tamanho do arquivo.
     */
    fun locate(text: String, snippet: String, recorded: IntRange): IntRange {
        val lines = normalize(text)
        val lastLine = (lines.size - 1).coerceAtLeast(0)
        val fallback = recorded.first.coerceIn(0, lastLine)..recorded.last.coerceIn(recorded.first.coerceIn(0, lastLine), lastLine)
        val wanted = normalize(snippet)
        if (snippet.isBlank() || wanted.size > lines.size) return fallback
        val found = (0..lines.size - wanted.size).filter { index -> lines.subList(index, index + wanted.size) == wanted }
        val start = found.minByOrNull { kotlin.math.abs(it - recorded.first) } ?: return fallback
        return start..start + wanted.size - 1
    }

    private fun normalize(text: String): List<String> = text.replace("\r\n", "\n").split('\n').map { it.trimEnd() }
}

/** A conversa do pedido no PR: resolvida, respondida por outra pessoa ou ainda aberta. */
object ThreadCheck {

    private const val REPLY_MAX = 160

    /** [me] é o login da conta que enviou o pedido: respostas dela não contam como resposta do autor. */
    fun check(request: ChangeRequest, threads: List<ReviewThread>, me: String): Pair<ThreadStatus, String?> {
        val commentId = request.commentId ?: return ThreadStatus.NONE to null
        val thread = threads.firstOrNull { it.firstCommentId == commentId } ?: return ThreadStatus.NONE to null
        val reply = thread.replies.lastOrNull { !it.author.equals(me, ignoreCase = true) }
        val summary = reply?.let { "@${it.author}: \"${oneLine(it.body)}\"" }
        return when {
            thread.resolved -> ThreadStatus.RESOLVED to summary
            reply != null -> ThreadStatus.REPLIED to summary
            else -> ThreadStatus.OPEN to null
        }
    }

    private fun oneLine(text: String): String {
        val line = text.lines().joinToString(" ") { it.trim() }.trim().replace("|", "/")
        return if (line.length > REPLY_MAX) line.take(REPLY_MAX - 1) + "…" else line
    }
}

/** Onde cada comentário entra na revisão do GitHub. */
enum class Placement {
    /** Comentário nas linhas do diff. */
    INLINE,
    /** Comentário no arquivo inteiro: arquivo do PR, mas linhas fora do diff ou alvo de arquivo. */
    FILE,
    /** No texto geral da revisão: pacotes e arquivos que o PR não altera. */
    BODY,
}

/** [startLine] e [line] são 1-based e só valem em [Placement.INLINE]. */
class PlannedRequest(val comment: ReviewComment, val placement: Placement, val body: String, val startLine: Int?, val line: Int?)

object ReviewPlan {

    fun marker(id: String) = "<!-- iron-dome:$id -->"

    private val MARKER = Regex("""<!--\s*iron-dome:[^>]*-->""")
    private val OUTSIDE_DIFF = Regex("""^\*\*Linhas? [^*\n]+\*\* \(fora do diff\):\n\n(`{3,4})[^\n]*\n.*?\n\1[ \t]*(\n|$)""", RegexOption.DOT_MATCHES_ALL)
    private val SUGGESTION_BLOCK = Regex("""(`{3,4})suggestion[^\n]*\n(.*?)\1[ \t]*$""", RegexOption.DOT_MATCHES_ALL)
    private val EXAMPLE_BLOCK = Regex("""\*\*Como sugiro que fique:\*\*\s*\n(`{3,4})[^\n]*\n(.*?)\1[ \t]*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * O texto e o código sugerido de volta, a partir do comentário como está no GitHub, que pode ter sido
     * editado lá. Desfaz o que [bodyFor] monta: a marca do id, o trecho citado fora do diff e o bloco
     * `suggestion` ou o código de exemplo. Um texto em outro formato vem inteiro, sem sugestão.
     */
    fun parseBody(body: String): Pair<String, String> {
        var text = MARKER.replace(body.replace("\r\n", "\n"), "").trim()
        OUTSIDE_DIFF.find(text)?.let { text = text.substring(it.range.last + 1).trim() }
        val block = SUGGESTION_BLOCK.find(text) ?: EXAMPLE_BLOCK.find(text) ?: return text to ""
        val code = block.groupValues[2].removeSuffix("\n")
        return text.removeRange(block.range).trim() to code
    }

    fun plan(comments: List<ReviewComment>, files: List<ChangedFile>): List<PlannedRequest> {
        val byPath = files.associateBy { it.path }
        return comments.map { comment ->
            val file = byPath[comment.filePath]
            val first = comment.startLine + 1
            val last = comment.endLine + 1
            when {
                comment.target == CommentTarget.LINES && file != null && (first..last).all { it in file.commentableLines } ->
                    PlannedRequest(comment, Placement.INLINE, bodyFor(comment, Placement.INLINE), first.takeIf { it < last }, last)
                comment.target != CommentTarget.PACKAGE && file != null ->
                    PlannedRequest(comment, Placement.FILE, bodyFor(comment, Placement.FILE), null, null)
                else -> PlannedRequest(comment, Placement.BODY, bodyFor(comment, Placement.BODY), null, null)
            }
        }
    }

    /** O texto de [comment] no GitHub em [placement], com a marca do id quando é um comentário próprio. */
    fun bodyFor(comment: ReviewComment, placement: Placement): String {
        val first = comment.startLine + 1
        val last = comment.endLine + 1
        return when {
            placement == Placement.INLINE -> withMarker(inline(comment), comment.id)
            placement == Placement.FILE && comment.target == CommentTarget.LINES -> withMarker(outsideDiff(comment, first, last), comment.id)
            placement == Placement.FILE -> withMarker(comment.text.trim() + example(comment), comment.id)
            else -> bodyItem(comment, first, last)
        }
    }

    /** Nas linhas do diff, a sugestão vira um bloco `suggestion`, que o autor aplica pelo GitHub. */
    private fun inline(comment: ReviewComment): String {
        if (comment.suggestion.isBlank()) return comment.text.trim()
        val fence = fenceFor(comment.suggestion)
        return "${comment.text.trim()}\n\n${fence}suggestion\n${comment.suggestion.trimEnd()}\n$fence"
    }

    /** Fora do diff, o GitHub não aplica sugestões: o código vai como exemplo. */
    private fun example(comment: ReviewComment): String {
        if (comment.suggestion.isBlank()) return ""
        val fence = fenceFor(comment.suggestion)
        return "\n\n**Como sugiro que fique:**\n\n$fence${language(comment)}\n${comment.suggestion.trimEnd()}\n$fence"
    }

    private fun language(comment: ReviewComment) =
        if (comment.target == CommentTarget.PACKAGE) "" else comment.filePath.substringAfterLast('/').substringAfterLast('.', "")

    private fun fenceFor(code: String) = if ("```" in code) "````" else "```"

    /** O texto geral da revisão, com os pedidos que não cabem em comentários de arquivo ou de linha. */
    fun reviewBody(general: String, planned: List<PlannedRequest>): String {
        val items = planned.filter { it.placement == Placement.BODY }
        return buildString {
            append(general.trim())
            if (items.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("### Pedidos fora dos arquivos do PR\n\n")
                items.forEach { append(it.body).append('\n') }
            }
        }.trim()
    }

    private fun outsideDiff(comment: ReviewComment, first: Int, last: Int): String {
        val lines = if (first == last) "Linha $first" else "Linhas $first–$last"
        val fence = fenceFor(comment.snippet)
        return "**$lines** (fora do diff):\n\n$fence${language(comment)}\n${comment.snippet.trimEnd()}\n$fence\n\n${comment.text.trim()}" +
            example(comment)
    }

    private fun bodyItem(comment: ReviewComment, first: Int, last: Int): String {
        val where = when (comment.target) {
            CommentTarget.PACKAGE -> "pacote inteiro"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.LINES -> if (first == last) "linha $first" else "linhas $first–$last"
        }
        val text = (comment.text.trim() + example(comment)).lines().joinToString("\n") { if (it.isEmpty()) it else "  $it" }.trimStart()
        return "- **${comment.id}** · `${comment.filePath}`, $where: $text"
    }

    private fun withMarker(text: String, id: String) = "$text\n\n${marker(id)}"
}
