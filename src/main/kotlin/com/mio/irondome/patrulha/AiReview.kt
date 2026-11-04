package com.mio.irondome.patrulha

import com.google.gson.JsonObject
import com.mio.irondome.interceptor.AgentEvents

/** Um comentário que a IA propôs para o PR: linhas 1-based do arquivo local, no checkout do PR. */
data class AiComment(val path: String, val startLine: Int, val endLine: Int, val comment: String, val suggestion: String)

/** A primeira revisão de um PR pela IA: as instruções que ela lê e a leitura do JSON que ela devolve. */
object AiReview {

    const val MAX_COMMENTS = 15
    /** Patches maiores são cortados: a IA lê o arquivo inteiro no checkout de qualquer jeito. */
    private const val PATCH_MAX_LINES = 400

    fun fileName(number: Int) = "pr-$number-revisao.md"

    fun instructions(pr: PullRequest, files: PullRequestFiles): String = buildString {
        appendLine("# Revisão do PR #${pr.number}: ${pr.title}")
        appendLine()
        appendLine("Você é um revisor de código experiente. O PR leva `${pr.headRef}` para `${pr.baseRef}`, e o código do PR está no")
        appendLine("checkout local (commit `${pr.headSha.take(7)}`). Leia os arquivos alterados abaixo no projeto, com o contexto que")
        appendLine("precisar, e aponte problemas reais: bugs, casos não tratados, segurança, desempenho, concorrência, testes")
        appendLine("faltando e legibilidade. Ignore gosto pessoal e o que um formatador resolveria.")
        appendLine()
        appendLine("Regras:")
        appendLine()
        appendLine("- **Não altere nenhum arquivo.**")
        appendLine("- No máximo $MAX_COMMENTS comentários, dos mais importantes para os menos.")
        appendLine("- Prefira linhas que o PR alterou (as `+` dos diffs abaixo).")
        appendLine("- `startLine` e `endLine` são 1-based e se referem ao arquivo como está agora no projeto.")
        appendLine("- `comment` em português, direto: o problema e por que importa.")
        appendLine("- `suggestion` é opcional: o código que substitui exatamente as linhas `startLine` a `endLine`, com a indentação")
        appendLine("  original. Deixe vazio quando a correção não couber nessas linhas.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json contendo uma lista, por exemplo:")
        appendLine()
        appendLine("```json")
        appendLine("""[{"path": "src/main/App.kt", "startLine": 12, "endLine": 14, "comment": "…", "suggestion": ""}]""")
        appendLine("```")
        appendLine()
        appendLine("Se não houver nada a apontar, responda `[]`.")
        appendLine()
        appendLine("## Arquivos alterados (${files.files.size})")
        files.files.forEach { file ->
            appendLine()
            appendLine("### `${file.path}` · ${file.statusLabel} · +${file.additions} −${file.deletions}")
            file.previousPath?.let { appendLine("Antes: `$it`") }
            val patch = file.patch ?: return@forEach
            val lines = patch.lines()
            appendLine()
            appendLine("```diff")
            lines.take(PATCH_MAX_LINES).forEach(::appendLine)
            if (lines.size > PATCH_MAX_LINES) appendLine("… (${lines.size - PATCH_MAX_LINES} linhas do diff omitidas: leia o arquivo)")
            appendLine("```")
        }
    }

    /** Os comentários válidos da resposta: com caminho, linhas e texto, até [MAX_COMMENTS]. */
    fun parse(answer: String): List<AiComment> {
        val json = AgentEvents.extractJson(answer) ?: return emptyList()
        val items = if (json.isJsonArray) json.asJsonArray else json.asJsonObject.getAsJsonArray("comments") ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val path = item.text("path")?.trim()?.removePrefix("./")?.replace('\\', '/') ?: return@mapNotNull null
            val start = item.int("startLine") ?: item.int("line") ?: return@mapNotNull null
            val end = (item.int("endLine") ?: start).coerceAtLeast(start)
            val comment = item.text("comment")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (start < 1) return@mapNotNull null
            AiComment(path, start, end, comment, item.text("suggestion").orEmpty().trimEnd())
        }.take(MAX_COMMENTS)
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
}
