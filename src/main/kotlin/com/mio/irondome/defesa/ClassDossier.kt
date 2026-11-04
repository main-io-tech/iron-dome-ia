package com.mio.irondome.defesa

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.mio.irondome.comando.ReviewMarkdown
import com.mio.irondome.patrulha.PatrulhaService
import com.mio.irondome.radar.CommentMarkdown
import com.mio.irondome.radar.ReviewCommentService
import java.nio.file.Files
import java.nio.file.Path

/** O que se sabe de um arquivo antes de pedir algo à IA sobre ele. */
data class DossierEntry(
    val path: String,
    val history: FileHistory?,
    /** Comentários antigos sobre ele, já resumidos, como "Radar · resolvido: falta tratar lista vazia". */
    val pastComments: List<String>,
    /** Decisões que o time registrou, como "é thread-safe porque só a EDT escreve". */
    val decisions: List<String>,
) {
    val isEmpty: Boolean get() = (history == null || history.commits == 0) && pastComments.isEmpty() && decisions.isEmpty()
}

/**
 * O dossiê por classe que acompanha os pedidos à IA: comentários anteriores (e o que foi feito com eles), correções
 * no histórico do git e decisões registradas em `iron-dome/defesa/decisoes.md`. Com ele, a IA diz "esse mesmo ponto
 * gerou bug em março" em vez de recomendar no genérico, e o contexto fica menor que o diff inteiro.
 */
object ClassDossier {

    const val DECISIONS_FILE = "decisoes.md"
    private const val MAX_FILES = 12
    private const val MAX_COMMENTS = 5
    private const val SUMMARY_MAX = 160
    private val SECTION = Regex("""^##\s+`?([^`]+?)`?\s*$""")

    /**
     * As decisões do `.md`, por caminho ou nome de classe: cada seção `## caminho/Arquivo.kt` (ou `## Arquivo`) com
     * uma decisão por item `- `.
     */
    fun decisions(markdown: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (line in markdown.lines()) {
            SECTION.matchEntire(line.trim())?.let {
                current = it.groupValues[1].trim()
                return@let
            }
            val key = current ?: continue
            if (line.trimStart().startsWith("- ")) result.getOrPut(key) { mutableListOf() } += line.trimStart().removePrefix("- ").trim()
        }
        return result.filterValues { it.isNotEmpty() }
    }

    /** As decisões que valem para [path]: pelo caminho inteiro, pelo nome do arquivo ou pelo nome sem extensão. */
    fun decisionsFor(path: String, all: Map<String, List<String>>): List<String> {
        val file = path.substringAfterLast('/')
        val name = file.substringBeforeLast('.')
        return all.filterKeys { key -> key == path || key == file || key == name || path.endsWith("/$key") }.values.flatten().distinct()
    }

    fun render(entries: List<DossierEntry>): String {
        val useful = entries.filterNot { it.isEmpty }
        if (useful.isEmpty()) return ""
        return buildString {
            appendLine("## Dossiê das classes")
            appendLine()
            appendLine("O que o projeto já sabe destes arquivos. Use para priorizar e para citar o histórico (\"esse ponto já gerou")
            appendLine("correção antes\"), mas confira sempre no código atual.")
            useful.forEach { entry ->
                appendLine()
                appendLine("### `${entry.path}`")
                appendLine()
                val history = entry.history
                if (history != null && history.commits > 0) {
                    val parts = listOfNotNull(
                        "mudou ${history.commits} vez(es) em ${ChangeHistory.MONTHS} meses",
                        "${history.fixes} correção(ões)".takeIf { history.fixes > 0 },
                        "só ${history.authors.first()} mexeu".takeIf { history.singleAuthor },
                    )
                    appendLine("- **Histórico:** ${parts.joinToString(", ")}.")
                    history.fixSubjects.forEach { appendLine("  - Correção: $it") }
                    history.strongCoChanges.take(3).forEach {
                        appendLine("- **Costuma mudar junto com** `${it.path}` (${it.percent}% dos commits).")
                    }
                }
                entry.pastComments.forEach { appendLine("- **Comentário anterior:** $it") }
                entry.decisions.forEach { appendLine("- **Decisão do time:** $it") }
            }
        }
    }

    /** Monta o dossiê dos arquivos [paths], lendo histórico, revisões antigas e decisões. Não chame na EDT. */
    fun collect(project: Project, paths: Collection<String>): String {
        if (!DefenseSettings.dossier) return ""
        val targets = paths.filter { it.isNotBlank() }.distinct().take(MAX_FILES)
        if (targets.isEmpty()) return ""
        return runCatching {
            val history = ChangeHistoryService.getInstance(project).currentOrLoad()
            val decisions = decisionsFile(project)?.takeIf(Files::isRegularFile)?.let { decisions(Files.readString(it)) }.orEmpty()
            val past = pastComments(project)
            render(targets.map { path ->
                DossierEntry(
                    path = path,
                    history = history.of(path).takeIf { it.commits > 0 },
                    pastComments = past[path].orEmpty().take(MAX_COMMENTS),
                    decisions = decisionsFor(path, decisions),
                )
            })
        }.getOrElse {
            LOG.warn("Não deu para montar o dossiê das classes", it)
            ""
        }
    }

    fun decisionsFile(project: Project): Path? =
        project.basePath?.let { Path.of(it, ReviewCommentService.REVIEW_DIR, ReviewFeedback.DIR, DECISIONS_FILE) }

    /** Os comentários de revisões encerradas do Radar e os pedidos já enviados a PRs, por arquivo. */
    private fun pastComments(project: Project): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        fun add(path: String, line: String) {
            result.getOrPut(path) { mutableListOf() } += line
        }
        val radar = project.service<ReviewCommentService>()
        val saved = radar.archivedReviews().flatMap { it.comments } +
            radar.historyReviews().flatMap { (_, file) -> runCatching { ReviewMarkdown.parse(Files.readString(file)) }.getOrDefault(emptyList()) }
        saved.forEach { comment ->
            val status = if (comment.resolved) "resolvido" else "não resolvido"
            add(comment.filePath, "Radar · $status: ${CommentMarkdown.plain(comment.text, SUMMARY_MAX)}")
        }
        // Sem o plugin do Git, a Patrulha não existe: o dossiê fica só com o Radar.
        try {
            PatrulhaService.getInstance(project).records().forEach { record ->
                record.requests.filterNot { it.deleted }.forEach { request ->
                    val status = if (request.likelyDone) "atendido" else "pedido"
                    add(request.path, "PR #${record.number} · $status: ${CommentMarkdown.plain(request.text, SUMMARY_MAX)}")
                }
            }
        } catch (_: LinkageError) {
        }
        return result
    }

    private val LOG = logger<ClassDossier>()
}
