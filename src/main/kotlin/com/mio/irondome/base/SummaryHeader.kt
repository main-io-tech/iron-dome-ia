package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
/** Um número do cabeçalho; com [count] zero, o indicador não aparece. */
class Stat(val count: Int, val label: String, val tone: Tone)

/**
 * Cabeçalho das abas: o ícone do mecanismo num ladrilho colorido, o título com um ponto na cor do estado,
 * uma linha de contexto, os indicadores de cada estado, a barra com a proporção entre eles e, se houver,
 * um aviso. O fundo ganha um leve degradê na cor do estado e a linha de baixo acompanha essa cor.
 *
 * Minimizado, cabe numa linha: ícone, título e indicadores, com o contexto e o aviso no tooltip. A seta do canto
 * alterna, e a escolha vale para as abas de todos os mecanismos; o padrão é o cabeçalho completo.
 */
class SummaryHeader(icon: Icon, private val tone: Tone = Tone.GRAY) : JPanel(BorderLayout()) {

    private val tile = IconTile(icon, tone)
    private val smallTile = IconTile(icon, tone, scale = 1f, size = 22)
    private val dot = StatusDot()
    private val title = WrapText(font = JBFont.h4())
    private val subtitle = WrapText(font = JBFont.small(), color = UIUtil.getContextHelpForeground())
    private val chips = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply { isOpaque = false }
    private val barSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
    }
    private val banner = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }
    private val warningIcon = JBLabel(AllIcons.General.Warning).apply { isVisible = false }
    private val toggle = JBLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
            }
        })
    }

    /** A cor do estado atual: tinge o fundo e a linha de baixo. */
    private var accent: Color? = null
    private var subtitleText: String? = null
    private var warningText: String? = null

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        headers += this
        relayout()
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
        accent = titleColor
        dot.color = titleColor
        dot.isVisible = titleColor != null
        subtitleText = subtitle?.takeIf { it.isNotEmpty() }
        this.subtitle.text = subtitle.orEmpty()
        chips.removeAll()
        stats.filter { it.count > 0 }.forEach { chips.add(StatChip(it.count.toString(), it.label, it.tone)) }
        barSlot.removeAll()
        if (bar.any { it.value > 0 }) barSlot.add(SegmentBar(bar), BorderLayout.CENTER)
        warningText = warning
        warning?.let { banner.text = it }
        applyVisibility()
        revalidate()
        repaint()
    }

    private fun relayout() {
        removeAll()
        val expanded = expanded
        toggle.icon = if (expanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
        toggle.toolTipText = if (expanded) "Minimizar os cabeçalhos" else "Expandir os cabeçalhos"
        val corner = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toggle, BorderLayout.NORTH)
        }
        if (expanded) {
            border = JBUI.Borders.empty(12, 14, 10, 8)
            title.font = JBFont.h4()
            // Título e contexto quebram na largura do painel; o ponto acompanha a primeira linha do título.
            val titleLine = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(JBUI.scale(4))
                    add(dot, BorderLayout.NORTH)
                }, BorderLayout.WEST)
                add(title, BorderLayout.CENTER)
            }
            val body = JPanel(VerticalLayout(JBUI.scale(5), VerticalLayout.FILL)).apply {
                isOpaque = false
                add(JPanel(VerticalLayout(JBUI.scale(2), VerticalLayout.FILL)).apply {
                    isOpaque = false
                    add(titleLine)
                    add(subtitle)
                })
                add(chips)
                add(barSlot)
                add(banner)
            }
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyRight(12)
                add(tile, BorderLayout.NORTH)
            }, BorderLayout.WEST)
            add(body, BorderLayout.CENTER)
        } else {
            border = JBUI.Borders.empty(3, 8, 3, 6)
            title.font = JBFont.label().asBold()
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
                isOpaque = false
                add(smallTile)
                add(dot)
                add(title)
                add(warningIcon)
                add(chips)
            }, BorderLayout.CENTER)
        }
        add(corner, BorderLayout.EAST)
        applyVisibility()
        revalidate()
        repaint()
    }

    private fun applyVisibility() {
        val expanded = expanded
        subtitle.isVisible = expanded && subtitleText != null
        chips.isVisible = chips.componentCount > 0
        barSlot.isVisible = expanded && barSlot.componentCount > 0
        banner.isVisible = expanded && warningText != null
        warningIcon.isVisible = !expanded && warningText != null
        warningIcon.toolTipText = warningText
        title.toolTipText = if (expanded) null else listOfNotNull(subtitleText, warningText).joinToString("\n").ifEmpty { null }
    }

    companion object {
        private const val EXPANDED_KEY = "IronDome.Header.Expanded"
        private val headers = ContainerUtil.createWeakSet<SummaryHeader>()

        /** Os cabeçalhos completos, com o contexto, a barra e o aviso; vale para todas as abas abertas. */
        var expanded: Boolean
            get() = PropertiesComponent.getInstance().getBoolean(EXPANDED_KEY, true)
            set(value) {
                PropertiesComponent.getInstance().setValue(EXPANDED_KEY, value, true)
                headers.toList().forEach(SummaryHeader::relayout)
            }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            val color = accent ?: tone.accent
            g2.paint = GradientPaint(0f, 0f, tint(color, 0.10f), width.toFloat() * 0.7f, 0f, tint(color, 0f))
            g2.fillRect(0, 0, width, height)
            g2.color = JBColor.border()
            g2.fillRect(0, height - 1, width, 1)
            g2.color = tint(color, 0.55f)
            g2.fillRect(0, height - JBUI.scale(2), JBUI.scale(72), JBUI.scale(2))
        } finally {
            g2.dispose()
        }
    }

    private fun tint(color: Color, alpha: Float) = Color(color.red, color.green, color.blue, (alpha * 255).toInt())
}

/** O ícone do mecanismo, ampliado, sobre um quadrado arredondado na cor dele. */
class IconTile(icon: Icon, private val tone: Tone, scale: Float = 1.5f, private val size: Int = 38) : JComponent() {

    private val scaled = IconUtil.scale(icon, null, scale)

    init {
        isOpaque = false
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(size), JBUI.scale(size))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(11).toFloat()
            g2.color = tone.background
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.color = Color(tone.accent.red, tone.accent.green, tone.accent.blue, 70)
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
            scaled.paintIcon(this, g2, (width - scaled.iconWidth) / 2, (height - scaled.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}

/** Um ponto redondo na cor do estado, com um halo suave, ao lado do título. */
class StatusDot : JComponent() {

    var color: Color? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(12), JBUI.scale(12))

    override fun paintComponent(g: Graphics) {
        val color = color ?: return
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val halo = JBUI.scale(12).toFloat()
            val core = JBUI.scale(7).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            g2.color = Color(color.red, color.green, color.blue, 60)
            g2.fill(Ellipse2D.Float(cx - halo / 2, cy - halo / 2, halo, halo))
            g2.color = color
            g2.fill(Ellipse2D.Float(cx - core / 2, cy - core / 2, core, core))
        } finally {
            g2.dispose()
        }
    }
}
