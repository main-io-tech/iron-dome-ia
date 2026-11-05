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
    private const val EXAMPLE = "**Bug:** carrinho vazio derruba o checkout\\n\\n" +
        "**O que acontece:** `total` começa com o primeiro item da lista (`itens.first()`), mas a lista pode vir vazia " +
        "quando o cliente remove tudo do carrinho.\\n\\n" +
        "**Por que importa:** nesse caso, `first()` lança uma `NoSuchElementException` e a tela de checkout mostra um erro " +
        "em vez do total zero. Qualquer cliente que esvaziar o carrinho vai ver essa falha.\\n\\n" +
        "**Como corrigir:**\\n" +
        "1. Troque o `first()` por uma soma com `sumOf`, que devolve 0 para uma lista vazia (é o código sugerido abaixo).\\n" +
        "2. Adicione um teste com `itens` vazio, para a regressão não voltar.\\n\\n" +
        "> **Conceito:** `sumOf` percorre a lista somando o valor que você escolher de cada item; com a lista vazia, o resultado é 0."
    private const val EXAMPLE_SUGGESTION = "val total = itens.sumOf { it.preco }"

    fun fileName(number: Int) = "pr-$number-revisao.md"

    /**
     * [hints] são as preferências que o time ensinou ao descartar ou rebaixar sugestões; [dossier], o que se sabe dos
     * arquivos alterados (comentários antigos, correções no git e decisões registradas); [guidelines], a linha de
     * avaliação do revisor, de [ReviewerGuidelines]; [rules], os arquivos de regras do projeto, de [ReviewRules].
     */
    fun instructions(
        pr: PullRequest,
        files: PullRequestFiles,
        hints: List<String> = emptyList(),
        dossier: String = "",
        guidelines: String = "",
        rules: List<RuleFile> = emptyList(),
    ): String = buildString {
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
        appendLine("Siga exatamente esta estrutura, em Markdown do GitHub, separando cada parte por uma linha em branco:")
        appendLine()
        appendLine("1. **Primeira linha:** a gravidade em negrito e um título curto do problema, como")
        appendLine("   `**Bug:** carrinho vazio derruba o checkout`. As gravidades são **Bug:** (quebra ou dá resultado errado),")
        appendLine("   **Atenção:** (risco real em alguma situação) e **Sugestão:** (melhoria que vale fazer, mas não bloqueia).")
        appendLine("2. **O que acontece:** um parágrafo que começa com esse rótulo em negrito e descreve o problema nas linhas")
        appendLine("   apontadas, citando variáveis, métodos e classes entre crases (como `calcularFrete`).")
        appendLine("3. **Por que importa:** um parágrafo com esse rótulo e a consequência concreta, com um exemplo de entrada ou")
        appendLine("   cenário que causa o problema (\"se o pedido vier sem itens, `itens.first()` lança uma exceção e o checkout cai\").")
        appendLine("4. **Como corrigir:** o rótulo e, quando houver mais de um passo, uma lista numerada (`1.`, `2.`) com um passo")
        appendLine("   por item. Se houver código sugerido, diga o que ele muda e por quê. Trechos de mais de uma linha vão em")
        appendLine("   bloco de código cercado por três crases, com a linguagem (como `kotlin`) depois das crases de abertura.")
        appendLine("5. **Conceito** (opcional): uma citação `> **Conceito:** …` explicando, em uma frase simples, um termo que um")
        appendLine("   júnior pode não conhecer (condição de corrida, injeção de SQL, imutabilidade, complexidade O(n²)…).")
        appendLine()
        appendLine("Também:")
        appendLine()
        appendLine("- Nada de comentários telegráficos como \"Possível NPE\" ou \"Melhorar nome\", nem siglas sem explicação.")
        appendLine("- Tom respeitoso e encorajador: fale do código, não da pessoa. Evite \"errado\", \"ruim\" ou \"óbvio\"; prefira")
        appendLine("  \"aqui pode acontecer…\" e \"uma forma mais segura é…\".")
        appendLine("- Entre 4 e 10 frases por comentário. Sem títulos (`#`), tabelas ou HTML; negrito só nos rótulos e no que")
        appendLine("  for realmente importante.")
        appendLine()
        appendLine("Responda **só** com um bloco ```json contendo uma lista, por exemplo:")
        appendLine()
        appendLine("```json")
        appendLine("""[{"path": "src/main/App.kt", "startLine": 12, "endLine": 14, "comment": "$EXAMPLE", "suggestion": "$EXAMPLE_SUGGESTION"}]""")
        appendLine("```")
        appendLine()
        appendLine("Se não houver nada a apontar, responda `[]`.")
        appendLine()
        append(ReviewerGuidelines.section(guidelines))
        append(ReviewRules.section(rules))
        if (hints.isNotEmpty()) {
            appendLine("## Preferências do time")
            appendLine()
            appendLine("Aprendidas com o que o time aceitou e descartou das revisões anteriores. Siga-as:")
            appendLine()
            hints.forEach { appendLine("- $it") }
            appendLine()
        }
        if (dossier.isNotBlank()) {
            appendLine(dossier.trimEnd())
            appendLine()
        }
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
