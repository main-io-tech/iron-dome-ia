package com.mio.irondome.base

import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.ListModel
import javax.swing.tree.TreeModel

/**
 * O estado vazio das abas: acima do texto do `emptyText`, o ícone do mecanismo ampliado sobre um ladrilho na
 * cor dele, para a tela vazia não ser só uma frase cinza no meio do nada.
 */
internal object EmptyState {

    private const val SCALE = 2f
    private const val TILE = 56
    private const val GAP = 14

    fun paint(owner: JComponent, status: StatusText, icon: Icon, tone: Tone, g: Graphics) {
        val size = status.preferredSize
        if (size.width == 0 && size.height == 0) return
        val below = status.pointBelow
        val tile = JBUI.scale(TILE)
        val top = below.y - size.height - JBUI.scale(GAP) - tile
        if (top < JBUI.scale(4)) return
        paintTile(g, owner, icon, tone, (owner.width - tile) / 2, top, tile, SCALE)
    }

    /** O ícone ampliado por [scale], centrado num quadrado arredondado de lado [size] na cor de [tone]. */
    fun paintTile(g: Graphics, owner: JComponent, icon: Icon, tone: Tone, x: Int, y: Int, size: Int, scale: Float) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = (size * 0.3f)
            g2.color = tone.background
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), arc, arc))
            g2.color = Color(tone.accent.red, tone.accent.green, tone.accent.blue, 70)
            g2.draw(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, size - 1f, size - 1f, arc, arc))
            val scaled = IconUtil.scale(icon, owner, scale)
            scaled.paintIcon(owner, g2, x + (size - scaled.iconWidth) / 2, y + (size - scaled.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}

/** Árvore de uma aba: linhas com a altura do conteúdo e o estado vazio com o ícone do mecanismo. */
open class MechanismTree(model: TreeModel, private val icon: Icon, private val tone: Tone) : Tree(model) {

    init {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (isEmpty) EmptyState.paint(this, emptyText, icon, tone, g)
    }
}

/** Lista de uma aba, com o estado vazio com o ícone do mecanismo. */
open class MechanismList<T>(model: ListModel<T>, private val icon: Icon, private val tone: Tone) : JBList<T>(model) {

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (isEmpty) EmptyState.paint(this, emptyText, icon, tone, g)
    }
}
