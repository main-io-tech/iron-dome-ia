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

    const val JUNIOR = "um desenvolvedor júnior, que ainda está aprendendo o projeto e a linguagem"

    /** O exemplo do JSON já no tom pedido, escapado para dentro da string JSON. */
    private const val EXAMPLE = "**Bug:** `total` começa com o primeiro item da lista (`itens.first()`), mas a lista pode vir vazia " +
        "quando o cliente remove tudo do carrinho.\\n\\nNesse caso, `first()` lança uma `NoSuchElementException` e a tela de " +
        "checkout mostra um erro em vez do total zero.\\n\\nPara corrigir, some os itens com `sumOf`, que devolve 0 para uma " +
        "lista vazia, como na sugestão abaixo."
    private const val EXAMPLE_SUGGESTION = "val total = itens.sumOf { it.preco }"

    fun fileName(number: Int) = "pr-$number-revisao.md"

    fun instructions(pr: PullRequest, files: PullRequestFiles): String = buildString {
        appendLine("# Revisão do PR #${pr.number}: ${pr.title}")
        appendLine()
        appendLine("Você é um revisor de código experiente, que também orienta desenvolvedores júnior. O PR leva `${pr.headRef}` para `${pr.baseRef}`, e o código do PR está no")
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
        appendLine("- `suggestion` é opcional: o código que substitui exatamente as linhas `startLine` a `endLine`, com a indentação")
        appendLine("  original. Deixe vazio quando a correção não couber nessas linhas.")
        appendLine()
        appendLine("## Como escrever o `comment`")
        appendLine()
        appendLine("Quem vai ler é $JUNIOR. Escreva em português do Brasil, com")
        appendLine("texto claro e bem explicativo, como um colega mais experiente que ensina com paciência:")
        appendLine()
        appendLine("- Comece pela gravidade, em negrito: **Bug:** (quebra ou dá resultado errado), **Atenção:** (risco real em")
        appendLine("  alguma situação) ou **Sugestão:** (melhoria que vale fazer, mas não bloqueia).")
        appendLine("- Depois, em parágrafos curtos:")
        appendLine("  1. **O que acontece:** descreva o problema nas linhas apontadas, citando variáveis, métodos e classes entre")
        appendLine("     crases (como `calcularFrete`).")
        appendLine("  2. **Por que importa:** a consequência concreta, com um exemplo de entrada ou cenário que causa o problema")
        appendLine("     (por exemplo: \"se o pedido vier sem itens, `itens.first()` lança uma exceção e o checkout cai\").")
        appendLine("  3. **Como corrigir:** o passo a passo da correção. Se houver código sugerido, diga o que ele muda e por quê.")
        appendLine("- Explique em uma frase simples todo conceito que um júnior pode não conhecer (como condição de corrida,")
        appendLine("  injeção de SQL, imutabilidade ou complexidade O(n²)) na primeira vez que ele aparecer.")
        appendLine("- Nada de comentários telegráficos como \"Possível NPE\" ou \"Melhorar nome\", nem siglas sem explicação.")
        appendLine("- Tom respeitoso e encorajador: fale do código, não da pessoa. Evite \"errado\", \"ruim\" ou \"óbvio\"; prefira")
        appendLine("  \"aqui pode acontecer…\" e \"uma forma mais segura é…\".")
        appendLine("- Entre 3 e 8 frases por comentário. Pode usar Markdown do GitHub (crases, listas, blocos de código), sem títulos.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json contendo uma lista, por exemplo:")
        appendLine()
        appendLine("```json")
        appendLine("""[{"path": "src/main/App.kt", "startLine": 12, "endLine": 14, "comment": "$EXAMPLE", "suggestion": "$EXAMPLE_SUGGESTION"}]""")
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
