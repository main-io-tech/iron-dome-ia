package com.mio.irondome.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentMarkdownTest {

    private val comment = """
        **Bug:** carrinho vazio derruba o checkout

        **O que acontece:** `total` começa com `itens.first()`, mas a lista
        pode vir vazia.

        **Como corrigir:**
        1. Troque por `sumOf`.
        2. Adicione um teste.

        ```kotlin
        val total = itens.sumOf { it.preco }
        ```

        > **Conceito:** `sumOf` soma o valor de cada item.
    """.trimIndent()

    @Test
    fun severityComesFromTheBoldPrefixAndLeavesTheBody() {
        assertEquals(Severity.BUG, CommentMarkdown.severity(comment))
        assertEquals(Severity.WARNING, CommentMarkdown.severity("**Atenção**: pode falhar"))
        assertEquals(Severity.SUGGESTION, CommentMarkdown.severity("🔧 **Sugestão:** renomear"))
        assertNull(CommentMarkdown.severity("**Nota:** sem gravidade conhecida"))
        assertNull(CommentMarkdown.severity("texto comum"))

        assertTrue(CommentMarkdown.withoutSeverity(comment).startsWith("carrinho vazio derruba o checkout"))
        assertEquals("**Nota:** fica", CommentMarkdown.withoutSeverity("**Nota:** fica"))
    }

    @Test
    fun blocksFollowTheMarkdownStructure() {
        val blocks = CommentMarkdown.parse(CommentMarkdown.withoutSeverity(comment))

        assertEquals(
            listOf("Paragraph", "Paragraph", "Paragraph", "Item", "Item", "Code", "Quote"),
            blocks.map { it::class.simpleName },
        )
        val what = blocks[1] as Block.Paragraph
        assertEquals("linhas seguidas viram um parágrafo só", "O que acontece: total começa com itens.first(), mas a lista pode vir vazia.", what.spans.joinToString("") { it.text })
        assertTrue("o rótulo fica em negrito", what.spans.first().bold && what.spans.first().text == "O que acontece:")
        assertTrue("o código fica marcado", what.spans.any { it.code && it.text == "total" })
        val step = blocks[3] as Block.Item
        assertEquals("1.", step.marker)
        assertEquals(0, step.level)
        val code = blocks[5] as Block.Code
        assertEquals(listOf("val total = itens.sumOf { it.preco }"), code.lines)
        assertEquals("kotlin", code.language)
        val concept = blocks[6] as Block.Quote
        assertTrue(concept.spans.first().bold && concept.spans.first().text == "Conceito:")
    }

    @Test
    fun bulletsHeadingsAndItalicsAreUnderstood() {
        val blocks = CommentMarkdown.parse("# Título\n- um `x`\n  continua\n  - dois\n* três _ênfase_ fim\n\ntexto com snake_case e 2*3")

        assertTrue((blocks[0] as Block.Paragraph).spans.single().let { it.bold && it.text == "Título" })
        val first = blocks[1] as Block.Item
        assertEquals("•", first.marker)
        assertEquals("um x continua", first.spans.joinToString("") { it.text })
        assertEquals("item recuado é aninhado", 1, (blocks[2] as Block.Item).level)
        val third = blocks[3] as Block.Item
        assertTrue(third.spans.any { it.italic && it.text == "ênfase" })
        assertEquals("texto com snake_case e 2*3", (blocks[4] as Block.Paragraph).spans.joinToString("") { it.text })
    }

    @Test
    fun plainSummaryDropsTheMarkupAndTheSeverity() {
        assertEquals("carrinho vazio derruba o checkout…", CommentMarkdown.plain(comment))
        assertEquals("carrinho vazio…", CommentMarkdown.plain(comment, 15))
        assertEquals("só uma frase com código", CommentMarkdown.plain("só uma **frase** com `código`"))
        assertEquals("", CommentMarkdown.plain("   "))
    }

    @Test
    fun wrapBreaksBetweenWordsAndKeepsStyles() {
        val spans = listOf(Span("O que acontece:", bold = true), Span(" a lista pode vir vazia e "), Span("itens.first()", code = true), Span(" falha."))

        val lines = CommentMarkdown.wrap(spans, 24) { it.text.length }

        assertEquals(
            listOf("O que acontece: a lista", "pode vir vazia e", "itens.first() falha."),
            lines.map { line -> line.joinToString("") { it.text } },
        )
        assertTrue("o negrito sobrevive à quebra", lines[0].first().bold)
        assertTrue("o código continua marcado", lines[2].first().code)
        assertTrue("nenhuma linha começa com espaço", lines.none { it.first().text.startsWith(" ") })
        assertEquals("palavra maior que a largura fica sozinha", 1, CommentMarkdown.wrap(listOf(Span("supercalifragilístico")), 5) { it.text.length }.size)
    }
}
