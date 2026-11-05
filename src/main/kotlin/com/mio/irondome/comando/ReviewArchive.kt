package com.mio.irondome.comando

import com.mio.irondome.radar.SavedComment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Histórico consolidado: as revisões encerradas de `iron-dome/` reunidas em [FILE_NAME], uma seção
 * `# Revisão: <nome>` por revisão, com o conteúdo do `.md` original menos as instruções para a IA.
 */
object ReviewArchive {

    const val FILE_NAME = "historico-interceptacoes.md"

    private val reviewHeading = Regex("""^# Revisão: (\S+)\s*$""")
    private val createdAt = Regex("""Criada em (\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2})""")
    private val trailingNumber = Regex("""(\d+)$""")
    private val pendingCount = Regex("""^Pendentes: \d+ de \d+\.$""")
    private val displayTimestamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private const val SEPARATOR = "---"

    /** Uma revisão do histórico; [body] começa pelo título `# Revisão:`. */
    class Entry(val name: String, val body: String) {
        val comments: List<SavedComment> by lazy { ReviewMarkdown.parse(body) }
        val createdAt: String? get() = ReviewArchive.createdAt.find(body)?.groupValues?.get(1)
        val pending: Int get() = comments.count { !it.resolved }
    }

    /** As revisões de um histórico consolidado, na ordem em que aparecem. */
    fun entries(markdown: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var name: String? = null
        val lines = mutableListOf<String>()
        fun flush() {
            name?.let { entries += Entry(it, lines.joinToString("\n").trim().removeSuffix(SEPARATOR).trim()) }
            lines.clear()
        }
        for (line in markdown.lines()) {
            reviewHeading.matchEntire(line)?.let {
                flush()
                name = it.groupValues[1]
            }
            if (name != null) lines += line
        }
        flush()
        return entries
    }

    /**
     * O `.md` de uma revisão pronto para o histórico: sem as instruções para a IA (o bloco do topo e os avisos
     * de discutir antes) e com a contagem de pendentes refeita, já que a IA só marca o `[x]`.
     */
    fun entryOf(name: String, markdown: String): Entry {
        val lines = markdown.lines()
        val firstSection = lines.indexOfFirst { it.startsWith("## ") }.takeIf { it >= 0 } ?: lines.size
        val comments = ReviewMarkdown.parse(markdown)
        val kept = lines.filterIndexed { i, line ->
            val instructions = i < firstSection && (line == ">" || line.startsWith("> "))
            !instructions && !ReviewMarkdown.isDiscussNotice(line)
        }.map { if (pendingCount.matches(it)) ReviewMarkdown.pendingLine(comments.count { c -> !c.resolved }, comments.size) else it }
        return Entry(name, collapseBlankLines(kept).trim())
    }

    /** Junta [added] às revisões de [existing], da mais recente para a mais antiga; a repetida fica com a versão de [added]. */
    fun render(existing: String?, added: List<Entry>, now: LocalDateTime): String {
        val byName = LinkedHashMap<String, Entry>()
        existing?.let(::entries)?.forEach { byName[it.name] = it }
        added.forEach { byName[it.name] = it }
        val all = byName.values.sortedByDescending { trailingNumber.find(it.name)?.value?.toIntOrNull() ?: 0 }
        val total = all.sumOf { it.comments.size }
        val pending = all.sumOf { it.pending }
        return buildString {
            appendLine("# Histórico de interceptações")
            appendLine()
            appendLine("> **Arquivo de histórico, não é uma ordem de interceptação.** Reúne as revisões encerradas que o Radar")
            appendLine("> consolidou. IA: não aplique, não discuta e não marque os comentários daqui.")
            appendLine()
            append("Consolidado em ${now.format(displayTimestamp)}. ")
            append(count(all.size, "revisão", "revisões")).append(", ").append(count(total, "comentário", "comentários"))
            if (pending > 0) append(", ").append(count(pending, "ainda pendente", "ainda pendentes"))
            appendLine(".")
            appendLine()
            appendLine("| Revisão | Criada em | Comentários | Interceptados | Pendentes |")
            appendLine("| --- | --- | ---: | ---: | ---: |")
            for (entry in all) {
                val size = entry.comments.size
                appendLine("| ${entry.name} | ${entry.createdAt ?: "—"} | $size | ${size - entry.pending} | ${entry.pending} |")
            }
            for (entry in all) {
                appendLine()
                appendLine(SEPARATOR)
                appendLine()
                appendLine(entry.body)
            }
        }
    }

    private fun count(value: Int, one: String, many: String) = "$value ${if (value == 1) one else many}"

    private fun collapseBlankLines(lines: List<String>): String = buildList<String> {
        for (line in lines) if (line.isNotBlank() || lastOrNull()?.isNotBlank() == true) add(line)
    }.joinToString("\n")
}
