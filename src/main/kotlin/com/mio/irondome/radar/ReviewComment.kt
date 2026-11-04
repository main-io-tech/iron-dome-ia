package com.mio.irondome.radar

import com.intellij.util.messages.Topic

/** O que o comentário aponta: um trecho, um arquivo inteiro ou um pacote (pasta) inteiro. */
enum class CommentTarget { LINES, FILE, PACKAGE }

/** Quem marcou o alvo: o usuário no Radar ou outro mecanismo, como o Escudo nos testes fora da convenção. */
enum class CommentOrigin(val label: String) {
    RADAR("Radar"),
    ESCUDO("Escudo"),
    ;

    companion object {
        fun ofLabel(label: String): CommentOrigin? = entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
    }
}

/**
 * Linhas são 0-based; `filePath` é relativo à raiz do projeto, com `/`. Em [CommentTarget.PACKAGE],
 * `filePath` é a pasta; fora de [CommentTarget.LINES], linhas e trecho ficam vazios.
 */
class ReviewComment(
    var id: String = "",
    var reviewId: Int = 0,
    var filePath: String = "",
    var startLine: Int = 0,
    var endLine: Int = 0,
    var snippet: String = "",
    var text: String = "",
    var resolved: Boolean = false,
    /** A IA deve discutir o apontamento com o usuário antes de alterar qualquer arquivo. */
    var discuss: Boolean = false,
    /** Só vale para resolvidos, que aparecem recolhidos até o usuário expandir. */
    var expanded: Boolean = false,
    /** Resolvido oculto do editor pelo usuário: continua no `.md` e no estado, e pode voltar a aparecer. */
    var dismissed: Boolean = false,
    /** Como o comentário está no `.md`; nulo enquanto ele nunca foi salvo (rascunho). */
    var exported: ExportedVersion? = null,
    /** Continua no `.md` até o próximo salvamento do arquivo dele, quando sai. */
    var removing: Boolean = false,
    var target: CommentTarget = CommentTarget.LINES,
    /**
     * Número do pull request a que o comentário vai, pela Patrulha; 0 para os que vão para a IA. Os de PR
     * não entram em revisão do Radar ([reviewId] 0) nem no `.md` da IA.
     */
    var pullRequest: Int = 0,
    /**
     * Código de exemplo de como deveria ficar, só em comentários de PR. Em [CommentTarget.LINES], substitui
     * as linhas comentadas, como a sugestão do GitHub.
     */
    var suggestion: String = "",
    var origin: CommentOrigin = CommentOrigin.RADAR,
    /** Rascunho proposto pela IA na Patrulha: o que o time faz com ele ensina o pedido seguinte. */
    var fromAi: Boolean = false,
    /** O texto como a IA escreveu, para medir o que o time mudou antes de enviar. */
    var aiText: String = "",
) {
    /** O comentário como foi gravado no `.md`, para regravá-lo igual quando outro arquivo é salvo. */
    fun asExported(): ReviewComment? = exported?.let {
        ReviewComment(id, reviewId, filePath, it.startLine, it.endLine, it.snippet, it.text, resolved, it.discuss, target = target, origin = origin)
    }

    companion object {
        /** Por caminho; num mesmo arquivo, o de arquivo inteiro vem antes dos trechos. */
        val ORDER: Comparator<ReviewComment> =
            compareBy({ it.filePath }, { it.target == CommentTarget.LINES }, { it.startLine })
    }
}

class ExportedVersion(
    var startLine: Int = 0,
    var endLine: Int = 0,
    var snippet: String = "",
    var text: String = "",
    var discuss: Boolean = false,
) {
    fun matches(comment: ReviewComment) =
        startLine == comment.startLine && endLine == comment.endLine && snippet == comment.snippet &&
            text == comment.text && discuss == comment.discuss

    companion object {
        fun of(comment: ReviewComment) =
            ExportedVersion(comment.startLine, comment.endLine, comment.snippet, comment.text, comment.discuss)
    }
}

/** Comentário lido de um `.md` de revisão. Linhas 0-based, como em [ReviewComment]. */
class SavedComment(
    val id: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val resolved: Boolean,
    val discuss: Boolean,
    val text: String,
    val target: CommentTarget = CommentTarget.LINES,
    val origin: CommentOrigin = CommentOrigin.RADAR,
)

class ReviewState {
    var nextReviewId: Int = 1

    /** 0 quando não há revisão aberta: o próximo comentário abre `ordem-interceptacao-<nextReviewId>.md`. */
    var activeReviewId: Int = 0
    var activeReviewCreatedAt: String = ""
    var nextCommentId: Int = 1
    var comments: MutableList<ReviewComment> = mutableListOf()

    /** PR em revisão pela Patrulha: os comentários novos vão para ele, não para a IA. 0 quando não há. */
    var activePullRequest: Int = 0
    var nextPullRequestCommentId: Int = 1

    /** O estado dos outros projetos da janela, guardado ao trocar o projeto do Iron Dome, pela pasta de cada um. */
    var parked: MutableList<ParkedReview> = mutableListOf()
}

/** O estado do Radar de um projeto que não é o selecionado agora; [root] é a pasta dele. */
class ParkedReview(var root: String = "", var state: ReviewState = ReviewState())

fun interface ReviewCommentsListener {
    fun commentsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(ReviewCommentsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
