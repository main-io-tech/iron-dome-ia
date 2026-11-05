package com.mio.irondome.base

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Texto que quebra na largura que recebe. Num painel, a altura acompanha a largura sozinha; num renderer de
 * lista ou árvore, que não dá largura ao componente, [fit] mede a altura para a largura disponível.
 */
class WrapText(text: String = "", font: Font = JBFont.label(), color: Color? = null) : JTextArea(text) {

    private var laidOutWidth = -1

    init {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        margin = JBUI.emptyInsets()
        this.font = font
        color?.let { foreground = it }
    }

    /** Fixa a largura em no máximo [maxWidth], sem esticar textos curtos, e a altura das linhas quebradas. */
    fun fit(maxWidth: Int): WrapText {
        setPreferredSize(null)
        val metrics = getFontMetrics(font)
        val natural = text.lines().maxOfOrNull { metrics.stringWidth(it) } ?: 0
        val width = minOf(natural + JBUI.scale(2), maxWidth.coerceAtLeast(JBUI.scale(MIN_WIDTH)))
        setSize(width, Short.MAX_VALUE.toInt())
        preferredSize = Dimension(width, super.getPreferredSize().height)
        return this
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width != laidOutWidth && isPreferredSizeSet.not()) {
            laidOutWidth = width
            revalidate()
        }
    }

    /** Sem [fit], a largura natural do texto não pode alargar o painel: vale a que ele já recebeu. */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        if (isPreferredSizeSet || width <= 0) return size
        return Dimension(minOf(size.width, width), size.height)
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), super.getMinimumSize().height)

    private companion object {
        const val MIN_WIDTH = 60
    }
}

/**
 * Linha de renderer que distribui as partes da esquerda para a direita e passa para a linha de baixo quando não
 * cabem em [maxWidth]; um [WrapText] ocupa o resto da linha e quebra nele.
 */
class FlowRow(private val maxWidth: Int, private val hgap: Int = JBUI.scale(6), private val vgap: Int = JBUI.scale(2)) : JPanel(null) {

    init {
        isOpaque = false
    }

    private fun place(apply: Boolean): Dimension {
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        val placed = mutableListOf<Pair<Component, Rectangle>>()
        val line = mutableListOf<Pair<Component, Rectangle>>()
        fun closeLine() {
            line.forEach { (c, r) -> r.y = y + (lineHeight - r.height) / 2; placed += c to r }
            line.clear()
            y += lineHeight + vgap
            widest = maxOf(widest, x - hgap)
            x = 0
            lineHeight = 0
        }
        for (child in components) {
            if (!child.isVisible) continue
            var size = child.preferredSize
            val remaining = maxWidth - x
            if (child is WrapText) {
                if (remaining < JBUI.scale(MIN_TEXT) && x > 0) closeLine()
                size = child.fit(maxWidth - x).preferredSize
            } else if (x > 0 && size.width > remaining) {
                closeLine()
            }
            line += child to Rectangle(x, 0, size.width, size.height)
            x += size.width + hgap
            lineHeight = maxOf(lineHeight, size.height)
        }
        if (line.isNotEmpty()) closeLine()
        if (apply) placed.forEach { (c, r) -> c.bounds = r }
        return Dimension(maxOf(widest, 0), maxOf(y - vgap, 0))
    }

    override fun getPreferredSize(): Dimension = place(apply = false)

    override fun doLayout() {
        place(apply = true)
    }

    private companion object {
        const val MIN_TEXT = 90
    }
}
