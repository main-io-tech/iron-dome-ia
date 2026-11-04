package com.mio.irondome.impacto

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.mio.irondome.patrulha.PatrulhaService
import com.mio.irondome.radar.CommentOrigin
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService

enum class CommentSource(val label: String) {
    RADAR("Radar"),
    ESCUDO("Escudo"),
    PATRULHA("Patrulha"),
}

/**
 * Comentário pendente sobre o código, do Radar (alvo para a IA), do Escudo ou da Patrulha (pedido ao PR em
 * revisão). Linhas 1-based; 0 em [CommentTarget.FILE] e [CommentTarget.PACKAGE]. [sent]: já está no GitHub.
 */
data class ImpactComment(
    val id: String,
    val source: CommentSource,
    val path: String,
    val target: CommentTarget,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val suggestion: String = "",
    val sent: Boolean = false,
) {
    val location: String
        get() = when (target) {
            CommentTarget.LINES -> if (startLine == endLine) "linha $startLine" else "linhas $startLine–$endLine"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.PACKAGE -> "pacote inteiro"
        }

    /** O comentário vale para o arquivo de [filePath]: no próprio arquivo ou no pacote que o contém. */
    fun covers(filePath: String): Boolean =
        filePath.isNotEmpty() && if (target == CommentTarget.PACKAGE) filePath.startsWith("${path.trimEnd('/')}/") else filePath == path
}

object ImpactComments {

    /**
     * Os comentários pendentes do projeto: os alvos abertos do Radar e do Escudo e, com um PR em revisão na
     * Patrulha, os pedidos a ele ainda não enviados e os enviados sem sinal de atendidos. Só na EDT.
     */
    fun collect(project: Project): List<ImpactComment> {
        val radar = project.service<ReviewCommentService>()
        val targets = radar.activeComments().filter { !it.resolved && !it.removing }.map {
            ImpactComment(
                id = it.id,
                source = if (it.origin == CommentOrigin.ESCUDO) CommentSource.ESCUDO else CommentSource.RADAR,
                path = it.filePath,
                target = it.target,
                startLine = if (it.target == CommentTarget.LINES) it.startLine + 1 else 0,
                endLine = if (it.target == CommentTarget.LINES) it.endLine + 1 else 0,
                text = it.text,
            )
        }
        val number = radar.activePullRequest
        if (number == 0) return targets
        val unsent = radar.pullRequestComments(number).map {
            ImpactComment(
                id = it.id,
                source = CommentSource.PATRULHA,
                path = it.filePath,
                target = it.target,
                startLine = if (it.target == CommentTarget.LINES) it.startLine + 1 else 0,
                endLine = if (it.target == CommentTarget.LINES) it.endLine + 1 else 0,
                text = it.text,
                suggestion = it.suggestion,
            )
        }
        val sent = PatrulhaService.getInstance(project).record(number)?.requests.orEmpty()
            .filter { !it.deleted && !it.likelyDone }
            .map { ImpactComment(it.id, CommentSource.PATRULHA, it.path, it.target, it.startLine, it.endLine, it.text, it.suggestion, sent = true) }
        return targets + unsent + sent
    }

    /** Os comentários de cada classe de [nodes], pelo arquivo dela; classes sem comentário ficam de fora. */
    fun byClass(nodes: List<ClassNode>, comments: List<ImpactComment>): Map<String, List<ImpactComment>> =
        nodes.associate { node -> node.qualifiedName to comments.filter { it.covers(node.filePath) } }
            .filterValues { it.isNotEmpty() }
}
