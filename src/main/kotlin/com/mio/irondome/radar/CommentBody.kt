package com.mio.irondome.radar

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D

/** As fontes do corpo de um cartão: a da interface para o texto e a do editor para código. */
class BodyFonts(editor: Editor) {
    private val component = editor.contentComponent
    val regular: Font = JBFont.label()
    val bold: Font = JBFont.label().asBold()
    val italic: Font = JBFont.label().asItalic()
    val boldItalic: Font = JBFont.label().asBold().asItalic()
    val code: Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    /** Código no meio do texto acompanha o tamanho da fonte da interface. */
    val inlineCode: Font = code.deriveFont(regular.size2D)
    val marker: Font = JBFont.small().asBold()

    val regularMetrics: FontMetrics = component.getFontMetrics(regular)
    val codeMetrics: FontMetrics = component.getFontMetrics(code)
    val markerMetrics: FontMetrics = component.getFontMetrics(marker)
    val lineHeight: Int = maxOf(regularMetrics.height, component.getFontMetrics(inlineCode).height) + JBUI.scale(4)
    val codeLineHeight: Int = codeMetrics.height

    fun font(span: Span): Font = when {
        span.code -> inlineCode
        span.bold && span.italic -> boldItalic
        span.bold -> bold
        span.italic -> italic
        else -> regular
    }

    fun metrics(span: Span): FontMetrics = component.getFontMetrics(font(span))

    /** Largura do trecho na linha; código ganha o respiro da etiqueta. */
    fun width(span: Span): Int = metrics(span).stringWidth(span.text) + if (span.code) 2 * CommentBody.chipPadding() else 0
}

/** As cores do corpo, que mudam com o estado do cartão. */
class BodyPalette(
    val foreground: Color,
    val muted: Color,
    /** Rótulos de seção, como `**O que acontece:**` no início de um parágrafo. */
    val label: Color,
    val bullet: Color,
    val codeBackground: Color,
    val border: Color,
    val strikeout: Boolean = false,
)

/**
 * O corpo de um comentário diagramado numa largura: parágrafos com quebra de linha, listas com marcador,
 * citações com barra lateral, código em etiqueta no texto e em caixa quando é um bloco.
 */
class CommentBody(blocks: List<Block>, private val fonts: BodyFonts, width: Int) {

    private sealed class Row(val height: Int) {
        class Text(
            val spans: List<Span>,
            val indent: Int,
            val marker: String?,
            val quote: Boolean,
            val sectionLabel: Boolean,
            height: Int,
        ) : Row(height)

        class Code(val lines: List<String>, height: Int) : Row(height)

        class Gap(height: Int) : Row(height)
    }

    private val rows: List<Row>

    /** Altura total, em pixels. */
    val height: Int

    /** A largura que o conteúdo ocupa de fato, até a largura pedida. */
    val naturalWidth: Int

    init {
        val list = mutableListOf<Row>()
        var previous: Block? = null
        for (block in blocks) {
            if (previous != null) list += Row.Gap(if (previous is Block.Item && block is Block.Item) itemGap() else paragraphGap())
            when (block) {
                is Block.Paragraph -> list += textRows(block.spans, 0, null, quote = false, width)
                is Block.Item -> list += textRows(block.spans, listIndent() * (block.level + 1), block.marker, quote = false, width)
                is Block.Quote -> list += textRows(block.spans, quoteIndent(), null, quote = true, width)
                is Block.Code -> list += Row.Code(block.lines.ifEmpty { listOf("") }, 2 * codePadding() + block.lines.size.coerceAtLeast(1) * fonts.codeLineHeight)
            }
            previous = block
        }
        rows = list
        height = rows.sumOf { it.height }
        naturalWidth = rows.maxOfOrNull { row ->
            when (row) {
                is Row.Text -> row.indent + row.spans.sumOf(fonts::width)
                is Row.Code -> row.lines.maxOf { fonts.codeMetrics.stringWidth(it.expandTabs()) } + 2 * codePadding()
                is Row.Gap -> 0
            }
        }?.coerceAtMost(width) ?: 0
    }

    private fun textRows(spans: List<Span>, indent: Int, marker: String?, quote: Boolean, width: Int): List<Row> {
        val lines = CommentMarkdown.wrap(spans, (width - indent).coerceAtLeast(JBUI.scale(80)), fonts::width)
        val first = spans.firstOrNull()
        val sectionLabel = first != null && first.bold && !first.code && first.text.trimEnd().endsWith(":")
        return lines.mapIndexed { index, line ->
            Row.Text(line, indent, marker.takeIf { index == 0 }, quote, sectionLabel && index == 0, fonts.lineHeight)
        }
    }

