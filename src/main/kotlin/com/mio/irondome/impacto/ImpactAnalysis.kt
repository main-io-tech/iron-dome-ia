package com.mio.irondome.impacto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mio.irondome.interceptor.AgentEvents
import com.mio.irondome.patrulha.ChangedFile
import com.mio.irondome.radar.CommentTarget

enum class ImpactLevel(val label: String) {
    LOW("Baixo"),
    MEDIUM("Médio"),
    HIGH("Alto"),
}

/** Uma classe do diagrama que a IA apontou como afetada; [sources] são os comentários e alterações que a afetam. */
data class ClassImpact(val qualifiedName: String, val level: ImpactLevel, val reason: String, val sources: List<String>)

/** O PR em revisão na Patrulha: o código local é o dele, e [files] são os arquivos que ele altera, com o diff do GitHub. */
data class ReviewedPullRequest(val number: Int, val files: List<ChangedFile>)

/** O que a IA concluiu para a classe [focus]: o resumo e as classes afetadas, da mais para a menos afetada. */
data class ImpactReport(val focus: String, val summary: String, val impacts: List<ClassImpact>) {
    fun of(qualifiedName: String): ClassImpact? = impacts.firstOrNull { it.qualifiedName == qualifiedName }
}

/** O pedido de impacto à IA: as instruções que ela lê e a leitura do JSON que ela devolve. */
object ImpactAnalysis {

    private const val DIFF_MAX_LINES = 600

    fun fileName(focus: String) = "impacto-${DependencyAnalyzer.simpleName(focus).lowercase()}.md"

