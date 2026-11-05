package com.mio.irondome.radar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.VfsTestUtil
import java.nio.file.Files
import java.nio.file.Path

class MarkTargetActionTest : HeavyPlatformTestCase() {

    private lateinit var file: VirtualFile

    override fun setUp() {
        super.setUp()
        val base = Path.of(project.basePath!!).also(Files::createDirectories)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
        file = VfsTestUtil.createFile(root, "src/loja/Pedido.kt", "class Pedido\n")
    }

    fun testActionIsInTheContextMenus() {
        val manager = ActionManager.getInstance()
        assertNotNull(manager.getAction("IronDome.MarkTarget"))
        for (group in listOf("ProjectViewPopupMenu", "EditorTabPopupMenu", "EditorPopupMenu")) {
            val ids = (manager.getAction(group) as DefaultActionGroup).childActionsOrStubs.map(manager::getId)
            assertTrue("falta no $group: $ids", "IronDome.MarkTarget" in ids)
        }
    }

    fun testVisibleForFileAndPackage() {
        assertEquals("Iron Dome IA: Marcar Arquivo Inteiro como Alvo", update(file))
        assertEquals("Iron Dome IA: Marcar Pacote como Alvo", update(file.parent))
    }

    /** Como no projeto com `.iml` em `.idea` e um módulo Maven: a pasta do módulo do projeto é a `.idea`. */
    fun testVisibleWhenTheProjectModuleLivesInIdeaFolder() {
        val idea = VfsTestUtil.createDir(file.parent.parent.parent, ".idea")
        assertEquals(project.name, module.name)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.forEach(model::removeContentEntry)
            model.addContentEntry(idea)
        }
        createModule("backend")

        assertEquals("src/loja/Pedido.kt", project.service<ReviewCommentService>().targetPath(file))
        assertEquals("Iron Dome IA: Marcar Pacote como Alvo", update(file.parent))
    }

    private fun update(target: VirtualFile): String? {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, target)
            .build()
        val event = TestActionEvent.createTestEvent(MarkTargetAction(), context)
        MarkTargetAction().update(event)
        return event.presentation.text.takeIf { event.presentation.isEnabledAndVisible }
    }
}
