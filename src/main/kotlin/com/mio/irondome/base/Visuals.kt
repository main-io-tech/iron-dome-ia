package com.mio.irondome.base

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

internal fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

/** Texto forte sobre fundo suave, para etiquetas, e [accent] para barras e destaques. */
class Tone(val foreground: Color, val background: Color, val accent: Color) {

    companion object {
        val GREEN = tone(0x116329, 0x56D364, 0xDAFBE1, 0x13361F, 0x2DA44E, 0x3FB950)
        val RED = tone(0xA40E26, 0xFF7B72, 0xFFEBE9, 0x4A1A1E, 0xCF222E, 0xF85149)
        val YELLOW = tone(0x7A5A00, 0xE3C065, 0xFFF1C2, 0x4A3D12, 0xD4A72C, 0xBB8009)
        val BLUE = tone(0x0550AE, 0x79C0FF, 0xDDF4FF, 0x12304F, 0x0969DA, 0x2F81F7)
        val PURPLE = tone(0x6639BA, 0xD2A8FF, 0xFBEFFF, 0x3A2350, 0x8250DF, 0xA371F7)
        val ORANGE = tone(0x953800, 0xFFA657, 0xFFF1E5, 0x4A2A12, 0xBC4C00, 0xDB6D28)
        val PINK = tone(0x99286E, 0xFF9BCE, 0xFFEFF7, 0x4A1A35, 0xBF3989, 0xDB61A2)
        val TEAL = tone(0x0A5A60, 0x56D4DD, 0xD8F5F7, 0x10373A, 0x1B7C83, 0x39C5CF)
        val GRAY = tone(0x57606A, 0x9198A1, 0xEAEEF2, 0x30363D, 0x8C959F, 0x6E7681)

        private val KEY_TONES = listOf(BLUE, PURPLE, TEAL, ORANGE, PINK, GREEN)

        /** A mesma chave sempre ganha a mesma cor, sem diferenciar maiúsculas. */
        fun forKey(key: String): Tone = KEY_TONES[Math.floorMod(key.lowercase().hashCode(), KEY_TONES.size)]

        /** Etiqueta na cor [color], com o fundo tingido por ela. */
        fun of(color: Color): Tone = Tone(color, JBColor(tint(color, 0.16f), tint(color, 0.22f)), color)

        private fun tint(color: Color, alpha: Float) = Color(color.red, color.green, color.blue, (alpha * 255).toInt())

        private fun tone(fgLight: Int, fgDark: Int, bgLight: Int, bgDark: Int, accentLight: Int, accentDark: Int) =
            Tone(JBColor(fgLight, fgDark), JBColor(bgLight, bgDark), JBColor(accentLight, accentDark))
    }
}

/** Etiqueta arredondada, como as de status e de chave. */
class Pill(private val text: String, private val tone: Tone, bold: Boolean = false) : JComponent() {

    init {
        font = JBFont.small().let { if (bold) it.asBold() else it }
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        return Dimension(metrics.stringWidth(text) + 2 * JBUI.scale(7), metrics.height + JBUI.scale(3))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            GraphicsUtil.setupAntialiasing(g2)
            val height = preferredSize.height.coerceAtMost(this.height)
            val top = (this.height - height) / 2
            g2.color = tone.background
            g2.fill(RoundRectangle2D.Float(0f, top.toFloat(), width.toFloat(), height.toFloat(), height.toFloat(), height.toFloat()))
            g2.font = font
            g2.color = tone.foreground
            val metrics = g2.fontMetrics
            g2.drawString(text, JBUI.scale(7), top + (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** Indicador do cabeçalho: um número em destaque e o que ele conta. */
class StatChip(private val count: String, private val label: String, private val tone: Tone) : JComponent() {

    private val countFont: Font = JBFont.label().asBold()
    private val labelFont: Font = JBFont.small()

    init {
        isOpaque = false
        toolTipText = "$count $label"
    }

    override fun getPreferredSize(): Dimension {
        val countMetrics = getFontMetrics(countFont)
        val labelMetrics = getFontMetrics(labelFont)
        val width = countMetrics.stringWidth(count) + gap() + labelMetrics.stringWidth(label) + 2 * padding()
        return Dimension(width, countMetrics.height + JBUI.scale(8))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            GraphicsUtil.setupAntialiasing(g2)
            val arc = JBUI.scale(10).toFloat()
            g2.color = tone.background
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            val center = height / 2
            g2.font = countFont
            g2.color = tone.foreground
            var metrics = g2.fontMetrics
            g2.drawString(count, padding(), center - metrics.height / 2 + metrics.ascent)
            val x = padding() + metrics.stringWidth(count) + gap()
            g2.font = labelFont
            metrics = g2.fontMetrics
            g2.drawString(label, x, center - metrics.height / 2 + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }

    private fun padding() = JBUI.scale(9)

    private fun gap() = JBUI.scale(5)
}

/** Caixa de aviso arredondada, com ícone e texto que quebra na largura disponível. */
class NoticeBox(icon: Icon, private val tone: Tone) : JPanel(BorderLayout(JBUI.scale(8), 0)) {

    private val message = JTextArea().apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
        foreground = tone.foreground
        border = null
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10)
        add(JBLabel(icon).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
        add(message, BorderLayout.CENTER)
    }

    var text: String
        get() = message.text
        set(value) {
            message.text = value
        }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(10).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            g2.color = tone.background
            g2.fill(shape)
            g2.color = tone.accent
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}

class Segment(val value: Int, val color: Color)

/** Barra arredondada dividida na proporção de cada [Segment], sobre um trilho neutro. */
class SegmentBar(private val segments: List<Segment>, private val preferredWidth: Int = 0, private val barHeight: Int = 6) : JComponent() {

    init {
        isOpaque = false
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(preferredWidth), JBUI.scale(barHeight) + JBUI.scale(2))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val height = JBUI.scale(barHeight).toFloat()
            val top = (this.height - height) / 2
            val track = RoundRectangle2D.Float(0f, top, width.toFloat(), height, height, height)
            g2.color = Tone.GRAY.background
            g2.fill(track)
            val total = segments.sumOf { it.value }
            if (total <= 0) return
            g2.clip(track)
            var x = 0f
            for (segment in segments.filter { it.value > 0 }) {
                val segmentWidth = width * segment.value / total.toFloat()
                g2.color = segment.color
                g2.fill(Rectangle2D.Float(x, top, segmentWidth + 0.5f, height))
                x += segmentWidth
            }
        } finally {
            g2.dispose()
        }
    }
}
