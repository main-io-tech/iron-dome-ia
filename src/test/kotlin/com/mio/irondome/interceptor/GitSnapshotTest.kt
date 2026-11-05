package com.mio.irondome.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class GitSnapshotTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repo: File

    @Before
    fun gitIsInstalled() {
        assumeTrue(runCatching { ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        repo = temp.newFolder("repo")
    }

    @Test
    fun undoBringsBackWhatTheAiChangedOrDeletedAndRemovesWhatItCreatedWithoutTouchingTheUsersIndex() {
        git("init", "-q")
        write("a.txt", "a original")
        write("b.txt", "b original")
        write(".gitignore", "build/\n")
        git("add", ".")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
        write("c.txt", "c sem commit nem add")
        write("d.txt", "d adicionado")
        git("add", "d.txt")
        write("build/out.txt", "ignorado")
        val head = git("rev-parse", "HEAD")
        val statusBefore = git("status", "--porcelain")

        val snapshot = GitSnapshot.create(repo.toPath(), "Disparar")

        assertNotNull(snapshot)
        assertEquals("a foto não mexe no índice nem na pasta", statusBefore, git("status", "--porcelain"))
        assertEquals(head, git("rev-parse", "HEAD"))
        assertTrue(git("for-each-ref", "refs/iron-dome/antes-da-ia").contains(snapshot!!))

        // O que a IA faria: altera, apaga (inclusive o que não tinha commit), cria e adiciona ao índice.
        write("a.txt", "a quebrado pela IA")
        File(repo, "b.txt").delete()
        File(repo, "c.txt").delete()
        write("e.txt", "criado pela IA")
        write("f.txt", "criado e adicionado pela IA")
        git("add", "f.txt")
        write("build/out.txt", "ignorado alterado")

        GitSnapshot.restore(repo.toPath(), snapshot)

        assertEquals("a original", read("a.txt"))
        assertEquals("b original", read("b.txt"))
        assertEquals("c sem commit nem add", read("c.txt"))
        assertFalse("o que a IA criou sai", File(repo, "e.txt").exists() || File(repo, "f.txt").exists())
        assertEquals("o ignorado pelo .gitignore fica fora da foto", "ignorado alterado", read("build/out.txt"))
        assertEquals("o índice volta ao do usuário", statusBefore, git("status", "--porcelain"))
        assertEquals(head, git("rev-parse", "HEAD"))
    }

    @Test
    fun outsideAGitRepositoryThereIsNoSnapshot() {
        assertNull(GitSnapshot.create(temp.newFolder("sem-git").toPath(), "Disparar"))
    }

    @Test
    fun onlyTheLatestSnapshotsAreKept() {
        git("init", "-q")
        write("a.txt", "a")
        repeat(12) {
            write("a.txt", "versão $it")
            GitSnapshot.create(repo.toPath(), "tarefa $it")
            Thread.sleep(2)
        }
        assertEquals(10, git("for-each-ref", "refs/iron-dome/antes-da-ia").lines().count { it.isNotBlank() })
    }

    private fun write(path: String, text: String) = File(repo, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun read(path: String) = File(repo, path).readText()

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")}: $output" }
        return output.trim()
    }
}
