package com.mio.irondome.defesa

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Um commit do histórico: só o que a análise precisa. */
data class Commit(val sha: String, val subject: String, val author: String, val files: List<String>) {
    /** Mensagem de correção: o commit arrumou algo que estava quebrado. */
    val isFix: Boolean get() = FIX.containsMatchIn(subject)

    private companion object {
        val FIX = Regex("""\b(fix|fixes|fixed|bug|bugfix|hotfix|corrig\w*|conserta\w*|arruma\w*|erro|falha|regress\w*)\b""", RegexOption.IGNORE_CASE)
    }
}

/** Outro arquivo que costuma mudar junto: em [together] dos [total] commits do arquivo de referência. */
data class CoChange(val path: String, val together: Int, val total: Int) {
    val ratio: Double get() = if (total == 0) 0.0 else together.toDouble() / total

    val percent: Int get() = (ratio * 100).toInt()
}

/** O que o git conta sobre um arquivo nos últimos meses. */
data class FileHistory(
    val path: String,
    val commits: Int,
    val fixes: Int,
    val authors: Set<String>,
    val coChanges: List<CoChange>,
    /** As mensagens das correções, da mais recente para a mais antiga, para o dossiê. */
    val fixSubjects: List<String>,
) {
    /** Um arquivo que só uma pessoa tocou: o conhecimento está concentrado. */
    val singleAuthor: Boolean get() = authors.size == 1 && commits >= ChangeHistory.SINGLE_AUTHOR_FROM

    /** Co-mudanças fortes: juntos em pelo menos [ChangeHistory.CO_CHANGE_MIN_RATIO] dos commits, com amostra mínima. */
    val strongCoChanges: List<CoChange>
        get() = coChanges.filter { it.total >= ChangeHistory.CO_CHANGE_MIN_COMMITS && it.ratio >= ChangeHistory.CO_CHANGE_MIN_RATIO }

    companion object {
        fun empty(path: String) = FileHistory(path, 0, 0, emptySet(), emptyList(), emptyList())
    }
}

/**
 * O histórico do git lido uma vez (`git log --name-only`) e resumido por arquivo: quantas vezes mudou, quantas
 * foram correções, quem mexeu e com quais arquivos costuma mudar junto. Barato, local e sem IA.
 */
class ChangeHistory(val commits: List<Commit>) {

    private val byFile: Map<String, List<Commit>> = commits.flatMap { commit -> commit.files.map { it to commit } }
        .groupBy({ it.first }, { it.second })

    val isEmpty: Boolean get() = commits.isEmpty()

    fun of(path: String): FileHistory {
        val own = byFile[path] ?: return FileHistory.empty(path)
        val partners = own.asSequence()
            // Commits enormes (merges, formatação geral) não dizem nada sobre quem muda junto com quem.
            .filter { it.files.size <= CO_CHANGE_MAX_FILES }
            .flatMap { commit -> commit.files.asSequence().filter { it != path } }
            .groupingBy { it }.eachCount()
        val coChanges = partners.entries
            .map { (partner, together) -> CoChange(partner, together, own.size) }
            .sortedWith(compareByDescending<CoChange> { it.together }.thenBy { it.path })
            .take(CO_CHANGE_LIMIT)
        val fixes = own.filter { it.isFix }
        return FileHistory(
            path = path,
            commits = own.size,
            fixes = fixes.size,
            authors = own.map { it.author }.toSet(),
            coChanges = coChanges,
            fixSubjects = fixes.map { it.subject }.distinct().take(FIX_SUBJECTS_LIMIT),
        )
    }

    /**
     * Entre [changed], os parceiros fortes que ficaram de fora: `A` mudou, mas `B`, que muda junto com `A` em 80%
     * dos commits, não. Um alerta melhor que qualquer dependência estática.
     */
    fun missingPartners(changed: Collection<String>): List<Pair<String, CoChange>> {
        val set = changed.toSet()
        return changed.flatMap { path -> of(path).strongCoChanges.filter { it.path !in set }.map { path to it } }
            .sortedByDescending { it.second.ratio }
    }

    companion object {
        const val MONTHS = 6
        const val CO_CHANGE_MAX_FILES = 20
        const val CO_CHANGE_LIMIT = 8
        const val CO_CHANGE_MIN_RATIO = 0.6
        const val CO_CHANGE_MIN_COMMITS = 4
        const val SINGLE_AUTHOR_FROM = 3
        const val FIX_SUBJECTS_LIMIT = 5
        private const val SEPARATOR = "\u001E"
        private const val TIMEOUT_MS = 30_000

        val EMPTY = ChangeHistory(emptyList())

        /** Os argumentos do `git log`, para o teste conferir e para rodar. */
        fun command(months: Int = MONTHS): List<String> =
            listOf("git", "log", "--since=$months.months", "--no-merges", "--name-only", "--format=$SEPARATOR%H%x09%an%x09%s")

        /** Lê a saída de [command]: um bloco por commit, com o cabeçalho e os arquivos. */
        fun parse(output: String): ChangeHistory {
            val commits = output.split(SEPARATOR).mapNotNull { block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val header = lines.firstOrNull()?.split('\t', limit = 3) ?: return@mapNotNull null
                if (header.size < 3 || header[0].length < 7) return@mapNotNull null
                Commit(header[0], header[2], header[1], lines.drop(1).map { it.replace('\\', '/') })
            }
            return ChangeHistory(commits)
        }

        fun read(root: String): ChangeHistory = runCatching {
            val line = GeneralCommandLine(command()).withWorkDirectory(root).withCharset(StandardCharsets.UTF_8)
            val output = ExecUtil.execAndGetOutput(line, TIMEOUT_MS)
            if (output.exitCode != 0 || output.isTimeout) EMPTY else parse(output.stdout)
        }.getOrElse {
            LOG.warn("Não deu para ler o histórico do git", it)
            EMPTY
        }

        private val LOG = logger<ChangeHistory>()
    }
}

/** O histórico do projeto em cache, relido em segundo plano quando envelhece. */
@Service(Service.Level.PROJECT)
class ChangeHistoryService(private val project: Project) {

    @Volatile
    private var history: ChangeHistory? = null
    @Volatile
    private var readAt = 0L
    private val loading = AtomicBoolean(false)

    /** O histórico já lido, ou vazio enquanto a primeira leitura roda em segundo plano. */
    val current: ChangeHistory
        get() {
            val cached = history
            if (cached == null || System.currentTimeMillis() - readAt > MAX_AGE_MS) refresh()
            return cached ?: ChangeHistory.EMPTY
        }

    /** O histórico em cache ou, sem ele, lido agora. Não chame na EDT. */
    fun currentOrLoad(): ChangeHistory = history?.also { if (System.currentTimeMillis() - readAt > MAX_AGE_MS) refresh() } ?: load()

    /** Lê agora, nesta thread (não chame na EDT). */
    fun load(): ChangeHistory {
        val root = project.basePath ?: return ChangeHistory.EMPTY
        val read = ChangeHistory.read(root)
        history = read
        readAt = System.currentTimeMillis()
        return read
    }

    fun refresh() {
        if (!loading.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                load()
            } finally {
                loading.set(false)
            }
        }
    }

    companion object {
        private const val MAX_AGE_MS = 30 * 60 * 1000L

        fun getInstance(project: Project): ChangeHistoryService = project.service()
    }
}
