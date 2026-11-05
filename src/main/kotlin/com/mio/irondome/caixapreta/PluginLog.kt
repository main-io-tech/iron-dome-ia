package com.mio.irondome.caixapreta

import com.intellij.openapi.application.PathManager
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Erros e avisos do `idea.log` que passaram pelo código do plugin, do mais recente para o mais antigo. */
object PluginLog {

    private val ENTRY = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}),\d{3} \[\s*\d+]\s+(\w+)\s+-\s+(\S+)\s+-\s?(.*)$""")
    private val LEVELS = setOf("ERROR", "SEVERE", "WARN")
    /** O log pode ter dezenas de megabytes; os erros que interessam estão no fim. */
    private const val TAIL_BYTES = 4L * 1024 * 1024
    const val FILE_NAME = "idea.log"
    /** O log anterior, que a IDE guarda ao rodar o `idea.log`. */
    private const val ROTATED_FILE_NAME = "idea.1.log"

    val file: Path get() = Path.of(PathManager.getLogPath(), FILE_NAME)

    /** Com [includeRotated], completa com o log anterior quando o atual não tem [limit] erros. */
    fun recentErrors(limit: Int = 5, includeRotated: Boolean = false): List<PluginError> {
        val errors = read(file, limit)
        if (!includeRotated || errors.size >= limit) return errors
        return errors + read(Path.of(PathManager.getLogPath(), ROTATED_FILE_NAME), limit - errors.size)
    }

    /** Junta as ocorrências do mesmo erro, mantendo a ordem do mais recente para o mais antigo. */
    fun group(errors: List<PluginError>): List<ErrorGroup> =
        errors.groupBy { Triple(it.level, it.message, it.origin ?: it.exception) }.values.map { same ->
            ErrorGroup(same.first(), same.size, same.last().time)
        }

    /** O erro por inteiro, com a pilha completa, para ler na janela de logs. */
    fun describe(group: ErrorGroup): String = buildString {
        val error = group.latest
        appendLine("${error.time} · ${error.level}${occurrences(group)}")
        error.mechanism?.let { appendLine("Mecanismo: ${it.title} (${it.qualifiedPackage})") }
        error.origin?.let { appendLine("Origem no plugin: $it") }
        if (error.category.isNotBlank()) appendLine("Registrado por: ${error.category}")
        appendLine()
        appendLine(error.message)
        if (error.stack.isNotEmpty()) {
            appendLine()
            error.stack.forEach(::appendLine)
        }
    }.trimEnd()

    /** Para colar numa conversa ou issue: os frames da plataforma longe do plugin saem, como no bug.md. */
    fun markdown(groups: List<ErrorGroup>): String = buildString {
        appendLine("## Erros do Iron Dome IA no log da IDE")
        groups.forEach { group ->
            val error = group.latest
            appendLine()
            appendLine("### ${error.time} · ${error.level}${error.mechanism?.let { " · ${it.title}" }.orEmpty()}${occurrences(group)}")
            appendLine()
            appendLine(error.message.lines().joinToString(" ") { it.trim() }.trim())
            error.origin?.let {
                appendLine()
                appendLine("Origem no plugin: `$it`")
            }
            if (error.stack.isNotEmpty()) {
                appendLine()
                appendLine("```text")
                BugReportMarkdown.compactStack(error.stack).forEach(::appendLine)
                appendLine("```")
            }
        }
    }.trimEnd() + "\n"

    private fun occurrences(group: ErrorGroup) =
        if (group.count > 1) " (${group.count} vezes, desde ${group.firstTime})" else ""

    internal fun parse(text: String, limit: Int): List<PluginError> {
        val errors = mutableListOf<PluginError>()
        var header: MatchResult? = null
        val body = mutableListOf<String>()
        fun flush() {
            val (time, level, category, message) = header?.destructured ?: return
            val pluginCode = Mechanism.ROOT_PACKAGE in category || Mechanism.ROOT_PACKAGE in message ||
                body.any { Mechanism.ROOT_PACKAGE in it }
            if (level in LEVELS && pluginCode) errors += PluginError(time, level, message.trim(), body.toList(), category)
        }
        for (line in text.lineSequence()) {
            val match = ENTRY.matchEntire(line)
            if (match != null) {
                flush()
                header = match
                body.clear()
            } else if (header != null && line.isNotBlank()) {
                body += line.trimEnd()
            }
        }
        flush()
        return errors.asReversed().take(limit)
    }

    private fun read(log: Path, limit: Int): List<PluginError> =
        if (Files.isRegularFile(log)) parse(tail(log), limit) else emptyList()

    private fun tail(log: Path): String = RandomAccessFile(log.toFile(), "r").use { file ->
        val start = (file.length() - TAIL_BYTES).coerceAtLeast(0)
        file.seek(start)
        val bytes = ByteArray((file.length() - start).toInt())
        file.readFully(bytes)
        val text = String(bytes, StandardCharsets.UTF_8)
        if (start == 0L) text else text.substringAfter('\n')
    }
}
