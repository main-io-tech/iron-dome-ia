package com.mio.irondome.base

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.font.TextAttribute
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * Linha de árvore montada com componentes: ícone, textos, etiquetas arredondadas e barras na primeira linha e,
 * opcionalmente, um texto de apoio embaixo. As partes passam para a linha de baixo e os textos quebram na
 * largura da aba, sem rolagem lateral. A árvore precisa de `rowHeight = 0` para as linhas terem a altura do conteúdo.
 */
abstract class RichTreeCellRenderer : TreeCellRenderer {

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
        val depth = (value as? DefaultMutableTreeNode)?.level ?: 1
        val width = ((tree as? MechanismTree)?.rowWidth(depth) ?: tree.width).coerceAtLeast(JBUI.scale(MIN_WIDTH))
        val panel = JPanel(VerticalLayout(JBUI.scale(1))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 0)
        }
        val content = Row(FlowRow(width), width, RenderingUtil.getForeground(tree, selected), selected)
        describe(content, value)
        panel.add(content.line)
        content.detail?.let(panel::add)
        panel.toolTipText = content.tooltip
        return panel
    }

    class Row internal constructor(
        internal val line: FlowRow,
        private val width: Int,
        private val foreground: Color,
        private val selected: Boolean,
    ) {
        internal var detail: JPanel? = null
        internal var tooltip: String? = null
        private var hasIcon = false

        fun icon(icon: Icon?) {
            if (icon == null) return
            line.add(JBLabel(icon))
            hasIcon = true
        }

        fun text(text: String, attributes: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES) {
            if (text.isEmpty()) return
            line.add(WrapText(text, font(attributes), color(attributes)))
        }

        /**
         * Trechos com estilos diferentes num texto só, sem o espaço que fica entre componentes, como código com
         * fundo no meio da frase. O trecho inteiro passa para a linha de baixo quando não cabe.
         */
        fun styled(fragments: List<Pair<String, SimpleTextAttributes>>) {
            if (fragments.isEmpty()) return
            line.add(
                SimpleColoredComponent().apply {
                    isOpaque = false
                    ipad = JBUI.emptyInsets()
                    font = UIUtil.getTreeFont()
                    foreground = this@Row.foreground
                    fragments.forEach { (text, attributes) -> append(text, attributes) }
                },
            )
        }

        fun pill(text: String, tone: Tone, bold: Boolean = false) {
            line.add(Pill(text, tone, bold))
        }

        fun bar(segments: List<Segment>, width: Int = 56) {
            line.add(SegmentBar(segments, width, barHeight = 5))
        }

        /** Texto de apoio embaixo, alinhado com o texto da primeira linha. */
        fun detail(text: String, attributes: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES) {
            val indent = if (hasIcon) JBUI.scale(16) + JBUI.scale(6) else 0
            detail = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(indent)
                add(WrapText(text, font(attributes, JBFont.small()), color(attributes)).fit(width - indent), BorderLayout.WEST)
            }
        }

        fun tooltip(text: String?) {
            tooltip = text
        }

        private fun font(attributes: SimpleTextAttributes, base: Font = if (attributes.isSmaller) JBFont.small() else UIUtil.getTreeFont()): Font {
            val bold = attributes.style and SimpleTextAttributes.STYLE_BOLD != 0
            val italic = attributes.style and SimpleTextAttributes.STYLE_ITALIC != 0
            val styled = base.deriveFont((if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0))
            return if (attributes.isStrikeout) styled.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON)) else styled
        }

        private fun color(attributes: SimpleTextAttributes): Color = if (selected) foreground else attributes.fgColor ?: foreground
    }

    private companion object {
        const val MIN_WIDTH = 120
    }
}
