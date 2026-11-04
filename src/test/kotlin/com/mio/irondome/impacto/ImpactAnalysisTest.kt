package com.mio.irondome.impacto

import com.mio.irondome.patrulha.ChangedFile
import com.mio.irondome.radar.CommentTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactAnalysisTest {

    private fun node(name: String, path: String, level: Int = 1) =
        ClassNode("com.app.$name", name, "com.app", ClassKind.CLASS, null, path, level, 2, 1, TestUsage.NONE)

    private val frete = node("Frete", "src/main/kotlin/com/app/Frete.kt", level = 0)
    private val pedido = node("Pedido", "src/main/kotlin/com/app/Pedido.kt")
    private val nota = node("Nota", "src/main/kotlin/com/app/fiscal/Nota.kt")
    private val graph = DependencyGraph(
        frete.qualifiedName,
        Direction.BOTH,
        1,
        listOf(frete, pedido, nota),
        listOf(Edge(pedido.qualifiedName, frete.qualifiedName, EdgeKind.USAGE)),
        truncated = false,
    )

    private val radar = ImpactComment("C1", CommentSource.RADAR, frete.filePath, CommentTarget.LINES, 10, 12, "Arredondar o valor")
    private val pacote = ImpactComment("P1", CommentSource.PATRULHA, "src/main/kotlin/com/app/fiscal", CommentTarget.PACKAGE, 0, 0, "Renomear o pacote", sent = true)

    @Test
    fun commentsBelongToTheClassOfTheirFileOrPackage() {
        val byClass = ImpactComments.byClass(graph.nodes, listOf(radar, pacote))

        assertEquals(listOf(radar), byClass[frete.qualifiedName])
        assertEquals(listOf(pacote), byClass[nota.qualifiedName])
        assertNull("sem comentário, a classe fica de fora", byClass[pedido.qualifiedName])
        assertEquals("linhas 10–12", radar.location)
    }

    @Test
    fun theInstructionsListClassesCommentsAndChangesAndForbidChanges() {
        val text = ImpactAnalysis.instructions(
            graph,
            ImpactComments.byClass(graph.nodes, listOf(radar, pacote)),
            mapOf(pedido.qualifiedName to FileChange.MODIFIED),
            "diff --git a/Pedido.kt b/Pedido.kt\n+val x = 1",
        )

        assertTrue("**Não altere nenhum arquivo.**" in text)
        assertTrue("`com.app.Frete` (analisada, 1 comentário(s))" in text)
        assertTrue("### C1 · Radar · `${frete.filePath}`, linhas 10–12" in text)
        assertTrue("### P1 · Patrulha, já enviado ao PR" in text)
        assertTrue("- Pedido usa Frete" in text)
        assertTrue("- `Pedido` (`${pedido.filePath}`): modificada" in text)
        assertTrue("+val x = 1" in text)
    }

    @Test
    fun theInstructionsBringCommentsOutsideTheDiagramAndTheReviewedPullRequest() {
        val outside = ImpactComment("P2", CommentSource.PATRULHA, "src/main/kotlin/com/app/Cliente.kt", CommentTarget.LINES, 3, 3, "Validar o CPF")
        val pullRequest = ReviewedPullRequest(
            42,
            listOf(
                ChangedFile("src/main/kotlin/com/app/Leitor.kt", "added", "@@ -0,0 +1 @@\n+class Leitor", 1, 0),
                ChangedFile(pedido.filePath, "modified", "@@ -1 +1 @@\n-val a = 1\n+val a = 2", 1, 1),
            ),
        )

        val text = ImpactAnalysis.instructions(
            graph,
            ImpactComments.byClass(graph.nodes, listOf(radar, outside)),
            emptyMap(),
            "",
            outside = listOf(radar, outside),
            pullRequest = pullRequest,
        )

        assertTrue("O pull request #42 está em revisão" in text)
        assertTrue("## Comentários pendentes em classes fora do diagrama (1)" in text)
        assertTrue("### P2 · Patrulha · `src/main/kotlin/com/app/Cliente.kt`, linha 3" in text)
        assertEquals("o comentário do diagrama não se repete fora dele", 1, Regex("### C1 ").findAll(text).count())
        assertTrue("`com.app.Pedido` (alterada no PR #42)" in text)
        assertTrue("- `${pedido.filePath}`: alterado, +1 −1, classe do diagrama" in text)
        assertTrue("o diff das classes do diagrama vem primeiro", text.indexOf("+val a = 2") in 0 until text.indexOf("+class Leitor"))
    }

    @Test
    fun theAnswerKeepsOnlyClassesOfTheDiagramFromTheMostToTheLeastAffected() {
        val answer = """
            Pronto:
            ```json
            {"summary": "Mudar o arredondamento afeta o Pedido.", "impacts": [
              {"class": "com.app.Nota", "level": "low", "reason": "Só conferir", "sources": ["P1"]},
              {"class": "Pedido", "level": "HIGH", "reason": "Soma os fretes", "sources": ["C1", "alteração em Pedido"]},
              {"class": "com.app.Pedido", "level": "MEDIUM", "reason": "Repetido", "sources": []},
              {"class": "com.app.Inexistente", "level": "HIGH", "reason": "Fora do diagrama", "sources": []}
            ]}
            ```
        """.trimIndent()

        val report = ImpactAnalysis.parse(answer, graph)!!

        assertEquals("Mudar o arredondamento afeta o Pedido.", report.summary)
        assertEquals(listOf(pedido.qualifiedName, nota.qualifiedName), report.impacts.map { it.qualifiedName })
        assertEquals(ImpactLevel.HIGH, report.of(pedido.qualifiedName)!!.level)
        assertEquals(listOf("C1", "alteração em Pedido"), report.of(pedido.qualifiedName)!!.sources)
        assertEquals(ImpactLevel.LOW, report.of(nota.qualifiedName)!!.level)
        assertNull(ImpactAnalysis.parse("Não consegui analisar.", graph))
    }
}
