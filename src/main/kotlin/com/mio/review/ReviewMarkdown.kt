package com.mio.review

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReviewMarkdown {

    private const val APPLY_MODE = "aplicar direto"
    private const val DISCUSS_MODE = "discutir antes de alterar"

    private val resolvedHeading = Regex("""^#+ \[[xX]] (C\d+)\b""", RegexOption.MULTILINE)
    private val displayTimestamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    fun resolvedIds(markdown: String): Set<String> =
        resolvedHeading.findAll(markdown).map { it.groupValues[1] }.toSet()

    /** `comments` devem vir ordenados por arquivo e linha. */
    fun render(
        reviewName: String,
        comments: List<ReviewComment>,
        createdAt: LocalDateTime,
        updatedAt: LocalDateTime,
    ): String = buildString {
        appendLine("# Revisão: $reviewName")
        appendLine()
        appendLine("Criada em ${createdAt.format(displayTimestamp)}. Atualizada em ${updatedAt.format(displayTimestamp)}.")
        appendLine()
        appendLine("> **Instruções para a IA:** trate cada comentário pendente (`[ ]`) no arquivo indicado na seção dele.")
        appendLine("> Use o trecho citado para localizar o ponto exato, pois os números de linha podem ter mudado.")
        appendLine("> Respeite o modo de cada comentário:")
        appendLine("> - **$APPLY_MODE:** aplique a correção sem consultar o usuário.")
        appendLine("> - **$DISCUSS_MODE:** não altere nenhum arquivo antes de discutir. Apresente ao usuário sua")
        appendLine(">   análise do apontamento e a alteração que propõe, e só aplique depois que ele concordar.")
        appendLine("> Depois de tratar um comentário, troque `[ ]` por `[x]` no título dele. Não altere o resto deste arquivo.")
        appendLine()
        appendLine("Pendentes: ${comments.count { !it.resolved }} de ${comments.size}.")

        for ((path, fileComments) in comments.groupBy { it.filePath }) {
            appendLine()
            appendLine("## `$path`")
            for (comment in fileComments) {
                appendLine()
                val mark = if (comment.resolved) "x" else " "
                appendLine("### [$mark] ${comment.id} — ${describeLines(comment.startLine, comment.endLine)}")
                appendLine()
                appendLine("**Modo:** ${if (comment.discuss) DISCUSS_MODE else APPLY_MODE}")
                appendLine()
                if (comment.snippet.isNotBlank()) {
                    val fence = fenceFor(comment.snippet)
                    appendLine("$fence${language(path)}")
                    appendLine(comment.snippet.trimEnd('\n'))
                    appendLine(fence)
                    appendLine()
                }
                appendLine("**Comentário:**")
                appendLine()
                appendLine(comment.text.trim())
            }
        }
    }

    fun describeLines(startLine: Int, endLine: Int): String =
        if (startLine == endLine) "linha ${startLine + 1}" else "linhas ${startLine + 1}–${endLine + 1}"

    private fun fenceFor(snippet: String): String {
        var fence = "```"
        while (snippet.contains(fence)) fence += "`"
        return fence
    }

    private fun language(path: String): String =
        when (val extension = path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "ts", "tsx" -> "typescript"
            "js", "jsx", "mjs" -> "javascript"
            "py" -> "python"
            "yml" -> "yaml"
            "sh" -> "bash"
            "md" -> "markdown"
            else -> extension
        }
}
