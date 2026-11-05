package com.mio.irondome.patrulha

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentRenderer
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
                thread(
                    90, "ana", "Isso pode ser nulo?", line = 4, startLine = 3,
                    replies = listOf(comment(91, "bia", "Pode, sim"), comment(92, "ana", "Então trata"), comment(93, "ana", "Com um `?:`")),
                ),
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
        assertEquals("o cartão leva só o primeiro comentário", "Isso pode ser nulo?", open.comment.text)
        assertEquals("as respostas vêm abaixo, na ordem", listOf("@bia", "@ana", "@ana"), open.replies.map { it.author })
        assertEquals(listOf("Pode, sim", "Então trata", "Com um `?:`"), open.replies.map { it.text })
        val details = open.replies.map { it.detail }
        assertTrue("diz a quem respondeu", details[0].startsWith("respondeu a @ana") && details[1].startsWith("respondeu a @bia"))
        assertTrue("a mesma pessoa em seguida continua", details[2].startsWith("continuou"))
        assertTrue("a posição na conversa", details.mapIndexed { i, it -> "resposta ${i + 1} de 3" in it }.all { it })
        assertTrue("3 respostas" in open.subtitle)
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

    fun testTheEditorMinimizesHidesAndBringsBackEveryPullRequestComment() {
        val radar = project.service<ReviewCommentService>()
        radar.startPullRequestReview(12)
        patrulha.setShownInEditor(12, true)
        val file = FileDocumentManager.getInstance().getFile(document())!!
        val draft = radar.addComment(file, document(), 2..2, "Rascunho", discuss = false)!!
        try {
            assertTrue("a conversa aberta e o rascunho começam expandidos", patrulha.hasExpandedInEditor(12))

            patrulha.setAllExpandedInEditor(12, false)
            assertFalse(patrulha.hasExpandedInEditor(12))
            assertTrue(cards().none { it.comment.expanded })
            assertTrue("o rascunho também minimiza", draft.minimized)
            radar.toggleExpanded(draft)
            assertFalse("o cabeçalho abre só ele", draft.minimized)

            patrulha.setAllExpandedInEditor(12, true)
            assertTrue(cards().all { it.comment.expanded })
            assertTrue("o pedido enviado expande junto", requestCards().single().comment.expanded)

            patrulha.setAllShownInEditor(12, false)
            assertFalse(patrulha.isAnyShownInEditor(12))
            assertEmpty(cards())
            assertEmpty(requestCards())
            assertTrue("o rascunho sai do editor", radar.pullRequestDraftsHidden)
            patrulha.setAllShownInEditor(12, true)
            assertEquals(2, cards().size)
            assertFalse(radar.pullRequestDraftsHidden)

            cards().first { it.comment.id == "@ana" }.onHide()
            assertEquals(1, patrulha.hiddenInEditor(12))
            patrulha.setAllShownInEditor(12, false)
            patrulha.showHiddenInEditor(12)
            assertEquals(0, patrulha.hiddenInEditor(12))
            assertEquals("trazer os ocultados liga os cartões de novo", 2, cards().size)

            radar.setPullRequestDraftsHidden(true)
            radar.addComment(file, document(), 3..3, "Outro", discuss = false)
            assertFalse("comentar de novo traz os rascunhos de volta", radar.pullRequestDraftsHidden)
        } finally {
            radar.stopPullRequestReview()
        }
    }

    fun testThePullRequestCommentsOnlyAppearInTheEditorDuringTheReview() {
        val radar = project.service<ReviewCommentService>()
        val editor = EditorFactory.getInstance().createEditor(document(), project, EditorKind.MAIN_EDITOR)
        try {
            fun drawn() = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength, ReviewCommentRenderer::class.java)
                .map { it.renderer.comment.text }

            radar.startPullRequestReview(12)
            patrulha.setShownInEditor(12, true)
            val file = FileDocumentManager.getInstance().getFile(document())!!
            radar.addComment(file, document(), 2..2, "Rascunho", discuss = false)!!
            radar.render(editor)
            assertTrue("na revisão: o rascunho, o pedido enviado e as conversas", drawn().containsAll(listOf("Rascunho", "Meu pedido", "Isso pode ser nulo?")))

            radar.stopPullRequestReview()
            patrulha.setShownInEditor(12, false)
            radar.render(editor)
            assertEmpty("encerrar a revisão tira todos, inclusive os não enviados", drawn())

            radar.startPullRequestReview(12)
            patrulha.setShownInEditor(12, true)
            radar.render(editor)
            assertTrue("revisar de novo traz de volta", "Rascunho" in drawn())
        } finally {
            radar.stopPullRequestReview()
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testTheIdeGitHubReviewModeIsOffDuringTheReviewAndComesBackAfter() {
        val settings = project.getService(Class.forName("org.jetbrains.plugins.github.pullrequest.config.GithubPullRequestsProjectUISettings"))
        fun enabled() = settings.javaClass.getMethod("getEditorReviewEnabled").invoke(settings) as Boolean
        settings.javaClass.getMethod("setEditorReviewEnabled", Boolean::class.javaPrimitiveType).invoke(settings, true)

        IdeEditorReview.suspend(project)
        assertFalse("na revisão da Patrulha, o modo revisão do GitHub não mostra os comentários com Editar e Excluir", enabled())
        assertEquals(true, patrulha.ideEditorReviewBefore)

        IdeEditorReview.suspend(project)
        IdeEditorReview.restore(project)
        assertTrue("encerrar devolve como estava", enabled())
        assertNull(patrulha.ideEditorReviewBefore)
    }

    private fun cards() = PullRequestDiscussionCards().cards(project, "src/Frete.kt", document())

    private fun requestCards() = PullRequestCards().cards(project, "src/Frete.kt", document())

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
