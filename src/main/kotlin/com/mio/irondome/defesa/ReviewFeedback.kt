package com.mio.irondome.defesa

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.mio.irondome.base.rootPath
import com.mio.irondome.radar.CommentMarkdown
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Sobre o que um comentário fala, por palavras-chave, para saber o que o time costuma descartar. */
enum class ReviewTheme(val label: String, private val keys: List<String>) {
    NAMING("nomenclatura", listOf("nome", "nomear", "renome", "naming", "chamar de", "identificador")),
    STYLE("estilo e formatação", listOf("formata", "indenta", "espaço", "estilo", "linha em branco", "ponto e vírgula", "chaves", "ordem dos imports")),
    LOGGING("logs", listOf("log ", "logger", "logging", "println", "print(")),
    DOCS("documentação", listOf("javadoc", "kdoc", "documenta", "comentário explicando", "comente o")),
    TESTS("testes", listOf("teste", "test", "cobertura", "assert")),
    PERFORMANCE("desempenho", listOf("desempenho", "performance", "lento", "o(n", "complexidade", "alocação", "cache")),
    NULLS("nulos", listOf("null", "nulo", "nullpointer", "npe", "!!", "?.")),
    EXCEPTIONS("exceções", listOf("exceção", "exception", "try", "catch", "throw", "lança")),
    CONCURRENCY("concorrência", listOf("thread", "concorr", "race", "sincron", "volatile", "lock", "edt")),
    REFACTOR("refatoração", listOf("extrai", "refator", "duplica", "método longo", "classe grande", "responsabilidade")),
    OTHER("outros", emptyList()),
    ;

    companion object {
        fun of(text: String): ReviewTheme {
            val lower = text.lowercase()
            return entries.firstOrNull { theme -> theme != OTHER && theme.keys.any { it in lower } } ?: OTHER
        }

        fun ofLabel(label: String): ReviewTheme? = entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
    }
}

/** O que o time fez com as sugestões da IA até agora, mais as regras que ele escreveu à mão. */
data class FeedbackState(
    val accepted: Int = 0,
    val discarded: Int = 0,
    /** Marcados **Bug:** pela IA que o time rebaixou para Atenção ou Sugestão antes de enviar. */
    val downgraded: Int = 0,
    /** Quantos **Bug:** a IA propôs, para a proporção dos rebaixados. */
    val bugsProposed: Int = 0,
    val acceptedByTheme: Map<ReviewTheme, Int> = emptyMap(),
    val discardedByTheme: Map<ReviewTheme, Int> = emptyMap(),
    /** Regras escritas pelo time no `.md`, como "no pacote legacy não sugira refatoração". */
    val rules: List<String> = emptyList(),
) {
    val total: Int get() = accepted + discarded

    /** Temas que o time descarta na maior parte das vezes, com amostra mínima. */
    val rejectedThemes: List<ReviewTheme>
        get() = ReviewTheme.entries.filter { theme ->
            val no = discardedByTheme[theme] ?: 0
            val yes = acceptedByTheme[theme] ?: 0
            theme != ReviewTheme.OTHER && no >= MIN_SAMPLE && no.toDouble() / (no + yes) >= REJECT_RATIO
        }

    val overAlarming: Boolean get() = downgraded >= MIN_SAMPLE && downgraded.toDouble() / bugsProposed.coerceAtLeast(1) >= DOWNGRADE_RATIO

    /** O que entra no pedido à IA; vazio enquanto não há sinal suficiente nem regras. */
    fun promptHints(): List<String> = buildList {
        rejectedThemes.forEach { theme ->
            add("O time costuma descartar comentários sobre ${theme.label}: só aponte isso quando causar um bug real ou um risco concreto.")
        }
        if (overAlarming) {
            add("O time rebaixou $downgraded de $bugsProposed comentários marcados **Bug:** para **Atenção:**: use **Bug:** só quando houver quebra ou resultado errado que você consiga demonstrar com um cenário.")
        }
        rules.forEach { add(it) }
    }

    companion object {
        const val MIN_SAMPLE = 3
        const val REJECT_RATIO = 0.6
        const val DOWNGRADE_RATIO = 0.5
    }
}

/** O `.md` do aprendizado: o time lê, edita as regras e commita, e o plugin atualiza as contagens. */
object FeedbackMarkdown {

    const val FILE_NAME = "aprendizado-revisao.md"
    private const val RULES = "## Regras do time"
    private const val COUNTS = "## Contagens (automático)"
    private val COUNT = Regex("""^- (aceitos|descartados|rebaixados|bugs propostos): (\d+)\s*$""")
    private val THEME_COUNT = Regex("""^\s+- ([^:]+): (\d+)\s*$""")

    fun render(state: FeedbackState): String = buildString {
        appendLine("# Aprendizado da revisão")
        appendLine()
        appendLine("O Iron Dome IA ajusta os pedidos à IA com o que o time aceita e descarta das sugestões dela. As regras abaixo")
        appendLine("entram em todo pedido de revisão: escreva uma por linha, começando com `- `, como")
        appendLine("`- No pacote legacy, não sugira refatoração.`")
        appendLine()
        appendLine(RULES)
        appendLine()
        state.rules.forEach { appendLine("- $it") }
        if (state.rules.isEmpty()) appendLine("<!-- nenhuma regra ainda -->")
        appendLine()
        appendLine(COUNTS)
        appendLine()
        appendLine("- aceitos: ${state.accepted}")
        state.acceptedByTheme.entries.sortedByDescending { it.value }.forEach { (theme, count) -> appendLine("    - ${theme.label}: $count") }
        appendLine("- descartados: ${state.discarded}")
        state.discardedByTheme.entries.sortedByDescending { it.value }.forEach { (theme, count) -> appendLine("    - ${theme.label}: $count") }
        appendLine("- rebaixados: ${state.downgraded}")
        appendLine("- bugs propostos: ${state.bugsProposed}")
    }

