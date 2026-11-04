package com.mio.irondome.base

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.util.xmlb.XmlSerializer
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewState
import java.nio.file.Files
import java.nio.file.Path

/** Dois projetos na mesma janela, cada um num módulo em pasta própria: o Iron Dome vigia um de cada vez. */
class IronDomeRootTest : HeavyPlatformTestCase() {

    private lateinit var loja: VirtualFile
    private lateinit var frete: VirtualFile
    private lateinit var pagamentos: VirtualFile
    private lateinit var cobranca: VirtualFile

    override fun setUp() {
        super.setUp()
        val base = Files.createDirectories(Path.of(project.basePath!!))
        val workspace = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.parent)!!
        loja = VfsTestUtil.createDir(workspace, "loja-${base.fileName}")
        pagamentos = VfsTestUtil.createDir(workspace, "pagamentos-${base.fileName}")
        frete = VfsTestUtil.createFile(loja, "src/Frete.kt", "val frete = 10\n")
        cobranca = VfsTestUtil.createFile(pagamentos, "src/Cobranca.kt", "val cobranca = 1\n")
        PsiTestUtil.addContentRoot(module, loja)
        VfsTestUtil.createDir(loja, "modulo-interno").also { PsiTestUtil.addContentRoot(module, it) }
        val second = runWriteAction {
            ModuleManager.getInstance(project).newModule(pagamentos.toNioPath().resolve("pagamentos.iml"), EmptyModuleType.EMPTY_MODULE)
        }
        PsiTestUtil.addContentRoot(second, pagamentos)
    }

    override fun tearDown() {
        try {
            listOf(loja, pagamentos).forEach { dir -> runCatching { dir.toNioPath().toFile().deleteRecursively() } }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testEachTopLevelFolderIsAProjectToChoose() {
        val names = IronDomeRoot.getInstance(project).candidates().map { it.path }

        assertContainsElements(names, loja.path, pagamentos.path)
        assertDoesntContain("a pasta dentro de outra não conta", names, "${loja.path}/modulo-interno")
        assertEquals(listOf("/a", "/b"), IronDomeRoot.topLevel(listOf("/a", "/a/sub", "/b", "/b/")))
    }

    fun testEachProjectKeepsItsOwnComments() {
        val root = IronDomeRoot.getInstance(project)
        val radar = project.service<ReviewCommentService>()
        assertNull(root.select(loja.path))
        assertEquals(loja.path, project.rootPath())
        comment(frete, "frete fixo")
        assertEquals(listOf("src/Frete.kt"), radar.activeComments().map { it.filePath })

        assertNull(root.select(pagamentos.path))

        assertEquals(pagamentos.path, project.rootPath())
        assertEmpty("os comentários da loja ficaram guardados", radar.activeComments())
        assertNull("o arquivo da loja não é do projeto escolhido", radar.relativePath(frete))
        comment(cobranca, "cobrança sem teste")
        assertEquals(listOf("src/Cobranca.kt"), radar.activeComments().map { it.filePath })

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(radar.state), ReviewState::class.java)
        radar.loadState(restored)
        assertNull(root.select(loja.path))

        assertEquals("guardados também depois de reabrir a IDE", listOf("frete fixo"), radar.activeComments().map { it.text })
        assertNull(root.select(pagamentos.path))
        assertEquals(listOf("cobrança sem teste"), radar.activeComments().map { it.text })
    }

    fun testCannotSwitchDuringAPullRequestReview() {
        val root = IronDomeRoot.getInstance(project)
        assertNull(root.select(loja.path))
        project.service<ReviewCommentService>().startPullRequestReview(12)

        val refused = root.select(pagamentos.path)

        assertTrue(refused!!, refused.contains("PR #12"))
        assertEquals(loja.path, project.rootPath())
    }

    private fun comment(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertNotNull(project.service<ReviewCommentService>().addComment(file, document, 0..0, text, discuss = false))
    }
}
