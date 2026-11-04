package com.mio.irondome.defesa

import com.mio.irondome.impacto.ChangeRisk

/** Se vale chamar a IA para um conjunto de alterações, e com que prioridade. */
enum class TriageVerdict(val label: String) {
    /** Só imports, formatação, comentários ou renomeações: não há o que analisar. */
    SKIP("Não vale analisar"),
    LOW("Vale conferir"),
    HIGH("Vale analisar com prioridade"),
}

data class TriageResult(val verdict: TriageVerdict, val reasons: List<String>, val meaningfulLines: Int) {
    val worthIt: Boolean get() = verdict != TriageVerdict.SKIP
}

/**
 * Triagem antes de chamar a IA, com sinais baratos: o que o diff muda de verdade e o risco de quem mudou.
 * É o que torna o modo automático sustentável: a IA entra quando o sinal justifica.
 */
object Triage {

    /** Linhas alteradas que não são import, comentário, vazio ou só fechamento de bloco. */
    fun meaningfulLines(diff: String): Int = diff.lineSequence()
        .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
        .map { it.drop(1).trim() }
        .count { line -> line.isNotEmpty() && !TRIVIAL.any { it.matches(line) } }

    /** Uma renomeação pura: cada linha removida reaparece adicionada com o mesmo texto fora do identificador. */
    fun looksLikeRename(diff: String): Boolean {
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        diff.lineSequence().forEach {
            when {
                it.startsWith("+++") || it.startsWith("---") -> Unit
                it.startsWith("-") -> removed += it.drop(1).trim()
                it.startsWith("+") -> added += it.drop(1).trim()
            }
        }
        if (removed.isEmpty() || removed.size != added.size) return false
        return removed.zip(added).all { (before, after) -> before != after && shape(before) == shape(after) }
    }

    /**
     * [diff] de todas as alterações; [risks] o risco de cada classe alterada; [missingPartners] co-mudanças quebradas.
     */
    fun classify(diff: String, risks: Collection<ChangeRisk>, missingPartners: Int = 0): TriageResult {
        val lines = meaningfulLines(diff)
        val reasons = mutableListOf<String>()
        if (diff.isBlank()) return TriageResult(TriageVerdict.SKIP, listOf("nenhuma alteração"), 0)
        if (lines == 0) return TriageResult(TriageVerdict.SKIP, listOf("só imports, comentários ou formatação"), 0)
        if (looksLikeRename(diff)) return TriageResult(TriageVerdict.SKIP, listOf("parece só uma renomeação"), lines)
        if (lines < SMALL_CHANGE && risks.none { it != ChangeRisk.LOW } && missingPartners == 0) {
            return TriageResult(TriageVerdict.SKIP, listOf("$lines linhas em classes de risco baixo"), lines)
        }
        val high = risks.count { it == ChangeRisk.HIGH }
        val medium = risks.count { it == ChangeRisk.MEDIUM }
        if (high > 0) reasons += if (high == 1) "1 classe de risco alto alterada" else "$high classes de risco alto alteradas"
        if (missingPartners > 0) reasons += if (missingPartners == 1) "1 arquivo que costuma mudar junto ficou de fora" else "$missingPartners arquivos que costumam mudar junto ficaram de fora"
        if (lines >= LARGE_CHANGE) reasons += "$lines linhas alteradas"
        if (high > 0 || missingPartners > 0 || lines >= LARGE_CHANGE) return TriageResult(TriageVerdict.HIGH, reasons, lines)
        if (medium > 0) reasons += if (medium == 1) "1 classe de risco médio alterada" else "$medium classes de risco médio alteradas"
        if (reasons.isEmpty()) reasons += "$lines linhas alteradas"
        return TriageResult(TriageVerdict.LOW, reasons, lines)
    }

    private fun shape(line: String) = line.replace(IDENTIFIER, "_")

    private const val SMALL_CHANGE = 6
    private const val LARGE_CHANGE = 120
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val TRIVIAL = listOf(
        Regex("""^import\s.*"""),
        Regex("""^package\s.*"""),
        Regex("""^//.*"""),
        Regex("""^/?\*.*"""),
        Regex("""^[{}()\[\];,]*$"""),
        Regex("""^@\w+$"""),
    )
}
