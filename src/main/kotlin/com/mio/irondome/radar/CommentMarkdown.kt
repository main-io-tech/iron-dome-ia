package com.mio.irondome.radar

import com.mio.irondome.base.Tone

/** A gravidade que abre um comentário, como `**Bug:** …`: vira etiqueta no cartão e nas listas. */
enum class Severity(val label: String, val tone: Tone, private vararg val keys: String) {
    BUG("Bug", Tone.RED, "bug", "erro", "falha"),
    WARNING("Atenção", Tone.ORANGE, "atenção", "atencao", "alerta", "risco", "cuidado"),
    SUGGESTION("Sugestão", Tone.BLUE, "sugestão", "sugestao", "melhoria", "dica"),
    ;

    companion object {
        fun ofLabel(label: String): Severity? {
            val key = label.lowercase().filter { it.isLetter() }
            return entries.firstOrNull { severity -> severity.keys.any { it.filter(Char::isLetter) == key } }
        }
    }
}

/** Um trecho de texto com um estilo só. */
class Span(val text: String, val bold: Boolean = false, val code: Boolean = false, val italic: Boolean = false) {
    fun with(text: String) = Span(text, bold, code, italic)
}

/** Um bloco do comentário, na ordem em que aparece. */
sealed class Block {
    class Paragraph(val spans: List<Span>) : Block()

    /** Item de lista: [marker] é `•` ou o número com ponto; [level] é o aninhamento, de 0. */
    class Item(val marker: String, val spans: List<Span>, val level: Int) : Block()

    class Quote(val spans: List<Span>) : Block()

    class Code(val lines: List<String>, val language: String) : Block()
}

/**
 * O Markdown que cabe num comentário de revisão: parágrafos, listas, citações, blocos de código e, dentro
 * do texto, negrito, itálico e código. É o suficiente para desenhar no editor o que o GitHub renderiza.
 */
object CommentMarkdown {

