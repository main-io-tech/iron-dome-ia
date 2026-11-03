package com.mio.irondome.caixapreta

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Parte do plugin onde o bug apareceu, com o pacote do código, para quem for corrigir saber por onde começar. */
enum class Mechanism(val title: String, val packageName: String, val contents: String) {
    RADAR("Radar", "radar", "comentários, formulário, margem do editor, cartões e a aba Radar"),
    COMANDO("Centro de Comando", "comando", "geração e leitura do .md, histórico consolidado e as ações de salvar"),
    IMPACTO("Raio de Impacto", "impacto", "análise de dependências, diagrama e métricas"),
    SENTINELA("Sentinela", "sentinela", "aba Sentinela e o diff dos arquivos não commitados"),
    ESCUDO("Escudo", "escudo", "execução dos testes JUnit, formatação do @DisplayName e a aba Escudo"),
    BASE("Base", "base", "tool window, abas extras, barra flutuante do editor e componentes visuais compartilhados"),
    CAIXA_PRETA("Caixa-Preta", "caixapreta", "o próprio Reportar Bug: formulário, capturas e envio ao Linear");

    val qualifiedPackage get() = "$ROOT_PACKAGE.$packageName"
    val sourceDir get() = "src/main/kotlin/${qualifiedPackage.replace('.', '/')}/"

    companion object {
        const val ROOT_PACKAGE = "com.mio.irondome"

        fun ofTab(title: String?): Mechanism? = entries.firstOrNull { it.title == title }

        /** Pelo pacote de um frame da pilha, como `com.mio.irondome.radar.CommentForm.submit(CommentForm.kt:120)`. */
        fun ofFrame(frame: String): Mechanism? = entries.firstOrNull { frame.startsWith("${it.qualifiedPackage}.") }
    }
}

/** Erro ou aviso do log da IDE que passou pelo código do plugin. [stack] traz as linhas depois da mensagem. */
data class PluginError(val time: String, val level: String, val message: String, val stack: List<String>) {

    /** Primeiro frame do plugin na pilha: o ponto mais provável do bug. */
    val origin: String?
        get() = stack.firstNotNullOfOrNull { line ->
            line.trim().removePrefix("at ").takeIf { it.startsWith("${Mechanism.ROOT_PACKAGE}.") }
        }
}

/** [errors] vem do mais recente para o mais antigo; [context] são pares de nome e valor do ambiente. */
data class BugReport(
    val title: String,
    val mechanism: Mechanism,
    val description: String,
    val steps: String,
    val expected: String,
    val errors: List<PluginError>,
    val context: List<Pair<String, String>>,
    val screenshot: Boolean,
    val recording: Boolean,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

object BugReportMarkdown {
    const val FILE_NAME = "bug.md"
    const val SCREENSHOT = "print.png"
    const val RECORDING = "gravacao.gif"

    /** Frames de fora do plugin mantidos no início de cada exceção; o resto vira uma linha de resumo. */
    private const val LEADING_FRAMES = 4
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /** [mediaUrl] troca o nome do arquivo da captura pelo endereço onde ela foi publicada. */
    fun render(report: BugReport, mediaUrl: (String) -> String = { it }): String = buildString {
        val mechanism = report.mechanism
        val origin = report.errors.firstNotNullOfOrNull { it.origin }

        appendLine("# Bug: ${report.title.oneLine()}")
        appendLine()
        appendLine("Relatado em ${report.createdAt.format(DATE)} pelo Reportar Bug do Iron Dome IA.")
        appendLine()
        appendLine("## Onde no plugin")
        appendLine()
        appendLine("- **Mecanismo:** ${mechanism.title}, pacote `${mechanism.qualifiedPackage}` (${mechanism.contents}).")
        appendLine("- **Código:** `${mechanism.sourceDir}`")
        if (origin != null) {
            val other = Mechanism.ofFrame(origin)?.takeIf { it != mechanism }
            append("- **Origem provável:** `$origin`, o primeiro ponto do plugin na pilha do erro mais recente")
            appendLine(if (other != null) ", que fica no mecanismo ${other.title} (`${other.sourceDir}`)." else ".")
        } else {
            appendLine("- **Origem provável:** nenhum erro do plugin no log da IDE; comece pelo pacote do mecanismo.")
        }
        appendLine()
        appendLine("> **Para quem for corrigir, pessoa ou IA:** reproduza com os passos abaixo, comece pela origem provável e")
        appendLine("> pelo pacote indicado e confira os erros do log no fim do relatório.")

        section("O que aconteceu", report.description)
        section("Passos para reproduzir", report.steps)
        section("O que era esperado", report.expected)

        if (report.screenshot || report.recording) {
            appendLine()
            appendLine("## Capturas")
            if (report.screenshot) {
                appendLine()
                appendLine("![Print da janela da IDE](${mediaUrl(SCREENSHOT)})")
            }
            if (report.recording) {
                appendLine()
                appendLine("![Gravação da janela da IDE](${mediaUrl(RECORDING)})")
            }
        }

        appendLine()
        appendLine("## Erros do plugin no log da IDE")
        appendLine()
        if (report.errors.isEmpty()) {
            appendLine("Nenhum erro ou aviso do plugin no `idea.log`.")
        } else {
            report.errors.forEachIndexed { index, error ->
                if (index > 0) appendLine()
                appendLine("### ${error.time} · ${error.level}: ${error.message.oneLine().take(140)}")
                error.origin?.let {
                    appendLine()
                    appendLine("Origem no plugin: `$it`")
                }
                if (error.stack.isNotEmpty()) {
                    appendLine()
                    appendLine("```text")
                    compactStack(error.stack).forEach(::appendLine)
                    appendLine("```")
                }
            }
        }

        appendLine()
        appendLine("## Ambiente")
        appendLine()
        appendLine("| Item | Valor |")
        appendLine("| --- | --- |")
        report.context.forEach { (name, value) -> appendLine("| $name | ${value.oneLine().replace("|", "\\|")} |") }
    }

    /**
     * Mantém as mensagens, os frames do plugin e os primeiros de cada exceção, que mostram onde ela
     * nasceu; os demais frames da plataforma viram uma linha com quantos foram omitidos.
     */
    internal fun compactStack(stack: List<String>): List<String> {
        val result = mutableListOf<String>()
        var framesInBlock = 0
        var skipped = 0
        fun flushSkipped() {
            if (skipped > 0) result += "\t... ${if (skipped == 1) "1 linha omitida" else "$skipped linhas omitidas"}"
            skipped = 0
        }
        for (line in stack) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("at ")) {
                if (trimmed.startsWith("...") && trimmed.endsWith("more")) {
                    skipped++
                    continue
                }
                flushSkipped()
                result += line
                framesInBlock = 0
                continue
            }
            framesInBlock++
            if (framesInBlock <= LEADING_FRAMES || Mechanism.ROOT_PACKAGE in trimmed) {
                flushSkipped()
                result += line
            } else {
                skipped++
            }
        }
        flushSkipped()
        return result
    }

    private fun StringBuilder.section(title: String, text: String) {
        appendLine()
        appendLine("## $title")
        appendLine()
        appendLine(text.trim().ifEmpty { "_Não informado._" })
    }

    private fun String.oneLine() = lines().joinToString(" ") { it.trim() }.trim()
}
