package com.mio.irondome.base

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.TreeCellRenderer

/**
 * Linha de árvore montada com componentes: ícone, textos, etiquetas arredondadas e barras na primeira
 * linha e, opcionalmente, um texto de apoio na segunda. A árvore precisa de `rowHeight = 0` para as
 * linhas terem a altura do conteúdo.
 */
abstract class RichTreeCellRenderer : TreeCellRenderer {

    private val panel = JPanel(VerticalLayout(JBUI.scale(1))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 0)
    }

    protected abstract fun describe(row: Row, value: Any?)

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        panel.removeAll()
        panel.toolTipText = null
        val line = JPanel(HorizontalLayout(JBUI.scale(6), SwingConstants.CENTER)).apply { isOpaque = false }
        val content = Row(line, RenderingUtil.getForeground(tree, selected))
        describe(content, value)
        panel.add(line)
        content.detail?.let(panel::add)
        panel.toolTipText = content.tooltip
        return panel
    }

    class Row internal constructor(private val line: JPanel, private val foreground: Color) {
        internal var detail: SimpleColoredComponent? = null
        internal var tooltip: String? = null
        private var hasIcon = false

        fun icon(icon: Icon?) {
            if (icon == null) return
            line.add(JBLabel(icon))
            hasIcon = true
        }

        fun text(text: String, attributes: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES) {
            line.add(coloredText().apply { append(text, attributes) })
        }

        fun pill(text: String, tone: Tone, bold: Boolean = false) {
            line.add(Pill(text, tone, bold))
        }

        fun bar(segments: List<Segment>, width: Int = 56) {
            line.add(SegmentBar(segments, width, barHeight = 5))
        }

        /** Texto de apoio numa segunda linha, alinhado com o texto da primeira. */
        fun detail(text: String, attributes: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES) {
            val component = detail ?: coloredText().also {
                it.border = JBUI.Borders.emptyLeft(if (hasIcon) JBUI.scale(16) + JBUI.scale(6) else 0)
                detail = it
            }
            component.append(text, attributes)
        }

        fun tooltip(text: String?) {
            tooltip = text
        }

        private fun coloredText() = SimpleColoredComponent().apply {
            isOpaque = false
            ipad = JBUI.emptyInsets()
            font = UIUtil.getTreeFont()
            foreground = this@Row.foreground
        }
    }
}
