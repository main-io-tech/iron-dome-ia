package com.mio.irondome.impacto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mio.irondome.interceptor.AgentEvents
import com.mio.irondome.radar.CommentTarget

enum class ImpactLevel(val label: String) {
    LOW("Baixo"),
    MEDIUM("Médio"),
    HIGH("Alto"),
}

/** Uma classe do diagrama que a IA apontou como afetada; [sources] são os comentários e alterações que a afetam. */
data class ClassImpact(val qualifiedName: String, val level: ImpactLevel, val reason: String, val sources: List<String>)

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
     * vazio (sem git ou arquivos novos).
     */
    fun instructions(
        graph: DependencyGraph,
        comments: Map<String, List<ImpactComment>>,
        changes: Map<String, FileChange>,
        diff: String,
    ): String = buildString {
        val names = graph.nodes.associate { it.qualifiedName to it.name }
        appendLine("# Raio de impacto de ${DependencyAnalyzer.simpleName(graph.focus)}")
        appendLine()
        appendLine("Você é um revisor de código experiente. Abaixo estão as classes ligadas a `${graph.focus}` por dependência, os")
        appendLine("comentários de revisão pendentes sobre elas e as alterações ainda não commitadas. Os comentários do Radar e do")
        appendLine("Escudo são pedidos de correção para a IA; os da Patrulha são pedidos de alteração a um pull request em revisão.")
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
        appendLine("- `sources`: os ids dos comentários (como `C1` ou `P2`) e, para alterações, `alteração em <Classe>`.")
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
        appendLine("## Comentários pendentes (${all.size})")
        all.forEach { comment ->
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
        if (all.isEmpty()) {
            appendLine()
            appendLine("Nenhum.")
        }
        appendLine()
        appendLine("## Alterações não commitadas (${changes.size})")
        appendLine()
        if (changes.isEmpty()) appendLine("Nenhuma.")
        changes.forEach { (name, change) ->
            appendLine("- `${names[name] ?: name}` (`${graph.nodes.first { it.qualifiedName == name }.filePath}`): ${change.label.lowercase()}")
        }
        val lines = diff.lines()
        if (diff.isNotBlank()) {
            appendLine()
            appendLine("```diff")
            lines.take(DIFF_MAX_LINES).forEach(::appendLine)
            if (lines.size > DIFF_MAX_LINES) appendLine("… (${lines.size - DIFF_MAX_LINES} linhas do diff omitidas: leia os arquivos)")
            appendLine("```")
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
