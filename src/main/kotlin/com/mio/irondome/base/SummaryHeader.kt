package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Um número do cabeçalho; com [count] zero, o indicador não aparece. */
class Stat(val count: Int, val label: String, val tone: Tone)

/**
 * Cabeçalho das abas: ícone, título, uma linha de contexto, os indicadores de cada estado, a barra com
 * a proporção entre eles e, se houver, um aviso.
 */
class SummaryHeader(icon: Icon) : JPanel(BorderLayout()) {

    private val title = JBLabel().apply { font = JBFont.h4() }
    private val subtitle = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val chips = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply { isOpaque = false }
    private val barSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
    }
    private val banner = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 12, 8, 12))
        val text = JPanel(VerticalLayout(JBUI.scale(2), SwingConstants.LEFT)).apply {
            isOpaque = false
            add(title)
            add(subtitle)
        }
        val body = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(text)
            add(chips)
            add(barSlot)
            add(banner)
        }
        add(JBLabel(IconUtil.scale(icon, null, 1.75f)).apply {
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.empty(2, 0, 0, 10)
        }, BorderLayout.WEST)
        add(body, BorderLayout.CENTER)
    }

    fun update(
        title: String,
        titleColor: Color? = null,
        subtitle: String? = null,
        stats: List<Stat> = emptyList(),
        bar: List<Segment> = emptyList(),
        warning: String? = null,
    ) {
        this.title.text = title
        this.title.foreground = titleColor ?: UIUtil.getLabelForeground()
        this.subtitle.text = subtitle.orEmpty()
        this.subtitle.isVisible = !subtitle.isNullOrEmpty()
        chips.removeAll()
        stats.filter { it.count > 0 }.forEach { chips.add(StatChip(it.count.toString(), it.label, it.tone)) }
        chips.isVisible = chips.componentCount > 0
        barSlot.removeAll()
        if (bar.any { it.value > 0 }) barSlot.add(SegmentBar(bar), BorderLayout.CENTER)
        barSlot.isVisible = barSlot.componentCount > 0
        banner.isVisible = warning != null
        warning?.let { banner.text = it }
        revalidate()
        repaint()
    }
}
