package com.mio.irondome.patrulha

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService
import java.nio.file.Files
import java.nio.file.Path

class PullRequestDiscussionCardsTest : HeavyPlatformTestCase() {

    private lateinit var patrulha: PatrulhaService

    override fun setUp() {
        super.setUp()
        val base = Path.of(project.basePath!!).also(Files::createDirectories)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
        VfsTestUtil.createFile(root, "src/Frete.kt", "package frete\n\nfun novo() {}\nval total = itens.sum()\n")
        val record = PullRequestRecord(
            12, "Frete", "https://github.com/o/r/pull/12", "frete", "main",
            listOf(
                ChangeRequest(
                    id = "P1", path = "src/Frete.kt", target = CommentTarget.LINES, startLine = 4, endLine = 4,
                    snippet = "val total = itens.sum()", text = "Meu pedido", commit = "abc", commentId = 77, url = null,
                    sentAt = "2026-11-03T13:00:00",
                ),
            ),
        )
        val file = base.resolve(ReviewCommentService.REVIEW_DIR).resolve(PullRequestRecord.DIR).resolve(PullRequestRecord.fileName(12))
        Files.createDirectories(file.parent)
        Files.writeString(file, ChangeRequestMarkdown.render(record))
        patrulha = PatrulhaService.getInstance(project)
        patrulha.rememberDiscussions(
            12,
            listOf(
                thread(77, "eu", "Meu pedido", line = 4),
                thread(90, "ana", "Isso pode ser nulo?", line = 4, startLine = 3, replies = listOf(comment(91, "bia", "Pode, sim"))),
                thread(95, "caio", "Arquivo grande demais", line = null, resolved = true),
                thread(96, "ana", "Em outro arquivo", line = 1, path = "src/Peso.kt"),
                Discussion(DiscussionKind.CONVERSATION, listOf(comment(100, "ana", "Pode subir hoje?"))),
            ),
        )
    }

    fun testOtherPeoplesThreadsAppearReadOnlyOnlyWhileThePullRequestIsInReview() {
        patrulha.setDiscussionsShown(12, true)
        assertEmpty("fora da revisão do PR, nada no editor", cards())

        project.service<ReviewCommentService>().startPullRequestReview(12)
        patrulha.setShownInEditor(12, true)
        val cards = cards().associateBy { it.comment.text.lineSequence().first() }

        assertEquals("o pedido do .md aparece como pedido, não como conversa", setOf("Isso pode ser nulo?", "Arquivo grande demais"), cards.keys)
        val open = cards.getValue("Isso pode ser nulo?")
        assertEquals("@ana", open.comment.id)
        assertEquals("linhas 3 e 4 do GitHub, 0-based", 2..3, open.lines)
        assertNull("só leitura: sem Editar", open.onEdit)
        assertEquals("Aberta", open.badge)
        assertTrue(open.comment.expanded)
        assertTrue("a resposta vem abaixo", "**@bia respondeu**" in open.comment.text && "Pode, sim" in open.comment.text)
        assertTrue("só leitura" in open.subtitle)

        val file = cards.getValue("Arquivo grande demais")
        assertNull("o comentário do arquivo inteiro fica no topo", file.lines)
        assertEquals(CommentTarget.FILE, file.comment.target)
        assertFalse("resolvida começa recolhida", file.comment.expanded)

        project.service<ReviewCommentService>().stopPullRequestReview()
        patrulha.setShownInEditor(12, false)
        assertEmpty("encerrar a revisão tira os cartões", cards())
    }

    fun testHiddenThreadsLeaveTheEditorAndTheHeaderTogglesTheCard() {
        project.service<ReviewCommentService>().startPullRequestReview(12)
        patrulha.setShownInEditor(12, true)

        cards().first { it.comment.id == "@ana" }.onToggle()
        assertFalse(cards().first { it.comment.id == "@ana" }.comment.expanded)

        cards().first { it.comment.id == "@ana" }.onHide()
        assertEquals(listOf("@caio"), cards().map { it.comment.id })

        val hidden = patrulha.editorDiscussions(12).first { it.first.id == 90L }
        assertFalse(patrulha.isDiscussionInEditor(12, hidden))
        patrulha.setDiscussionHidden(12, hidden, false)
        assertEquals(setOf("@ana", "@caio"), cards().map { it.comment.id }.toSet())
    }

    private fun cards() = PullRequestDiscussionCards().cards(project, "src/Frete.kt", document())

    private fun document() = FileDocumentManager.getInstance().getDocument(
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(project.basePath!!, "src/Frete.kt"))!!,
    )!!

    private fun thread(
        id: Long,
        author: String,
        body: String,
        line: Int?,
        startLine: Int? = null,
        resolved: Boolean = false,
        path: String = "src/Frete.kt",
        replies: List<DiscussionComment> = emptyList(),
    ) = Discussion(
        kind = DiscussionKind.THREAD,
        comments = listOf(comment(id, author, body)) + replies,
        nodeId = "T$id",
        path = path,
        line = line,
        startLine = startLine,
        resolved = resolved,
    )

    private fun comment(id: Long, author: String, body: String) = DiscussionComment(id, author, body, "2026-11-03T13:00:00Z")
}