    private val SEVERITY = Regex("""^[^\p{L}\p{N}*`]*\*\*\s*([^*:]{1,24}?)\s*:?\s*\*\*\s*:?\s*""")
    private val ORDERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)""")
    private val BULLET = Regex("""^(\s*)[-*+•]\s+(.*)""")
    private val HEADING = Regex("""^#{1,6}\s+(.*)""")

    fun severity(text: String): Severity? = SEVERITY.find(text.trimStart())?.let { Severity.ofLabel(it.groupValues[1]) }

    /** O texto sem a gravidade do início, quando ela existe e é conhecida. */
    fun withoutSeverity(text: String): String {
        val trimmed = text.trimStart()
        val match = SEVERITY.find(trimmed) ?: return text
        if (Severity.ofLabel(match.groupValues[1]) == null) return text
        return trimmed.substring(match.range.last + 1)
    }

    /** A primeira frase sem marcação, para resumos de uma linha; sem a gravidade, que vira etiqueta. */
    fun plain(text: String, max: Int = Int.MAX_VALUE): String {
        val blocks = parse(withoutSeverity(text))
        val first = blocks.firstNotNullOfOrNull { block ->
            when (block) {
                is Block.Paragraph -> block.spans
                is Block.Item -> block.spans
                is Block.Quote -> block.spans
                is Block.Code -> block.lines.firstOrNull { it.isNotBlank() }?.let { listOf(Span(it.trim(), code = true)) }
            }?.takeIf { spans -> spans.any { it.text.isNotBlank() } }
        } ?: return ""
        val line = first.joinToString("") { it.text }.trim()
        val more = blocks.size > 1 || line.length > max
        return if (more && line.length > max) line.take(max).trimEnd() + "…" else if (more) "$line…" else line
    }

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(spans(paragraph.toString().trim()))
            paragraph.clear()
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.trimStart().startsWith("```") -> {
                    flush()
                    val fence = line.trimStart().takeWhile { it == '`' }
                    val language = line.trimStart().removePrefix(fence).trim()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        code += lines[i].trimEnd()
                        i++
                    }
                    blocks += Block.Code(code.dropLastWhile(String::isBlank), language)
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote.append(lines[i].trimStart().removePrefix(">").trim()).append(' ')
                        i++
                    }
                    blocks += Block.Quote(spans(quote.toString().trim()))
                    continue
                }
                ORDERED.matches(line) || BULLET.matches(line) -> {
                    flush()
                    val ordered = ORDERED.find(line)
                    val bullet = BULLET.find(line)
                    val indent = (ordered ?: bullet)!!.groupValues[1].replace("\t", "    ").length
                    val marker = ordered?.let { "${it.groupValues[2]}." } ?: "•"
                    val body = StringBuilder(ordered?.groupValues?.get(3) ?: bullet!!.groupValues[2])
                    i++
                    // Linhas seguintes recuadas continuam o item.
                    while (i < lines.size && lines[i].isNotBlank() && !ORDERED.matches(lines[i]) && !BULLET.matches(lines[i]) &&
                        !lines[i].trimStart().startsWith("```") && !lines[i].trimStart().startsWith(">") &&
                        lines[i].takeWhile(Char::isWhitespace).length > indent
                    ) {
                        body.append(' ').append(lines[i].trim())
                        i++
                    }
                    blocks += Block.Item(marker, spans(body.toString().trim()), indent / 2)
                    continue
                }
                HEADING.matches(line) -> {
                    flush()
                    blocks += Block.Paragraph(listOf(Span(HEADING.find(line)!!.groupValues[1].trim(), bold = true)))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flush()
        return blocks
    }

    /** Os trechos de [text] com negrito (`**`), itálico (`_` ou `*`) e código (crases). */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        fun flush() {
            if (buffer.isNotEmpty()) out += Span(buffer.toString(), bold, false, italic)
            buffer.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        out += Span(text.substring(i + 1, end), bold, true, italic)
                        i = end + 1
                        continue
                    }
                    buffer.append(c)
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    flush()
                    bold = !bold
                    i += 2
                    continue
                }
                (c == '*' || c == '_') && italicToggle(text, i, italic) -> {
                    flush()
                    italic = !italic
                }
                else -> buffer.append(c)
            }
            i++
        }
        flush()
        return out.filter { it.text.isNotEmpty() }
    }

    private fun italicToggle(text: String, i: Int, italic: Boolean): Boolean {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        return if (!italic) (before == null || !before.isLetterOrDigit()) && after != null && !after.isWhitespace()
        else before != null && !before.isWhitespace() && (after == null || !after.isLetterOrDigit())
    }

    /**
     * Quebra [spans] em linhas de até [width], medindo cada trecho com [measure]. Palavras maiores que a
     * largura ficam sozinhas na linha, sem serem partidas.
     */
    fun wrap(spans: List<Span>, width: Int, measure: (Span) -> Int): List<List<Span>> {
        val lines = mutableListOf<MutableList<Span>>()
        var line = mutableListOf<Span>()
        var used = 0
        fun breakLine() {
            lines += line
            line = mutableListOf()
            used = 0
        }
        for (span in spans) {
            val tokens = if (span.code) listOf(span.text) else Regex("""\S+\s*|\s+""").findAll(span.text).map { it.value }.toList()
            for (token in tokens) {
                val piece = span.with(token)
                val pieceWidth = measure(span.with(token.trimEnd()))
                if (line.isNotEmpty() && used + pieceWidth > width) {
                    breakLine()
                    if (token.isBlank()) continue
                }
                if (line.isEmpty() && token.isBlank()) continue
                val last = line.lastOrNull()
                if (last != null && last.bold == piece.bold && last.code == piece.code && last.italic == piece.italic) {
                    line[line.lastIndex] = last.with(last.text + piece.text)
                } else {
                    line += piece
                }
                used += measure(piece)
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line
        return lines.map { parts ->
            val kept = parts.dropLastWhile { it.text.isBlank() }
            kept.mapIndexed { index, part -> if (index == kept.lastIndex) part.with(part.text.trimEnd()) else part }
        }
    }
}
