package com.mio.irondome.patrulha

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangedFileTreeTest {

    private fun file(path: String, additions: Int = 1, deletions: Int = 0) = ChangedFile(path, "modified", null, additions, deletions)

    @Test
    fun groupsByFolderCompactingFoldersWithASingleChild() {
        val root = ChangedFileTree.build(
            listOf(
                file("src/main/kotlin/com/mio/patrulha/Panel.kt", 10, 2),
                file("README.md"),
                file("src/main/kotlin/com/mio/base/Icons.kt", 3, 1),
                file("src/main/kotlin/com/mio/patrulha/Api.kt"),
                file("src/test/kotlin/com/mio/PanelTest.kt", 5),
            ),
        )

        assertEquals("", root.path)
        assertEquals(listOf("README.md"), root.files.map { it.path })
        val src = root.folders.single()
        assertEquals("src", src.name)
        assertEquals(listOf("main/kotlin/com/mio", "test/kotlin/com/mio"), src.folders.map { it.name })

        val main = src.folders.first()
        assertEquals("src/main/kotlin/com/mio", main.path)
        assertEquals(listOf("base", "patrulha"), main.folders.map { it.name })
        assertEquals(listOf("Api.kt", "Panel.kt"), main.folders.last().files.map { it.path.substringAfterLast('/') })
        assertEquals(14, main.additions)
        assertEquals(3, main.deletions)
        assertEquals(5, root.allFiles.size)
    }

    @Test
    fun aSingleDeepFolderBecomesOneLine() {
        val root = ChangedFileTree.build(listOf(file("a/b/c/X.kt"), file("a/b/c/Y.kt")))

        assertEquals(listOf("a/b/c"), root.folders.map { it.name })
        assertEquals(listOf("X.kt", "Y.kt"), root.folders.single().files.map { it.path.substringAfterLast('/') })
    }
}
