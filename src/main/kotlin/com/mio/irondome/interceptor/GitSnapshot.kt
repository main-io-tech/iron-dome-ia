package com.mio.irondome.interceptor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * A foto dos arquivos do projeto antes de uma tarefa da IA que altera arquivos, guardada no git sem mexer no que é do
 * usuário: um índice temporário (cópia do dele) recebe tudo o que está na pasta, inclusive o que não foi commitado nem
 * adicionado, e vira um commit numa referência própria, `refs/iron-dome/antes-da-ia/...`. O índice, a branch e os
 * stashes do usuário ficam como estavam. Só o que o `.gitignore` ignora fica de fora.
 */
object GitSnapshot {

    private const val REFS = "refs/iron-dome/antes-da-ia"
    private const val KEEP = 10
    private const val TIMEOUT_SECONDS = 180L

    class GitException(message: String) : Exception(message)

    /** O commit da foto de [root], ou nulo quando [root] não está num repositório git ou o git não está instalado. */
    fun create(root: Path, title: String): String? {
        runCatching { git(root, "rev-parse", "--show-toplevel") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return withIndex(root, copyReal = true) { env ->
            git(root, "add", "-A", "--", ".", env = env)
            val tree = git(root, "write-tree", env = env).trim()
            val parent = runCatching { git(root, "rev-parse", "--verify", "-q", "HEAD").trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            val args = listOf("commit-tree", tree) + (parent?.let { listOf("-p", it) } ?: emptyList()) + listOf("-m", "Iron Dome IA: antes de $title")
            val commit = git(root, *args.toTypedArray(), env = IDENTITY).trim()
            git(root, "update-ref", "$REFS/${System.currentTimeMillis()}", commit)
            prune(root)
            commit
        }
    }

    /**
     * Volta os arquivos de [root] para a foto [commit]: o que a IA alterou ou apagou volta, e o que ela criou sai. Só
     * dentro de [root], e o índice do usuário não muda.
     */
    fun restore(root: Path, commit: String) {
        val top = Path.of(git(root, "rev-parse", "--show-toplevel").trim())
        val created = withIndex(root, copyReal = true) { env ->
            git(root, "add", "-A", "--", ".", env = env)
            git(root, "diff", "--cached", "--name-only", "--no-renames", "--diff-filter=A", "-z", commit, "--", ".", env = env)
                .split('\u0000').filter(String::isNotEmpty)
        }
        created.forEach { Files.deleteIfExists(top.resolve(it)) }
        // O que a IA criou e adicionou ao índice sai dele também; o resto do índice do usuário fica como está.
        created.chunked(100).forEach { files ->
            git(top, *(listOf("rm", "--cached", "-q", "--ignore-unmatch", "--") + files).toTypedArray())
        }
        git(root, "restore", "--source=$commit", "--worktree", "--", ".")
    }

    /** Só as [KEEP] fotos mais recentes ficam: as outras deixam de segurar os arquivos no `.git`. */
    private fun prune(root: Path) {
        val refs = git(root, "for-each-ref", "--sort=-refname", "--format=%(refname)", REFS).lines().filter(String::isNotBlank)
        refs.drop(KEEP).forEach { git(root, "update-ref", "-d", it) }
    }

    /** Roda [block] com um índice temporário, que começa como cópia do índice do usuário, para o git reaproveitar o que já sabe. */
    private fun <T> withIndex(root: Path, copyReal: Boolean, block: (Map<String, String>) -> T): T {
        val index = Files.createTempFile("iron-dome-index", "")
        try {
            val real = Path.of(git(root, "rev-parse", "--git-path", "index").trim()).let { if (it.isAbsolute) it else root.resolve(it) }
            if (copyReal && Files.isRegularFile(real)) Files.copy(real, index, StandardCopyOption.REPLACE_EXISTING) else Files.delete(index)
            return block(mapOf("GIT_INDEX_FILE" to index.toString()))
        } finally {
            Files.deleteIfExists(index)
        }
    }

    /** Sem a identidade configurada, o `commit-tree` falha: a foto é do plugin, não de quem usa. */
    private val IDENTITY = mapOf(
        "GIT_AUTHOR_NAME" to "Iron Dome IA", "GIT_AUTHOR_EMAIL" to "iron-dome@localhost",
        "GIT_COMMITTER_NAME" to "Iron Dome IA", "GIT_COMMITTER_EMAIL" to "iron-dome@localhost",
    )

    private fun git(root: Path, vararg args: String, env: Map<String, String> = emptyMap()): String {
        val errors = Files.createTempFile("iron-dome-git", ".log")
        try {
            val process = ProcessBuilder(listOf("git", "-c", "core.quotepath=off", "-c", "core.safecrlf=false") + args)
                .directory(root.toFile())
                .redirectError(errors.toFile())
                .apply { environment().putAll(env) }
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GitException("git ${args.first()} passou de $TIMEOUT_SECONDS s")
            }
            if (process.exitValue() != 0) {
                val message = Files.readString(errors).lines().lastOrNull { it.isNotBlank() } ?: "código ${process.exitValue()}"
                throw GitException("git ${args.first()}: $message")
            }
            return output
        } finally {
            Files.deleteIfExists(errors)
        }
    }
}
