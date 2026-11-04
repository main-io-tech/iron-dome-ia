package com.mio.irondome.patrulha

/** Repositório no GitHub, lido da URL de um remote do Git. */
data class GitHubRepo(val host: String, val owner: String, val name: String) {

    val fullName get() = "$owner/$name"

    companion object {
        private val SCP = Regex("""^[\w.-]+@([\w.-]+):(.+)$""")
        private val URL = Regex("""^(?:https?|ssh|git)://(?:[^@/]+@)?([\w.-]+)(?::\d+)?/(.+)$""")

        /** Aceita `https://github.com/dono/repo.git`, `git@github.com:dono/repo.git` e `ssh://git@github.com/dono/repo`. */
        fun fromRemoteUrl(url: String): GitHubRepo? {
            val text = url.trim()
            val (host, path) = (SCP.matchEntire(text) ?: URL.matchEntire(text))?.destructured ?: return null
            val parts = path.removeSuffix("/").removeSuffix(".git").split('/').filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            return GitHubRepo(host.lowercase(), parts[0], parts[1])
        }
    }
}

/** O que a revisão diz do PR ao ser publicada, pelos eventos de revisão do GitHub. */
enum class ReviewVerdict(val event: String, val label: String, val done: String) {
    APPROVE("APPROVE", "Aprovar", "aprovado"),
    REQUEST_CHANGES("REQUEST_CHANGES", "Solicitar alterações", "com alterações solicitadas"),
    COMMENT("COMMENT", "Só comentar", "comentado"),
    ;

    /** O GitHub não deixa aprovar nem solicitar alterações no próprio PR. */
    val allowedOnOwnPr: Boolean get() = this == COMMENT

    /** Sem nenhum comentário na revisão, o GitHub exige texto para solicitar alterações ou comentar. */
    val needsBody: Boolean get() = this != APPROVE
}

data class PullRequest(
    val number: Int,
    val title: String,
    val author: String,
    val headRef: String,
    val headSha: String,
    val baseRef: String,
    val url: String,
    val updatedAt: String,
    val draft: Boolean,
    val baseSha: String = "",
    val createdAt: String = "",
    /** Dono do repositório da branch do PR: outro que o do repositório quando o PR vem de um fork. */
    val headOwner: String = "",
)

/**
 * Arquivo alterado no PR; [patch] é o diff unificado do GitHub, ausente em arquivos binários ou grandes.
 * [status] é o do GitHub (`added`, `modified`, `removed`, `renamed`...); [previousPath], o nome antigo de um
 * renomeado.
 */
data class ChangedFile(
    val path: String,
    val status: String,
    val patch: String?,
    val additions: Int = 0,
    val deletions: Int = 0,
    val previousPath: String? = null,
) {

    /** Linhas (1-based) do lado novo que aparecem no diff: só nelas o GitHub aceita comentário de linha. */
    val commentableLines: Set<Int> by lazy { DiffLines.rightSide(patch) }

    val added: Boolean get() = status == "added"
    val removed: Boolean get() = status == "removed"

    val statusLabel: String
        get() = when (status) {
            "added" -> "Novo"
            "removed" -> "Removido"
            "renamed" -> "Renomeado"
            "copied" -> "Copiado"
            else -> "Alterado"
        }
}

/** Os arquivos de um PR no [headSha] em que foram lidos; [mergeBase] é a base do diff, como no GitHub. */
data class PullRequestFiles(val headSha: String, val mergeBase: String, val files: List<ChangedFile>) {
    val additions: Int get() = files.sumOf { it.additions }
    val deletions: Int get() = files.sumOf { it.deletions }
}

object DiffLines {

    private val HUNK = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    fun rightSide(patch: String?): Set<Int> {
        if (patch == null) return emptySet()
        val lines = mutableSetOf<Int>()
        var next = 0
        for (line in patch.lineSequence()) {
            val hunk = HUNK.find(line)
            when {
                hunk != null -> next = hunk.groupValues[1].toInt()
                next == 0 -> continue
                line.startsWith("-") -> Unit
                line.startsWith("\\") -> Unit
                else -> lines += next++
            }
        }
        return lines
    }
}

/** Conversa de revisão no PR, identificada pelo primeiro comentário dela. */
data class ReviewThread(
    val firstCommentId: Long,
    val resolved: Boolean,
    val outdated: Boolean,
    val replies: List<ThreadReply>,
)

data class ThreadReply(val author: String, val body: String, val createdAt: String)
