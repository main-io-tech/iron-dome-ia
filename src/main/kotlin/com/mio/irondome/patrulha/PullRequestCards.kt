package com.mio.irondome.patrulha

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.mio.irondome.base.Tone
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.EditorCard
import com.mio.irondome.radar.EditorCardProvider

/**
 * O histórico do PR no editor: os pedidos já enviados, de `pr-<número>.md`, como cartões recolhidos nas
 * linhas onde o trecho está agora, com o status das verificações, Editar e Ocultar.
 */
class PullRequestCards : EditorCardProvider {

    override fun cards(project: Project, path: String, document: Document): List<EditorCard> {
        val patrulha = PatrulhaService.getInstance(project)
        return patrulha.historyRecords().flatMap { record ->
            record.requests
                .filter { it.path == path && it.target != CommentTarget.PACKAGE && !patrulha.isRequestHidden(record.number, it.id) }
                .map { request -> card(project, patrulha, record.number, request, document) }
        }
    }

    private fun card(project: Project, patrulha: PatrulhaService, number: Int, request: ChangeRequest, document: Document): EditorCard {
        val lines = if (request.target == CommentTarget.LINES) {
            SnippetLocator.locate(document.text, request.snippet, request.startLine - 1..request.endLine - 1)
        } else {
            null
        }
        val comment = request.toComment(number).apply {
            lines?.let {
                startLine = it.first
                endLine = it.last
            }
            expanded = patrulha.isRequestExpanded(number, request.id)
        }
        val (badge, tone) = when {
            request.deleted -> RemoteStatus.DELETED.label to Tone.GRAY
            request.likelyDone -> "Atendido" to Tone.GREEN
            request.likelyPending -> "Sem alteração" to Tone.YELLOW
            else -> "Enviado" to Tone.PURPLE
        }
        return EditorCard(
            comment = comment,
            lines = lines,
            badge = badge,
            tone = tone,
            subtitle = when (request.remote) {
                RemoteStatus.DELETED -> "Enviado ao PR #$number e apagado no GitHub pelo revisor"
                RemoteStatus.EDITED -> "Enviado ao PR #$number · editado no GitHub · Código: ${request.code.label} · Conversa: ${request.thread.label}"
                RemoteStatus.SYNCED -> "Enviado ao PR #$number · Código: ${request.code.label} · Conversa: ${request.thread.label}"
            },
            onToggle = { patrulha.toggleRequestExpanded(number, request.id) },
            onHide = { patrulha.setRequestsHidden(number, listOf(request.id), true) },
            onEdit = if (request.deleted) null else { { SentRequestEditor.edit(project, number, request) } },
        )
    }
}
