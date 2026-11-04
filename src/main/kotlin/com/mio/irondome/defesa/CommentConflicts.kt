package com.mio.irondome.defesa

import com.mio.irondome.impacto.ImpactComment
import com.mio.irondome.radar.CommentTarget

/** Dois comentários pendentes sobre o mesmo trecho: podem pedir coisas opostas. */
data class CommentOverlap(val first: ImpactComment, val second: ImpactComment) {
    val path: String get() = first.path

    /** As linhas em comum, 1-based. */
    val lines: IntRange
        get() = maxOf(first.startLine, second.startLine)..minOf(first.endLine, second.endLine)

    val location: String
        get() = if (lines.first == lines.last) "linha ${lines.first}" else "linhas ${lines.first}–${lines.last}"

    /** De mecanismos diferentes (Radar × Patrulha, humano × IA): mais chance de divergirem. */
    val crossSource: Boolean get() = first.source != second.source

    val key: String get() = listOf(first.id, second.id).sorted().joinToString("+")
}

/**
 * Quando dois comentários apontam para o mesmo trecho e podem dizer coisas opostas, o plugin detecta a
 * sobreposição por intervalo e pede à IA uma síntese. A detecção é só interseção de intervalos.
 */
object CommentConflicts {

    /** Pares de comentários de linhas com interseção no mesmo arquivo, sem repetir pares. */
    fun overlaps(comments: List<ImpactComment>): List<CommentOverlap> {
        val byPath = comments.filter { it.target == CommentTarget.LINES && it.startLine > 0 }.groupBy { it.path }
        return byPath.values.flatMap { same ->
            val sorted = same.sortedWith(compareBy({ it.startLine }, { it.endLine }, { it.id }))
            val pairs = mutableListOf<CommentOverlap>()
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    if (sorted[j].startLine > sorted[i].endLine) break
                    if (sorted[i].id != sorted[j].id) pairs += CommentOverlap(sorted[i], sorted[j])
                }
            }
            pairs
        }
    }

    /** O pedido de síntese à IA: ler os dois, dizer se divergem e propor o que atende aos dois. */
    fun synthesisInstructions(overlaps: List<CommentOverlap>): String = buildString {
        appendLine("# Comentários que se sobrepõem")
        appendLine()
        appendLine("Dois ou mais comentários de revisão apontam para o mesmo trecho de código. Para cada par, leia o trecho no")
        appendLine("projeto e os dois comentários, e responda, em português do Brasil:")
        appendLine()
        appendLine("1. **Divergem?** Se os dois pedem a mesma coisa com palavras diferentes, diga que convergem e resuma o pedido.")
        appendLine("2. **O que cada um quer de verdade:** a intenção por trás de cada pedido (legibilidade, desempenho, segurança…).")
        appendLine("3. **Síntese:** uma alteração que atenda às duas intenções, ou, se for impossível, qual prevalece e por quê.")
        appendLine()
        appendLine("**Não altere nenhum arquivo.** Seja direto: até 8 frases por par.")
        overlaps.forEachIndexed { index, overlap ->
            appendLine()
            appendLine("## Par ${index + 1} · `${overlap.path}`, ${overlap.location}")
            appendLine()
            appendLine("### ${overlap.first.id} · ${overlap.first.source.label} · ${overlap.first.location}")
            appendLine()
            appendLine(overlap.first.text.trim())
            appendLine()
            appendLine("### ${overlap.second.id} · ${overlap.second.source.label} · ${overlap.second.location}")
            appendLine()
            appendLine(overlap.second.text.trim())
        }
    }
}
