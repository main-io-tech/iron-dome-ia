package com.mio.irondome.radar

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import com.intellij.util.xmlb.XmlSerializer
import com.mio.irondome.comando.ReviewMarkdown
import java.nio.file.Files
import java.nio.file.Path

class ReviewCommentServiceTest : HeavyPlatformTestCase() {

    private lateinit var service: ReviewCommentService
    private lateinit var first: VirtualFile
    private lateinit var second: VirtualFile

    override fun setUp() {
        super.setUp()
        val base = Path.of(project.basePath!!).also(Files::createDirectories)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
        first = VfsTestUtil.createFile(root, "src/First.kt", "val a = 1\nval b = 2\n")
        second = VfsTestUtil.createFile(root, "src/Second.kt", "val c = 3\nval d = 4\n")
        service = project.service<ReviewCommentService>()
    }

    fun testSavingOneFileKeepsOtherFilesPending() {
        val inFirst = comment(first, "corrigir a")
        val inSecond = comment(second, "corrigir c")

        service.exportFile(first)

        val markdown = Files.readString(reviewFile())
        assertTrue(markdown.contains("src/First.kt"))
        assertFalse(markdown.contains("src/Second.kt"))
        assertFalse(service.isUnsaved(inFirst))
        assertTrue(service.isUnsaved(inSecond))
        assertEquals(setOf("src/Second.kt"), service.pendingPaths())
    }

    fun testSavingOneFileKeepsTheSavedVersionOfOthers() {
        val inFirst = comment(first, "corrigir a")
        val inSecond = comment(second, "versão salva")
        service.exportAll()
        service.update(inSecond, "versão nova", discuss = false)
        service.markForRemoval(listOf(inFirst))

        service.exportFile(second)

        val markdown = Files.readString(reviewFile())
        assertTrue(markdown.contains("versão nova"))
        assertTrue("o removido só sai quando o arquivo dele for salvo", markdown.contains("corrigir a"))
        assertTrue(service.isMarkedForRemoval(inFirst))
    }