    /**
     * [comments] e [changes] por classe do diagrama; [diff] é o `git diff` das classes alteradas, que pode vir
     * vazio (sem git ou arquivos novos). [outside] são os comentários pendentes em classes fora do diagrama, e
     * [pullRequest], o PR em revisão; [dossier], o histórico e as decisões das classes alteradas ou comentadas.
     */
    fun instructions(
        graph: DependencyGraph,
        comments: Map<String, List<ImpactComment>>,
        changes: Map<String, FileChange>,
        diff: String,
        outside: List<ImpactComment> = emptyList(),
        pullRequest: ReviewedPullRequest? = null,
        dossier: String = "",
    ): String = buildString {
        val names = graph.nodes.associate { it.qualifiedName to it.name }
        val inPullRequest = pullRequest?.files.orEmpty().associateBy { it.path }
        appendLine("# Raio de impacto de ${DependencyAnalyzer.simpleName(graph.focus)}")
        appendLine()
        appendLine("Você é um revisor de código experiente. Abaixo estão as classes ligadas a `${graph.focus}` por dependência, os")
        appendLine("comentários de revisão pendentes sobre elas e as alterações ainda não commitadas. Os comentários do Radar e do")
        appendLine("Escudo são pedidos de correção para a IA; os da Patrulha são pedidos de alteração a um pull request em revisão.")
        if (pullRequest != null) {
            appendLine()
            appendLine("O pull request #${pullRequest.number} está em revisão: o código local é o dele. Avalie o que muda nas classes do")
            appendLine("diagrama se os pedidos da Patrulha forem atendidos pelo autor, e o que as alterações do próprio PR já afetam.")
        }
        appendLine()
        appendLine("Para cada comentário e cada alteração, descubra quais classes do diagrama são afetadas se o comentário for")
        appendLine("atendido ou se a alteração for mantida: quem chama o código que muda, contratos e assinaturas que mudam,")
        appendLine("comportamento que muda para quem usa, testes que precisam mudar. Leia o código das classes para decidir.")
        appendLine()
        appendLine("Regras:")
        appendLine()
        appendLine("- **Não altere nenhum arquivo.** Esta tarefa é só de análise.")
        appendLine("- Use só classes da tabela abaixo, pelo nome qualificado exato.")
        appendLine("- `level`: `HIGH` quando quebra a compilação ou muda o comportamento; `MEDIUM` quando pede ajuste ou revisão;")
        appendLine("  `LOW` quando só vale conferir.")
        appendLine("- `reason` em português, direto: o que muda na classe e por quê.")
        appendLine("- `sources`: os ids dos comentários (como `C1` ou `P2`) e, para alterações, `alteração em <Classe>`")
        appendLine("  (ou `PR #<número>` para as alterações do pull request).")
        appendLine("- Comentários em classes fora do diagrama também contam: aponte as classes do diagrama que eles afetam.")
        appendLine("- Inclua a classe do comentário ou da alteração quando ela mesma for afetada. Deixe de fora as não afetadas.")
        appendLine("- `summary`: duas ou três frases com o impacto geral e o que conferir primeiro.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json, por exemplo:")
        appendLine()
        appendLine("```json")
        appendLine("""{"summary": "…", "impacts": [{"class": "com.app.Frete", "level": "HIGH", "reason": "…", "sources": ["C1"]}]}""")
        appendLine("```")
        appendLine()
        appendLine("## Classes do diagrama (${graph.nodes.size})")
        appendLine()
        appendLine("| Classe | Arquivo | Dependem dela | Ela depende de | Risco |")
        appendLine("| --- | --- | --- | --- | --- |")
        graph.nodes.forEach { node ->
            val marks = listOfNotNull(
                "analisada".takeIf { node.qualifiedName == graph.focus },
                comments[node.qualifiedName]?.let { "${it.size} comentário(s)" },
                changes[node.qualifiedName]?.let { it.label.lowercase() },
                pullRequest?.let { pr -> "alterada no PR #${pr.number}".takeIf { node.filePath in inPullRequest } },
            )
            val name = "`${node.qualifiedName}`" + if (marks.isEmpty()) "" else " (${marks.joinToString(", ")})"
            appendLine("| $name | `${node.filePath}` | ${node.afferent} | ${node.efferent} | ${node.risk.label} |")
        }
        if (graph.edges.isNotEmpty()) {
            appendLine()
            appendLine("## Ligações")
            appendLine()
            graph.edges.forEach { edge ->
                val verb = if (edge.kind == EdgeKind.INHERITANCE) "herda ou implementa" else "usa"
                appendLine("- ${names[edge.from] ?: edge.from} $verb ${names[edge.to] ?: edge.to}")
            }
        }
        val all = comments.values.flatten().distinct()
        appendLine()
        appendLine("## Comentários pendentes nas classes do diagrama (${all.size})")
        all.forEach { appendComment(it) }
        if (all.isEmpty()) {
            appendLine()
            appendLine("Nenhum.")
        }
        val others = outside.filterNot { it in all }
        if (others.isNotEmpty()) {
            appendLine()
            appendLine("## Comentários pendentes em classes fora do diagrama (${others.size})")
            others.forEach { appendComment(it) }
        }
        appendLine()
        appendLine("## Alterações não commitadas (${changes.size})")
        appendLine()
        if (changes.isEmpty()) appendLine("Nenhuma.")
        changes.forEach { (name, change) ->
            appendLine("- `${names[name] ?: name}` (`${graph.nodes.first { it.qualifiedName == name }.filePath}`): ${change.label.lowercase()}")
        }
        var budget = DIFF_MAX_LINES
        val lines = diff.lines()
        if (diff.isNotBlank()) {
            appendLine()
            appendLine("```diff")
            lines.take(budget).forEach(::appendLine)
            if (lines.size > budget) appendLine("… (${lines.size - budget} linhas do diff omitidas: leia os arquivos)")
            appendLine("```")
            budget -= lines.size.coerceAtMost(budget)
        }
        if (pullRequest != null) appendPullRequest(pullRequest, graph, budget)
        if (dossier.isNotBlank()) {
            appendLine()
            appendLine(dossier.trimEnd())
        }
    }

