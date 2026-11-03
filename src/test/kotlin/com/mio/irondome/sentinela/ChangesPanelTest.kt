package com.mio.irondome.sentinela

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ChangesPanelTest : LightJavaCodeInsightFixtureTestCase() {

    fun testFilesInTheSamePackageShareTheGroup() {
        val service = myFixture.addFileToProject("app/core/Service.java", "package app.core; public class Service { }").virtualFile.parent
        val helper = myFixture.addFileToProject("app/core/Helper.java", "package app.core; class Helper { }").virtualFile.parent

        val location = locationOf(service)
        assertEquals("app.core", location.name)
        assertNotNull("arquivos de c\u00f3digo mostram a raiz de onde v\u00eam", location.root)
        assertEquals(location.key, locationOf(helper).key)
    }

    fun testOtherPackagesGetTheirOwnGroup() {
        val core = myFixture.addFileToProject("app/core/Service.java", "package app.core; public class Service { }").virtualFile.parent
        val web = myFixture.addFileToProject("app/web/Controller.java", "package app.web; public class Controller { }").virtualFile.parent

        assertEquals("app.web", locationOf(web).name)
        assertFalse(locationOf(core).key == locationOf(web).key)
    }

    fun testDefaultPackage() {
        val dir = myFixture.addFileToProject("Main.java", "public class Main { }").virtualFile.parent

        assertEquals("(pacote padr\u00e3o)", locationOf(dir).name)
    }

    fun testDeletedFolderFallsBackToItsPath() {
        val base = project.basePath!!.replace('\\', '/')

        val location = ChangesPanel.locationOf(project, "$base/docs/antigos", null)
        assertEquals("docs/antigos", location.name)
        assertNull(location.root)
        assertEquals("(raiz do projeto)", ChangesPanel.locationOf(project, base, null).name)
    }

    fun testFilterSearchesPathAndPackageIgnoringCase() {
        val filter = ChangesPanel.Filter(text = "RADAR")

        assertTrue(filter.matches("src/main/kotlin/app/radar/Gutter.kt", "app.radar", ChangesPanel.ChangeKind.MODIFIED, 0))
        assertTrue(filter.matches("src/Gutter.kt", "app.radar", ChangesPanel.ChangeKind.MODIFIED, 0))
        assertFalse(filter.matches("src/main/kotlin/app/web/Controller.kt", "app.web", ChangesPanel.ChangeKind.MODIFIED, 0))
    }

    fun testFilterHidesKindsAndFilesWithoutTargets() {
        val hidden = ChangesPanel.Filter(hiddenKinds = setOf(ChangesPanel.ChangeKind.UNVERSIONED))
        assertFalse(hidden.matches("a/Novo.kt", "a", ChangesPanel.ChangeKind.UNVERSIONED, 0))
        assertTrue(hidden.matches("a/Velho.kt", "a", ChangesPanel.ChangeKind.MODIFIED, 0))

        val onlyTargets = ChangesPanel.Filter(onlyTargets = true)
        assertFalse(onlyTargets.matches("a/Sem.kt", "a", ChangesPanel.ChangeKind.MODIFIED, 0))
        assertTrue(onlyTargets.matches("a/Com.kt", "a", ChangesPanel.ChangeKind.MODIFIED, 2))
    }

    fun testEmptyFilterIsInactiveAndShowsEverything() {
        val filter = ChangesPanel.Filter(text = "   ")

        assertFalse(filter.active)
        assertTrue(filter.matches("qualquer/Arquivo.kt", "qualquer", ChangesPanel.ChangeKind.OTHER, 0))
    }

    private fun locationOf(dir: VirtualFile) = ChangesPanel.locationOf(project, dir.path, dir)
}