    fun testUnsavedCommentsSurviveRestart() {
        comment(first, "salvo")
        service.exportAll()
        comment(second, "rascunho")

        service.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(service.state), ReviewState::class.java))

        val byText = service.activeComments().associateBy { it.text }
        assertFalse(service.isUnsaved(byText.getValue("salvo")))
        assertTrue(service.isDraft(byText.getValue("rascunho")))
        assertTrue(service.hasPendingChanges())
        assertTrue(service.hasPendingChanges(second))
        assertFalse(service.hasPendingChanges(first))
    }

    fun testLegacyStateCountsCommentsInMarkdownAsSaved() {
        comment(first, "salvo")
        service.exportAll()
        comment(second, "rascunho")
        val legacy = XmlSerializer.deserialize(XmlSerializer.serialize(service.state), ReviewState::class.java)
        legacy.comments.forEach { it.exported = null }

        service.loadState(legacy)

        val byText = service.activeComments().associateBy { it.text }
        assertFalse(service.isUnsaved(byText.getValue("salvo")))
        assertTrue(service.isDraft(byText.getValue("rascunho")))
    }

    fun testWholeFileAndPackageCommentsGoToMarkdown() {
        comment(first, "corrigir a")
        service.addTargetComment(first, "renomear o arquivo", discuss = false)
        service.addTargetComment(first.parent, "separar o pacote", discuss = true)

        service.exportAll()

        val markdown = Files.readString(reviewFile())
        assertTrue(markdown.contains("## Pacote `src/`"))
        assertTrue(markdown.contains("C2 — arquivo inteiro"))
        assertTrue(markdown.contains("C3 — pacote inteiro"))
        val parsed = ReviewMarkdown.parse(markdown).associateBy { it.id }
        assertEquals(CommentTarget.LINES to "src/First.kt", parsed.getValue("C1").let { it.target to it.filePath })
        assertEquals(CommentTarget.FILE to "src/First.kt", parsed.getValue("C2").let { it.target to it.filePath })
        assertEquals(CommentTarget.PACKAGE to "src", parsed.getValue("C3").let { it.target to it.filePath })
        assertTrue(parsed.getValue("C3").discuss)
    }

    fun testAiResolvesPackageComment() {
        val packageComment = service.addTargetComment(first.parent, "separar o pacote", discuss = false)!!
        comment(first, "corrigir a")
        service.exportAll()
        val file = reviewFile()
        Files.writeString(file, Files.readString(file).replace("### [ ] ${packageComment.id}", "### [x] ${packageComment.id}"))

        service.importStatus(listOf(file.toString()))

        assertTrue(packageComment.resolved)
        assertEquals(mapOf("src/First.kt" to 1), service.openCounts)
    }

    fun testCommentIdsAreUniqueInNewReview() {
        val ids = listOf(comment(first, "um"), comment(second, "dois"), comment(first, "três")).map { it.id }

        assertEquals(listOf("C1", "C2", "C3"), ids)
    }

    fun testConsolidateHistoryMergesClosedReviewsAndKeepsTheOpenOne() {
        comment(first, "primeira revisão")
        service.addTargetComment(first, "discutir o nome", discuss = true)
        service.exportAll()
        val firstReview = reviewFile()
        resolveAll(firstReview)
        comment(second, "revisão aberta")
        service.exportAll()
        val openReview = reviewFile()

        val archive = service.consolidateHistory()!!

        assertFalse(Files.exists(firstReview))
        assertTrue(Files.exists(openReview))
        val markdown = Files.readString(archive)
        assertTrue(markdown.contains("# Revisão: ${ReviewCommentService.reviewName(1)}"))
        assertFalse("o histórico não leva as instruções para a IA", markdown.contains("Instruções para a IA"))
        assertFalse(markdown.contains("IA: discuta antes de alterar"))
        assertTrue("a contagem vem do [x] marcado pela IA", markdown.contains("Pendentes: 0 de 2."))
        val entries = service.archivedReviews()
        assertEquals(listOf(ReviewCommentService.reviewName(1)), entries.map { it.name })
        assertEquals(
            setOf("primeira revisão" to true, "discutir o nome" to true),
            entries.single().comments.map { it.text to it.resolved }.toSet(),
        )
        assertTrue(service.historyReviews().isEmpty())
    }

    fun testConsolidatingAgainKeepsWhatWasAlreadyConsolidated() {
        comment(first, "um")
        service.exportAll()
        resolveAll(reviewFile())
        service.consolidateHistory()
        comment(second, "dois")
        service.exportAll()
        resolveAll(reviewFile())

        service.consolidateHistory()

        assertEquals(listOf(ReviewCommentService.reviewName(2), ReviewCommentService.reviewName(1)), service.archivedReviews().map { it.name })
        assertTrue(service.savedReviews().isEmpty())
    }

    fun testNewReviewSkipsIdsAlreadyConsolidated() {
        comment(first, "um")
        service.exportAll()
        resolveAll(reviewFile())
        service.consolidateHistory()
        service.loadState(ReviewState())

        comment(second, "depois de clonar o projeto")

        assertEquals(2, service.activeReviewId)
    }

    fun testClearHistoryKeepsTheOpenReview() {
        comment(first, "um")
        service.exportAll()
        resolveAll(reviewFile())
        service.consolidateHistory()
        comment(second, "dois")
        service.exportAll()
        resolveAll(reviewFile())
        comment(first, "aberta")
        service.exportAll()
        val open = reviewFile()

        assertEquals(2, service.clearHistory())

        assertTrue(service.archivedReviews().isEmpty())
        assertFalse(Files.exists(service.archiveFile()!!))
        assertEquals(listOf(open), service.savedReviews().map { it.second })
    }

    /** A IA marcou todos com `[x]`: a revisão se encerra. */
    private fun resolveAll(file: Path) {
        Files.writeString(file, Files.readString(file).replace("### [ ]", "### [x]"))
        service.importStatus(listOf(file.toString()))
        assertEquals(0, service.activeReviewId)
    }

    fun testHiddenResolvedCommentsCanBeShownAgainAfterTheReviewEnds() {
        val resolved = comment(first, "interceptado")
        service.exportAll()
        val file = reviewFile()
        resolveAll(file)

        service.hide(listOf(resolved))
        comment(second, "abre a próxima revisão")

        assertTrue("ocultar não tira do estado", resolved in service.hiddenComments())
        assertTrue(service.resolvedComments().isEmpty())
        service.show(service.hiddenComments())
        assertFalse(resolved.dismissed)
        assertEquals(listOf(resolved), service.resolvedComments())
    }

    fun testRestoreBringsBackResolvedCommentsThatLeftTheState() {
        val resolved = comment(first, "interceptado")
        service.exportAll()
        val file = reviewFile()
        resolveAll(file)
        val saved = ReviewMarkdown.parse(Files.readString(file))
        service.remove(listOf(resolved))

        service.restore(1, saved)
        service.restore(1, saved)

        val restored = service.findComment(1, resolved.id)!!
        assertTrue(restored.resolved)
        assertEquals("interceptado", restored.text)
        assertEquals("restaurar de novo não duplica", 1, service.resolvedComments().size)
    }

    fun testPullRequestCommentsStayOutOfTheAiReview() {
        val forAi = comment(first, "para a IA")
        service.startPullRequestReview(12)
        val forPr = comment(second, "para o PR")
        service.stopPullRequestReview()

        service.exportAll()

        assertEquals(12, forPr.pullRequest)
        assertEquals(0, forPr.reviewId)
        assertTrue(forPr.id.startsWith("P"))
        assertEquals(listOf(forAi), service.activeComments())
        assertEquals(listOf(forPr), service.pullRequestComments(12))
        assertFalse(Files.readString(reviewFile()).contains("para o PR"))
        assertTrue("rascunho de PR não pede disparo para a IA", service.pendingPaths().isEmpty())
    }

    fun testOnlyPullRequestCommentsKeepSuggestedCode() {
        val forAi = comment(first, "para a IA", suggestion = "val a = 10")
        service.startPullRequestReview(12)
        val forPr = comment(second, "para o PR", suggestion = "val c = 30\n")
        val unchanged = comment(second, "sem mudança", suggestion = "val c = 3")

        assertEquals("", forAi.suggestion)
        assertEquals("val c = 30", forPr.suggestion)
        assertEquals("sugestão igual ao trecho não sugere nada", "", unchanged.suggestion)

        service.update(forPr, "para o PR", discuss = false, suggestion = "")
        assertEquals("", forPr.suggestion)
        service.stopPullRequestReview()
    }

    private fun comment(file: VirtualFile, text: String, suggestion: String = ""): ReviewComment {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        return service.addComment(file, document, 0..0, text, discuss = false, suggestion = suggestion)!!
    }

    private fun reviewFile(): Path =
        Path.of(project.basePath!!, ReviewCommentService.REVIEW_DIR, ReviewCommentService.reviewFileName(service.activeReviewId))
}
