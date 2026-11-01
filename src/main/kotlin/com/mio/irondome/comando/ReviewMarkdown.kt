package com.mio.irondome.comando

import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.SavedComment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReviewMarkdown {

    private const val APPLY_MODE = "aplicar direto"
    private const val DISCUSS_MODE = "discutir antes de alterar"

    /** Escrito para qualquer assistente de IA, sem depender de ferramentas de um produto específico. */
    private val INSTRUCTIONS = """
        **Instruções para a IA** (valem para qualquer assistente ou modelo):

        1. Antes de alterar qualquer arquivo, leia esta revisão inteira e os arquivos citados. Localize
           cada comentário pendente (`[ ]`) pelo trecho citado, pois os números de linha podem ter mudado.
           Comentários de **$WHOLE_FILE** valem para o arquivo todo, e os de **$WHOLE_PACKAGE**, para
           todos os arquivos da pasta indicada no título da seção; eles não citam trecho.
        2. Analise os comentários pendentes em conjunto, inclusive os de arquivos diferentes, e procure:
           - **Contradições:** comentários que pedem coisas incompatíveis para o mesmo ponto, a mesma
             regra ou o mesmo comportamento.
           - **Cruzamentos:** aplicar um comentário quebra ou invalida outro. Por exemplo, um renomeia,
             move ou remove algo que outro cita ou de que depende, ou os dois alteram o mesmo trecho.
             Um comentário de arquivo ou pacote inteiro cruza com os comentários dos arquivos que abrange.
        3. Se encontrar contradição ou cruzamento, não altere nenhum arquivo e entre no modo de pergunta
           (abaixo), apresentando cada caso com os comentários envolvidos (`C1`, `C2`...), o conflito e
           as opções de solução.
        4. Se não houver contradições nem cruzamentos, ou depois das respostas do usuário, trate os
           comentários em sequência, na ordem deste arquivo, respeitando o modo de cada um:
           - **$APPLY_MODE:** aplique a correção sem consultar o usuário.
           - **$DISCUSS_MODE:** não altere nenhum arquivo antes de chegar a uma decisão com o usuário
             no modo de discussão (abaixo), um comentário por vez.
        5. Depois de tratar um comentário, troque `[ ]` por `[x]` no título dele, inclusive quando a
           decisão for manter o código como está. Se o usuário deixar para depois, mantenha `[ ]`. Não
           altere o resto deste arquivo.

        **Modo de pergunta:** $ASK_MODE

        **Modo de discussão:** não reduza o comentário a uma pergunta de múltipla escolha. Leia o código
        citado, quem o usa e os testes dele e, antes de qualquer pergunta, escreva no chat uma análise
        completa:
        - **Entendimento:** o que o comentário aponta ou pergunta, nas suas palavras.
        - **Por que está assim:** os motivos prováveis do código atual, o que ele resolve e as evidências
          no código, nos testes ou no histórico.
        - **Avaliação:** se o apontamento procede, com os argumentos a favor e contra.
        - **Impacto:** o que muda, quebra ou passa a ser exigido ao manter, alterar ou remover o trecho:
          chamadores, testes, comportamento, dados, desempenho e segurança.
        - **Opções:** de 2 a 5, numeradas, sempre incluindo manter como está e, se fizer sentido, remover.
          Para cada uma: o que muda, vantagens, desvantagens, impacto, risco e uma prévia da alteração
          (diff ou o trecho como ficaria).
        - **Recomendação:** a opção que você escolheria e por quê.

        Só então pergunte qual opção seguir pelo modo de pergunta, com rótulos curtos que remetam às
        opções numeradas, mais "deixar para depois". Se o usuário discordar, questionar ou trouxer outro
        argumento, responda com argumentos, revise a análise e as opções e pergunte de novo. Altere
        arquivos só quando a decisão final estiver clara: manter, alterar (com a opção escolhida) ou remover.
    """.trimIndent()

    private const val ASK_MODE =
        "use a ferramenta de perguntas estruturadas do seu ambiente, se houver (por exemplo, `AskQuestion` " +
            "no Cursor ou `AskUserQuestion` no Claude Code), com uma pergunta por caso e as opções de resposta. " +
            "Se não houver, pergunte no chat com opções numeradas. Em qualquer caso, pare e espere a resposta: " +
            "não suponha a escolha do usuário nem altere arquivos antes dela."

    private const val DISCUSS_NOTICE =
        "**IA: discuta antes de alterar.** Não altere nenhum arquivo por causa deste comentário. Siga o " +
            "modo de discussão das instruções do topo: escreva no chat a análise completa (entendimento, por que " +
            "o código está assim, avaliação e impacto de manter, alterar ou remover), de 2 a 5 opções com prévia " +
            "da alteração e a sua recomendação. Só depois pergunte qual seguir, e continue a discussão até a " +
            "decisão final de manter, alterar ou remover."

    private const val WHOLE_FILE = "arquivo inteiro"
    private const val WHOLE_PACKAGE = "pacote inteiro"

    private val resolvedHeading = Regex("""^#+ \[[xX]] (C\d+)\b""", RegexOption.MULTILINE)
    private val fileHeading = Regex("""^## `(.+)`\s*$""")
    private val packageHeading = Regex("""^## Pacote `(.+?)/?`\s*$""")
    private val commentHeading =
        Regex("""^### \[([ xX])] (C\d+) — (?:linhas? (\d+)(?:–(\d+))?|($WHOLE_FILE|$WHOLE_PACKAGE))""")
    private const val COMMENT_LABEL = "**Comentário:**"
    private const val MODE_LABEL = "**Modo:**"
    private val displayTimestamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /** O aviso para a IA que [render] põe nos comentários de discutir antes de alterar ainda pendentes. */
    fun isDiscussNotice(line: String) = line == "> $DISCUSS_NOTICE"

    fun pendingLine(pending: Int, total: Int) = "Pendentes: $pending de $total."

    fun resolvedIds(markdown: String): Set<String> =
        resolvedHeading.findAll(markdown).map { it.groupValues[1] }.toSet()

    /** Lê os comentários de um `.md` gravado por [render], como o de uma revisão já concluída. */
    fun parse(markdown: String): List<SavedComment> {
        val comments = mutableListOf<SavedComment>()
        var path = ""
        var current: ParsedComment? = null

        fun flush() {
            current?.let { comments += it.toSaved(path) }
            current = null
        }

        for (line in markdown.lines()) {
            val file = fileHeading.matchEntire(line) ?: packageHeading.matchEntire(line)
            val heading = commentHeading.find(line)
            val parsing = current
            val text = parsing?.text
            when {
                file != null -> {
                    flush()
                    path = file.groupValues[1]
                }
                heading != null -> {
                    flush()
                    current = ParsedComment(heading)
                }
                parsing == null -> Unit
                text != null -> text += line
                line == COMMENT_LABEL -> parsing.text = mutableListOf()
                line.startsWith(MODE_LABEL) -> parsing.discuss = DISCUSS_MODE in line
            }
        }
        flush()
        return comments
    }

    private class ParsedComment(val heading: MatchResult) {
        var discuss = false
        var text: MutableList<String>? = null

        fun toSaved(path: String): SavedComment {
            val (mark, id, start, end, whole) = heading.destructured
            val target = when (whole) {
                WHOLE_FILE -> CommentTarget.FILE
                WHOLE_PACKAGE -> CommentTarget.PACKAGE
                else -> CommentTarget.LINES
            }
            return SavedComment(
                id = id,
                filePath = path,
                startLine = (start.toIntOrNull() ?: 1) - 1,
                endLine = (end.ifEmpty { start }.toIntOrNull() ?: 1) - 1,
                resolved = mark.isNotBlank(),
                discuss = discuss,
                text = text.orEmpty().joinToString("\n").trim(),
                target = target,
            )
        }
    }

    /** `comments` devem vir ordenados por [ReviewComment.ORDER]. */
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
        INSTRUCTIONS.lines().forEach { appendLine(if (it.isEmpty()) ">" else "> $it") }
        appendLine()
        appendLine(pendingLine(comments.count { !it.resolved }, comments.size))

        for ((section, fileComments) in comments.groupBy { it.filePath to (it.target == CommentTarget.PACKAGE) }) {
            val (path, isPackage) = section
            appendLine()
            appendLine(if (isPackage) "## Pacote `$path/`" else "## `$path`")
            for (comment in fileComments) {
                appendLine()
                val mark = if (comment.resolved) "x" else " "
                appendLine("### [$mark] ${comment.id} — ${describe(comment.target, comment.startLine, comment.endLine)}")
                appendLine()
                appendLine("**Modo:** ${if (comment.discuss) DISCUSS_MODE else APPLY_MODE}")
                appendLine()
                if (comment.discuss && !comment.resolved) {
                    appendLine("> $DISCUSS_NOTICE")
                    appendLine()
                }
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

    fun describe(target: CommentTarget, startLine: Int, endLine: Int): String = when (target) {
        CommentTarget.LINES -> describeLines(startLine, endLine)
        CommentTarget.FILE -> WHOLE_FILE
        CommentTarget.PACKAGE -> WHOLE_PACKAGE
    }

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
