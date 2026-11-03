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

    fun plan(comments: List<ReviewComment>, files: List<ChangedFile>): List<PlannedRequest> {
        val byPath = files.associateBy { it.path }
        return comments.map { comment ->
            val file = byPath[comment.filePath]
            val first = comment.startLine + 1
            val last = comment.endLine + 1
            when {
                comment.target == CommentTarget.LINES && file != null && (first..last).all { it in file.commentableLines } ->
                    PlannedRequest(comment, Placement.INLINE, withMarker(inline(comment), comment.id), first.takeIf { it < last }, last)
                comment.target == CommentTarget.LINES && file != null ->
                    PlannedRequest(comment, Placement.FILE, withMarker(outsideDiff(comment, first, last), comment.id), null, null)
                comment.target == CommentTarget.FILE && file != null ->
                    PlannedRequest(comment, Placement.FILE, withMarker(comment.text.trim() + example(comment), comment.id), null, null)
                else -> PlannedRequest(comment, Placement.BODY, bodyItem(comment, first, last), null, null)
            }
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
