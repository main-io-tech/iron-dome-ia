package com.mio.review

import com.intellij.util.messages.Topic

/** Linhas são 0-based; `filePath` é relativo à raiz do projeto, com `/`. */
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
    /** Resolvido que o usuário dispensou com OK: some do editor, mas continua no `.md`. */
    var dismissed: Boolean = false,
)

class ReviewState {
    var nextReviewId: Int = 1

    /** 0 quando não há revisão aberta: o próximo comentário abre `mio-review-<nextReviewId>.md`. */
    var activeReviewId: Int = 0
    var activeReviewCreatedAt: String = ""
    var nextCommentId: Int = 1
    var comments: MutableList<ReviewComment> = mutableListOf()
}

fun interface ReviewCommentsListener {
    fun commentsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(ReviewCommentsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