    fun paint(g2: Graphics2D, x: Int, top: Int, width: Int, palette: BodyPalette) {
        var y = top
        var quoteStart = -1
        for ((index, row) in rows.withIndex()) {
            val inQuote = row is Row.Text && row.quote
            if (inQuote && quoteStart < 0) quoteStart = y
            if (!inQuote && quoteStart >= 0) {
                paintQuoteBar(g2, x, quoteStart, y, palette)
                quoteStart = -1
            }
            when (row) {
                is Row.Text -> paintText(g2, row, x, y, palette)
                is Row.Code -> paintCode(g2, row, x, y, width, palette)
                is Row.Gap -> Unit
            }
            y += row.height
            if (index == rows.lastIndex && quoteStart >= 0) paintQuoteBar(g2, x, quoteStart, y, palette)
        }
    }

    private fun paintText(g2: Graphics2D, row: Row.Text, x: Int, top: Int, palette: BodyPalette) {
        val left = x + row.indent
        val center = top + row.height / 2
        row.marker?.let { marker ->
            if (marker == "•") {
                val size = JBUI.scale(5)
                g2.color = palette.bullet
                g2.fill(Ellipse2D.Float((left - JBUI.scale(12)).toFloat(), (center - size / 2).toFloat(), size.toFloat(), size.toFloat()))
            } else {
                g2.font = fonts.marker
                g2.color = palette.bullet
                val width = fonts.markerMetrics.stringWidth(marker)
                g2.drawString(marker, left - width - JBUI.scale(6), center - fonts.markerMetrics.height / 2 + fonts.markerMetrics.ascent)
            }
        }
        var cursor = left
        for ((index, span) in row.spans.withIndex()) {
            val metrics = fonts.metrics(span)
            val baseline = center - metrics.height / 2 + metrics.ascent
            g2.font = fonts.font(span)
            if (span.code) {
                val chipWidth = fonts.width(span)
                val chipHeight = metrics.height + JBUI.scale(2)
                val chipTop = center - chipHeight / 2
                val arc = JBUI.scale(5)
                g2.color = palette.codeBackground
                g2.fillRoundRect(cursor, chipTop, chipWidth, chipHeight, arc, arc)
                g2.color = palette.border
                g2.drawRoundRect(cursor, chipTop, chipWidth, chipHeight, arc, arc)
                g2.color = palette.foreground
                g2.drawString(span.text, cursor + chipPadding(), baseline)
                cursor += chipWidth
            } else {
                g2.color = when {
                    row.quote -> palette.muted
                    index == 0 && row.sectionLabel -> palette.label
                    else -> palette.foreground
                }
                g2.drawString(span.text, cursor, baseline)
                cursor += metrics.stringWidth(span.text)
            }
        }
        if (palette.strikeout && cursor > left) {
            g2.color = palette.muted
            val strikeY = center + JBUI.scale(1)
            g2.drawLine(left, strikeY, cursor, strikeY)
        }
    }

    private fun paintQuoteBar(g2: Graphics2D, x: Int, top: Int, bottom: Int, palette: BodyPalette) {
        g2.color = palette.bullet
        val width = JBUI.scale(3)
        g2.fillRoundRect(x + JBUI.scale(2), top + JBUI.scale(2), width, bottom - top - JBUI.scale(4), width, width)
    }

    private fun paintCode(g2: Graphics2D, row: Row.Code, x: Int, top: Int, width: Int, palette: BodyPalette) {
        val arc = JBUI.scale(6)
        g2.color = palette.codeBackground
        g2.fillRoundRect(x, top, width, row.height, arc, arc)
        g2.color = palette.border
        g2.drawRoundRect(x, top, width, row.height, arc, arc)
        g2.font = fonts.code
        g2.color = palette.foreground
        var baseline = top + codePadding() + fonts.codeMetrics.ascent
        for (line in row.lines) {
            g2.drawString(line.expandTabs(), x + codePadding(), baseline)
            baseline += fonts.codeLineHeight
        }
    }

    private fun String.expandTabs() = replace("\t", "    ")

    companion object {
        fun chipPadding() = JBUI.scale(4)

        fun codePadding() = JBUI.scale(8)

        fun paragraphGap() = JBUI.scale(7)

        fun itemGap() = JBUI.scale(2)

        fun listIndent() = JBUI.scale(18)

        fun quoteIndent() = JBUI.scale(14)
    }
}
