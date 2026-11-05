package com.mio.irondome.patrulha

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** O descarte antes do checkout do PR, com o `git` da máquina num repositório temporário. */
class DiscardLocalChangesTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        assumeTrue("precisa do git no PATH", runCatching { run(Path.of("."), "--version") }.isSuccess)
        root = Files.createTempDirectory("iron-dome-discard")
        git("init", "-q")
        git("config", "user.email", "teste@iron-dome")
        git("config", "user.name", "Teste")
        write("src/Frete.kt", "val frete = 10\n")
        write("src/Pedido.kt", "val pedido = 1\n")
        write(".gitignore", "*.log\n")
        git("add", ".")
        git("commit", "-q", "-m", "inicial")
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }

    @Test
    fun discardsChangesWithoutStashAndKeepsReviewsIdeaAndIgnored() {
        write("src/Frete.kt", "val frete = 99\n")
        Files.delete(root.resolve("src/Pedido.kt"))
        write("src/Novo.kt", "val novo = 1\n")
        git("add", "src/Novo.kt")
        write("src/rascunho/Ideia.kt", "val ideia = 1\n")
        write("iron-dome/ordem-interceptacao-1.md", "# revisão\n")
        write(".idea/workspace.xml", "<project/>\n")
        write("build.log", "log\n")

        PatrulhaService.discard(root, PatrulhaService.workPaths(emptySet())) { args -> git(*args.toTypedArray()) }

        assertEquals("val frete = 10\n", read("src/Frete.kt"))
        assertEquals("val pedido = 1\n", read("src/Pedido.kt"))
        assertFalse("o novo que estava no index é apagado", Files.exists(root.resolve("src/Novo.kt")))
        assertFalse("a pasta nova vazia também sai", Files.exists(root.resolve("src/rascunho")))
        assertTrue("as revisões ficam", Files.exists(root.resolve("iron-dome/ordem-interceptacao-1.md")))
        assertTrue("as configurações da IDE ficam", Files.exists(root.resolve(".idea/workspace.xml")))
        assertTrue("o ignorado pelo .gitignore fica", Files.exists(root.resolve("build.log")))
        assertEquals("", git("stash", "list"))
        assertEquals(listOf("?? .idea/", "?? iron-dome/"), git("status", "--porcelain").lines().filter { it.isNotBlank() }.sorted())
    }

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun read(path: String) = Files.readString(root.resolve(path)).replace("\r\n", "\n")

    private fun git(vararg args: String): String = run(root, *args)

    private fun run(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.autocrlf=false") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")}: $output" }
        return output
    }
}
