package com.mio.irondome.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

class WrappingTest {

    private val font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    private val long = "Comentários de linha só valem nas linhas que o PR altera; os demais vão no arquivo inteiro ou no texto geral da revisão."

    @Test
    fun `texto longo quebra na largura dada e ganha altura`() {
        val oneLine = WrapText("x", font).fit(150).preferredSize.height
        val size = WrapText(long, font).fit(150).preferredSize
        assertTrue("largura ${size.width}", size.width <= 150)
        assertTrue("altura ${size.height}", size.height >= oneLine * 3)
    }

    @Test
    fun `texto curto fica com a largura natural`() {
        val size = WrapText("Radar", font).fit(400).preferredSize
        assertTrue(size.width < 100)
    }

    @Test
    fun `a linha passa as partes para baixo sem passar da largura`() {
        val row = FlowRow(160)
        repeat(4) { row.add(JLabel("Etiqueta $it")) }
        row.add(WrapText(long, font))
        val size = row.preferredSize
        assertTrue("largura ${size.width}", size.width <= 160)
        row.setSize(size)
        row.doLayout()
        row.components.forEach { assertTrue("${it.bounds}", it.x + it.width <= 160) }
    }

    @Test
    fun `o painel acompanha a largura do scroll em vez de alargar`() {
        val panel = FitWidthPanel(WrapText(long, font))
        val scroll = JScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.setSize(200, 300)
        scroll.validate()
        assertEquals(scroll.viewport.width, panel.width)
        assertTrue(panel.width <= 200)
    }
}