    fun parse(markdown: String): FeedbackState {
        var section = ""
        var lastCount = ""
        var state = FeedbackState()
        val rules = mutableListOf<String>()
        val acceptedBy = mutableMapOf<ReviewTheme, Int>()
        val discardedBy = mutableMapOf<ReviewTheme, Int>()
        for (line in markdown.lines()) {
            when {
                line.startsWith("## ") -> section = line.trim()
                section == RULES && line.startsWith("- ") -> rules += line.removePrefix("- ").trim()
                section == COUNTS -> {
                    COUNT.matchEntire(line)?.let { match ->
                        val value = match.groupValues[2].toInt()
                        lastCount = match.groupValues[1]
                        state = when (lastCount) {
                            "aceitos" -> state.copy(accepted = value)
                            "descartados" -> state.copy(discarded = value)
                            "rebaixados" -> state.copy(downgraded = value)
                            else -> state.copy(bugsProposed = value)
                        }
                    }
                    THEME_COUNT.matchEntire(line)?.let { match ->
                        val theme = ReviewTheme.ofLabel(match.groupValues[1]) ?: return@let
                        val target = if (lastCount == "aceitos") acceptedBy else if (lastCount == "descartados") discardedBy else return@let
                        target[theme] = match.groupValues[2].toInt()
                    }
                }
            }
        }
        return state.copy(acceptedByTheme = acceptedBy, discardedByTheme = discardedBy, rules = rules.filter { it.isNotEmpty() })
    }
}

/**
 * Aprende o gosto do time: quais rascunhos da IA viram comentário publicado, quais são descartados e quais
 * têm a gravidade rebaixada. Um ciclo de feedback simples, guardado no `.md` do projeto, sem modelo custom.
 */
@Service(Service.Level.PROJECT)
class ReviewFeedback(private val project: Project) {

    /** Rascunhos já contados como aceitos: a remoção deles depois do envio não é descarte. */
    private val settled: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun file(): Path? = project.rootPath()?.let { Path.of(it, ReviewCommentService.REVIEW_DIR, DIR, FeedbackMarkdown.FILE_NAME) }

    fun state(): FeedbackState {
        val file = file()?.takeIf(Files::isRegularFile) ?: return FeedbackState()
        return runCatching { FeedbackMarkdown.parse(Files.readString(file)) }.getOrElse {
            LOG.warn("Não deu para ler ${FeedbackMarkdown.FILE_NAME}", it)
            FeedbackState()
        }
    }

    /** O que entra no pedido de revisão à IA, ou vazio com o aprendizado desligado ou sem sinal. */
    fun promptHints(): List<String> = if (DefenseSettings.feedbackLearning) state().promptHints() else emptyList()

    /** A IA propôs estes rascunhos: conta os **Bug:** para calibrar a gravidade depois. */
    fun proposed(comments: Collection<ReviewComment>) {
        if (comments.isEmpty()) return
        val bugs = comments.count { CommentMarkdown.severity(it.aiText.ifEmpty { it.text }) == Severity.BUG }
        update { it.copy(bugsProposed = it.bugsProposed + bugs) }
    }

    /** Enviados ao GitHub: aceitos, e rebaixados se a gravidade caiu em relação ao que a IA escreveu. */
    fun accepted(comments: Collection<ReviewComment>) {
        val fromAi = comments.filter { it.fromAi && settled.add(it.id) }
        if (fromAi.isEmpty()) return
        val downgraded = fromAi.count { comment ->
            CommentMarkdown.severity(comment.aiText) == Severity.BUG && CommentMarkdown.severity(comment.text) != Severity.BUG
        }
        val themes = fromAi.groupingBy { ReviewTheme.of(it.aiText.ifEmpty { it.text }) }.eachCount()
        update { state ->
            state.copy(
                accepted = state.accepted + fromAi.size,
                downgraded = state.downgraded + downgraded,
                acceptedByTheme = merge(state.acceptedByTheme, themes),
            )
        }
    }

    /** Removidos sem enviar: descartados. */
    fun discarded(comments: Collection<ReviewComment>) {
        val fromAi = comments.filter { it.fromAi && it.pullRequest != 0 && it.id !in settled }
        if (fromAi.isEmpty()) return
        fromAi.forEach { settled += it.id }
        val themes = fromAi.groupingBy { ReviewTheme.of(it.aiText.ifEmpty { it.text }) }.eachCount()
        update { state -> state.copy(discarded = state.discarded + fromAi.size, discardedByTheme = merge(state.discardedByTheme, themes)) }
    }

    private fun merge(base: Map<ReviewTheme, Int>, more: Map<ReviewTheme, Int>): Map<ReviewTheme, Int> =
        (base.keys + more.keys).associateWith { (base[it] ?: 0) + (more[it] ?: 0) }

    private fun update(change: (FeedbackState) -> FeedbackState) {
        if (!DefenseSettings.feedbackLearning) return
        val file = file() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val next = change(state())
                Files.createDirectories(file.parent)
                Files.writeString(file, FeedbackMarkdown.render(next))
                LocalFileSystem.getInstance().refreshNioFiles(listOf(file))
            }.onFailure { LOG.warn("Não deu para gravar ${FeedbackMarkdown.FILE_NAME}", it) }
        }
    }

    companion object {
        const val DIR = "defesa"
        private val LOG = logger<ReviewFeedback>()

        fun getInstance(project: Project): ReviewFeedback = project.service()
    }
}
