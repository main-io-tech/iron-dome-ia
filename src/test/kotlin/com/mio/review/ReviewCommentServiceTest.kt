package com.mio.review

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import com.intellij.util.xmlb.XmlSerializer
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

    fun testCommentIdsAreUniqueInNewReview() {
        val ids = listOf(comment(first, "um"), comment(second, "dois"), comment(first, "três")).map { it.id }

        assertEquals(listOf("C1", "C2", "C3"), ids)
    }

    private fun comment(file: VirtualFile, text: String): ReviewComment {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        return service.addComment(file, document, 0..0, text, discuss = false)!!
    }

    private fun reviewFile(): Path =
        Path.of(project.basePath!!, ReviewCommentService.REVIEW_DIR, ReviewCommentService.reviewFileName(service.activeReviewId))
}
