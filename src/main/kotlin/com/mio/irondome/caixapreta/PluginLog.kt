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

    fun recentErrors(limit: Int = 5): List<PluginError> {
        val log = Path.of(PathManager.getLogPath(), "idea.log")
        if (!Files.isRegularFile(log)) return emptyList()
        return parse(tail(log), limit)
    }

    internal fun parse(text: String, limit: Int): List<PluginError> {
        val errors = mutableListOf<PluginError>()
        var header: MatchResult? = null
        val body = mutableListOf<String>()
        fun flush() {
            val (time, level, category, message) = header?.destructured ?: return
            val pluginCode = Mechanism.ROOT_PACKAGE in category || Mechanism.ROOT_PACKAGE in message ||
                body.any { Mechanism.ROOT_PACKAGE in it }
            if (level in LEVELS && pluginCode) errors += PluginError(time, level, message.trim(), body.toList())
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

    private fun tail(log: Path): String = RandomAccessFile(log.toFile(), "r").use { file ->
        val start = (file.length() - TAIL_BYTES).coerceAtLeast(0)
        file.seek(start)
        val bytes = ByteArray((file.length() - start).toInt())
        file.readFully(bytes)
        val text = String(bytes, StandardCharsets.UTF_8)
        if (start == 0L) text else text.substringAfter('\n')
    }
}
