package com.mio.irondome.patrulha

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService
import java.nio.file.Files
import java.nio.file.Path

class PullRequestCardsTest : HeavyPlatformTestCase() {

    private lateinit var patrulha: PatrulhaService

    override fun setUp() {
        super.setUp()
        val base = Path.of(project.basePath!!).also(Files::createDirectories)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
        VfsTestUtil.createFile(root, "src/Frete.kt", "package frete\n\nfun novo() {}\nval total = itens.sum()\n")
        val record = PullRequestRecord(
            12, "Frete", "https://github.com/o/r/pull/12", "frete", "main",
            listOf(
                request("P1", startLine = 2, code = CodeStatus.CHANGED),
                request("P2", startLine = 9),
            ),
        )
        val file = base.resolve(ReviewCommentService.REVIEW_DIR).resolve(PullRequestRecord.DIR).resolve(PullRequestRecord.fileName(12))
        Files.createDirectories(file.parent)
        Files.writeString(file, ChangeRequestMarkdown.render(record))
        patrulha = PatrulhaService.getInstance(project)
    }

    fun testSentRequestsAppearWhereTheCodeIsNowWhileTheHistoryIsShown() {
        assertEmpty("sem o histórico ligado, nada no editor", cards())

        patrulha.setHistoryShown(12, true)
        val cards = cards().associateBy { it.comment.id }

        assertEquals("o trecho desceu uma linha desde o envio", 3..3, cards.getValue("P1").lines)
        assertEquals("Atendido", cards.getValue("P1").badge)
        assertEquals("Enviado", cards.getValue("P2").badge)
        assertTrue(cards.getValue("P1").subtitle.startsWith("Enviado ao PR #12 · Código: Alterado"))
        assertNotNull(cards.getValue("P1").onEdit)
    }

    fun testHiddenRequestsLeaveTheEditorAndCanComeBack() {
        patrulha.setHistoryShown(12, true)

        cards().first { it.comment.id == "P1" }.onHide()

        assertEquals(listOf("P2"), cards().map { it.comment.id })
        assertFalse(patrulha.isInEditor(12, "P1"))
        patrulha.setRequestsHidden(12, listOf("P1"), false)
        assertEquals(setOf("P1", "P2"), cards().map { it.comment.id }.toSet())
    }

    fun testTheHeaderExpandsAndCollapsesTheCard() {
        patrulha.setHistoryShown(12, true)
        assertFalse(cards().first().comment.expanded)

        cards().first().onToggle()

        assertTrue(cards().first().comment.expanded)
    }

    private fun cards() = PullRequestCards().cards(project, "src/Frete.kt", document())

    private fun document() = FileDocumentManager.getInstance().getDocument(
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(project.basePath!!, "src/Frete.kt"))!!,
    )!!

    private fun request(id: String, startLine: Int, code: CodeStatus = CodeStatus.NOT_CHECKED) = ChangeRequest(
        id = id, path = "src/Frete.kt", target = CommentTarget.LINES, startLine = startLine, endLine = startLine,
        snippet = if (id == "P1") "val total = itens.sum()" else "trecho que sumiu", text = "Considere o peso",
        commit = "abc", commentId = 1, url = null, sentAt = "2026-11-03T13:00:00", code = code,
    )
}
