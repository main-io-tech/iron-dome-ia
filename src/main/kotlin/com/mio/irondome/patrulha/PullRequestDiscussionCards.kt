package com.mio.irondome.patrulha

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.mio.irondome.base.Tone
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.EditorCard
import com.mio.irondome.radar.EditorCardProvider
import com.mio.irondome.radar.ReviewComment

/**
 * As conversas do PR em revisão no GitHub, de outras pessoas e as suas fora dos pedidos do `.md`, como cartões só de
 * leitura nas linhas comentadas: sem Editar, só recolher e Ocultar. Responder fica na aba Patrulha.
 */
class PullRequestDiscussionCards : EditorCardProvider {

    override fun cards(project: Project, path: String, document: Document): List<EditorCard> {
        val patrulha = PatrulhaService.getInstance(project)
        val number = patrulha.reviewing().takeIf(patrulha::isDiscussionsShown) ?: return emptyList()
        return patrulha.editorDiscussions(number)
            .filter { it.path == path && !patrulha.isDiscussionHidden(number, it) }
            .map { card(patrulha, number, it) }
    }

    private fun card(patrulha: PatrulhaService, number: Int, discussion: Discussion): EditorCard {
        val line = discussion.line
        val lines = line?.let { (discussion.startLine ?: it).coerceAtMost(it) - 1..it - 1 }
        val first = discussion.first
        val comment = ReviewComment(
            id = "@${first.author}",
            filePath = discussion.path.orEmpty(),
            startLine = lines?.first ?: 0,
            endLine = lines?.last ?: 0,
            text = text(discussion),
            target = if (lines == null) CommentTarget.FILE else CommentTarget.LINES,
            pullRequest = number,
        ).apply { expanded = patrulha.isDiscussionExpanded(number, discussion) }
        val (badge, tone) = when {
            discussion.resolved -> "Resolvida" to Tone.GREEN
            discussion.outdated -> "Desatualizada" to Tone.GRAY
            else -> "Aberta" to Tone.BLUE
        }
        return EditorCard(
            comment = comment,
            lines = lines,
            badge = badge,
            tone = tone,
            subtitle = listOfNotNull(
                "Conversa no PR #$number",
                Discussions.formatDate(first.createdAt),
                discussion.replies.size.takeIf { it > 0 }?.let { if (it == 1) "1 resposta" else "$it respostas" },
                "só leitura: responda pela Patrulha",
            ).joinToString(" · "),
            onToggle = { patrulha.toggleDiscussionExpanded(number, discussion) },
            onHide = { patrulha.setDiscussionHidden(number, discussion, true) },
        )
    }

    /** O primeiro comentário e, abaixo, cada resposta com quem respondeu e quando. */
    private fun text(discussion: Discussion): String = buildString {
        append(discussion.first.body.trim().ifEmpty { "_Sem texto._" })
        discussion.replies.forEach { reply ->
            append("\n\n**@${reply.author} respondeu** · ${Discussions.formatDate(reply.createdAt)}")
            if (reply.pending) append(" · no seu rascunho")
            append("\n\n")
            append(reply.body.trim().ifEmpty { "_Sem texto._" })
        }
    }
}
