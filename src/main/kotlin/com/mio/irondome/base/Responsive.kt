package com.mio.irondome.base

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** Com o painel estreito, os botões descem para outra linha no lugar de sumirem atrás do ». */
fun ActionToolbar.wrapping(): ActionToolbar = apply { layoutStrategy = ToolbarLayoutStrategy.WRAP_STRATEGY }

/**
 * Dentro de uma rolagem, acompanha a largura visível: textos e linhas de botões quebram no lugar de abrir a
 * rolagem horizontal e esconder o que passa da borda. A rolagem vertical continua.
 */
class FitWidthPanel(content: JComponent) : JPanel(BorderLayout()), Scrollable {

    init {
        isOpaque = false
        add(content, BorderLayout.CENTER)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = UNIT

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth() = true

    override fun getScrollableTracksViewportHeight() = false

    private companion object {
        const val UNIT = 16
    }
}