    private fun StringBuilder.appendComment(comment: ImpactComment) {
        appendLine()
        val sent = if (comment.sent) ", já enviado ao PR" else ""
        appendLine("### ${comment.id} · ${comment.source.label}$sent · `${comment.path}`, ${comment.location}")
        appendLine()
        appendLine(comment.text.trim())
        if (comment.suggestion.isNotBlank()) {
            appendLine()
            appendLine("Código sugerido${if (comment.target == CommentTarget.LINES) " para essas linhas" else ""}:")
            appendLine()
            appendLine("```")
            appendLine(comment.suggestion.trimEnd())
            appendLine("```")
        }
    }

    /** Os arquivos do PR, com o diff primeiro das classes do diagrama, até [budget] linhas. */
    private fun StringBuilder.appendPullRequest(pullRequest: ReviewedPullRequest, graph: DependencyGraph, budget: Int) {
        val inDiagram = graph.nodes.map { it.filePath }.toSet()
        appendLine()
        appendLine("## Alterações do pull request #${pullRequest.number} (${pullRequest.files.size} arquivo(s))")
        appendLine()
        if (pullRequest.files.isEmpty()) appendLine("Nenhuma.")
        pullRequest.files.forEach { file ->
            val mark = if (file.path in inDiagram) ", classe do diagrama" else ""
            appendLine("- `${file.path}`: ${file.statusLabel.lowercase()}, +${file.additions} −${file.deletions}$mark")
        }
        var left = budget
        val withPatch = pullRequest.files.filter { !it.patch.isNullOrBlank() }.sortedByDescending { it.path in inDiagram }
        withPatch.forEach { file ->
            val lines = file.patch.orEmpty().lines()
            if (left <= 0) return@forEach
            appendLine()
            appendLine("```diff")
            appendLine("--- ${file.path}")
            lines.take(left).forEach(::appendLine)
            if (lines.size > left) appendLine("… (diff cortado: leia o arquivo)")
            appendLine("```")
            left -= lines.size.coerceAtMost(left)
        }
        if (left <= 0 && withPatch.isNotEmpty()) {
            appendLine()
            appendLine("Os demais diffs do PR foram omitidos: leia os arquivos no checkout.")
        }
    }

    /** O relatório da resposta, só com classes de [graph]; nulo se a resposta não tiver o JSON pedido. */
    fun parse(answer: String, graph: DependencyGraph): ImpactReport? {
        val json = AgentEvents.extractJson(answer) ?: return null
        val root = json as? JsonObject
        val items = (root?.get("impacts") as? JsonArray) ?: (json as? JsonArray) ?: return null
        val byName = graph.nodes.associateBy { it.qualifiedName }
        val bySimple = graph.nodes.groupBy { it.name }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val impacts = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = (item.text("class") ?: item.text("qualifiedName"))?.trim()?.removeSurrounding("`") ?: return@mapNotNull null
            val node = byName[name] ?: bySimple[name] ?: bySimple[name.substringAfterLast('.')] ?: return@mapNotNull null
            val level = ImpactLevel.entries.firstOrNull { it.name.equals(item.text("level")?.trim(), ignoreCase = true) } ?: ImpactLevel.MEDIUM
            val sources = (item.get("sources") as? JsonArray)?.mapNotNull { it.takeIf { s -> s.isJsonPrimitive }?.asString?.trim() }.orEmpty()
            ClassImpact(node.qualifiedName, level, item.text("reason")?.trim().orEmpty(), sources.filter { it.isNotEmpty() })
        }
            .groupBy { it.qualifiedName }.values.map { same -> same.maxBy { it.level } }
            .sortedWith(compareByDescending<ClassImpact> { it.level }.thenBy { byName.getValue(it.qualifiedName).name })
        return ImpactReport(graph.focus, root?.text("summary")?.trim().orEmpty(), impacts)
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
