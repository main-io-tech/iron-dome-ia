package com.mio.irondome.patrulha

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.mio.irondome.base.rootPath
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Um arquivo de regras lido para a IA: [path] relativo ao projeto, ou absoluto quando está fora dele; [text] nulo
 * quando ele não existe ou não deu para ler; [truncated] quando o texto foi cortado pelo limite de tamanho.
 */
data class RuleFile(val path: String, val text: String?, val truncated: Boolean = false)

/**
 * Os arquivos de regras do projeto, como `AGENTS.md` ou `.cursor/rules`, que o revisor vinculou à Patrulha. O
 * conteúdo vai para a IA na Revisão com IA e nas respostas automáticas, junto com as [ReviewerGuidelines].
 * Pastas valem pelos `.md` e `.mdc` de dentro.
 */
@Service(Service.Level.PROJECT)
@State(name = "IronDomeReviewRules", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewRules(private val project: Project) : PersistentStateComponent<ReviewRules.RulesState> {

    class RulesState {
        /** Relativos ao projeto, com `/`, ou absolutos quando estão fora dele. */
        var paths: MutableList<String> = mutableListOf()
    }

    private var state = RulesState()

    override fun getState(): RulesState = state

    override fun loadState(loaded: RulesState) {
        state = loaded
    }

    var paths: List<String>
        get() = state.paths.toList()
        set(value) {
            state.paths = value.map { it.trim().replace('\\', '/').trimEnd('/') }.filter(String::isNotEmpty).distinct().toMutableList()
        }

    /** Os arquivos vinculados lidos do disco agora. Fora da EDT. */
    fun read(): List<RuleFile> = root()?.let { read(it, paths) }.orEmpty()

    fun root(): Path? = project.rootPath()?.let(Path::of)

    companion object {
        const val FILE_MAX = 12_000
        const val TOTAL_MAX = 40_000
        private const val DEPTH = 4
        private val EXTENSIONS = setOf("md", "mdc", "markdown")

        /** Onde as ferramentas de IA costumam guardar as regras de um projeto. */
        val COMMON = listOf(
            "AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules", ".cursor/rules",
            ".github/copilot-instructions.md", ".github/instructions", "CONTRIBUTING.md",
        )

        fun getInstance(project: Project): ReviewRules = project.service()

        /** Os de [COMMON] que existem em [root]. */
        fun detect(root: Path): List<String> = COMMON.filter { Files.exists(root.resolve(it)) }

        /** Como [absolute] fica na lista: relativo a [root] quando está dentro dele. */
        fun relativize(root: Path?, absolute: Path): String {
            val normalized = absolute.toAbsolutePath().normalize()
            val inside = root?.toAbsolutePath()?.normalize()?.takeIf { normalized.startsWith(it) && normalized != it }
            return (inside?.relativize(normalized) ?: normalized).toString().replace('\\', '/')
        }

        /** O arquivo ou a pasta de [path] em [root]; nulo se o caminho não é válido. */
        fun resolve(root: Path, path: String): Path? =
            try {
                Path.of(path).let { if (it.isAbsolute) it else root.resolve(path) }.normalize()
            } catch (_: InvalidPathException) {
                null
            }

        /**
         * Lê [paths] em [root], abrindo as pastas nos `.md` e `.mdc` de dentro, sem repetir arquivos. Cada um vai até
         * [FILE_MAX] caracteres e todos juntos até [TOTAL_MAX]: a IA lê tudo a cada revisão e resposta.
         */
        fun read(root: Path, paths: List<String>): List<RuleFile> {
            var budget = TOTAL_MAX
            return paths.flatMap { expand(root, it) }.distinctBy { it.first }.map { (display, file) ->
                val text = file?.let { runCatching { String(Files.readAllBytes(it), Charsets.UTF_8) }.getOrNull() }
                    ?: return@map RuleFile(display, null)
                val limit = minOf(FILE_MAX, budget)
                budget -= minOf(text.length, limit)
                RuleFile(display, text.take(limit), truncated = text.length > limit)
            }
        }

        private fun expand(root: Path, path: String): List<Pair<String, Path?>> {
            val file = resolve(root, path) ?: return listOf(path to null)
            if (file.isRegularFile()) return listOf(relativize(root, file) to file)
            if (!file.isDirectory()) return listOf(path to null)
            return Files.walk(file, DEPTH).use { files ->
                files.filter { it.isRegularFile() && it.extension.lowercase() in EXTENSIONS }.sorted().toList()
            }.map { relativize(root, it) to it }
        }

        /** A seção das regras nas instruções da IA; vazia sem regras com texto. */
        fun section(rules: List<RuleFile>): String {
            val readable = rules.filter { !it.text.isNullOrBlank() }
            val left = rules.filter { it.truncated && it.text.isNullOrBlank() }
            if (readable.isEmpty() && left.isEmpty()) return ""
            return buildString {
                appendLine("## Regras do projeto")
                appendLine()
                appendLine("Regras escritas pelo time para este projeto, nos arquivos que o revisor vinculou. Avalie o código do PR também")
                appendLine("por elas: o que descumprir uma regra merece comentário (ou que você insista na conversa), com a gravidade que a")
                appendLine("própria regra indicar, ou **Atenção:** se ela não disser, e o texto deve dizer qual regra e de qual arquivo.")
                appendLine("Regras com `globs` no cabeçalho valem só para os arquivos que casam com eles. Quando divergirem das instruções")
                appendLine("do revisor, siga as do revisor; e nunca acima das regras de não alterar arquivos e do formato da resposta.")
                readable.forEach { rule ->
                    val text = rule.text.orEmpty().trimEnd()
                    val fence = "`".repeat(maxOf(4, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
                    appendLine()
                    appendLine("### `${rule.path}`")
                    appendLine()
                    appendLine("${fence}markdown")
                    appendLine(text)
                    appendLine(fence)
                    if (rule.truncated) appendLine("(Cortado pelo limite de tamanho: leia o restante em `${rule.path}`, se precisar.)")
                }
                if (left.isNotEmpty()) {
                    appendLine()
                    appendLine("Não couberam aqui, pelo limite de tamanho: ${left.joinToString { "`${it.path}`" }}. Leia nos arquivos, se precisar.")
                }
                appendLine()
            }
        }
    }
}
