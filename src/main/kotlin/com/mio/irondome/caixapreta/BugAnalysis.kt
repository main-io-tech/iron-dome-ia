package com.mio.irondome.caixapreta

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A IA lê um bug do plugin, do Reportar Bug ou dos logs de erro, e aponta a causa provável. Ela não altera
 * nada: a análise volta como resposta e, num bug, entra no fim do `bug.md`.
 */
object BugAnalysis {

    const val SECTION = "## Análise da IA"
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun instructions(subject: String, content: String): String = buildString {
        appendLine("# $subject")
        appendLine()
        appendLine("Analise o problema abaixo, do plugin Iron Dome IA para IntelliJ (pacote `${Mechanism.ROOT_PACKAGE}`). Se o código")
        appendLine("do plugin estiver neste projeto, leia os arquivos da pilha e do mecanismo indicado; se não estiver, use a pilha e")
        appendLine("o que se sabe da API do IntelliJ. Responda em português, em Markdown:")
        appendLine()
        appendLine("1. **Causa provável:** o que deu errado e por quê.")
        appendLine("2. **Onde:** arquivo, classe e linha.")
        appendLine("3. **Como reproduzir:** se der para deduzir.")
        appendLine("4. **Correção proposta:** o trecho corrigido, ou o que investigar se não houver certeza.")
        appendLine()
        appendLine("**Não altere nenhum arquivo.** Imagens e GIFs citados no relatório não estão disponíveis.")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine(content.trim())
    }

    /** A análise no fim do `bug.md`, trocando a anterior se já houver uma. */
    fun append(markdown: String, provider: String, answer: String, now: LocalDateTime = LocalDateTime.now()): String {
        val before = markdown.substringBefore("\n$SECTION").trimEnd()
        return "$before\n\n$SECTION\n\n_Feita pelo $provider em ${now.format(DATE)}; confira antes de seguir._\n\n${answer.trim()}\n"
    }

    fun analyzeReport(project: Project, bugFile: Path) {
        val content = runCatching { Files.readString(bugFile) }.getOrNull() ?: return
        val runner = AgentRunner.getInstance(project)
        val instructions = runner.writeInstructions("bug-${LocalDateTime.now().format(FILE_TIME)}.md", instructions("Relatório de bug", content.substringBefore("\n$SECTION")))
            ?: return
        runner.run(
            AgentTask("Analisar o bug", "Analise o bug descrito em $instructions, seguindo as instruções do arquivo.", write = false),
            "Abrir bug.md" to { _ -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(bugFile)?.let { runner.open(bugFile) } },
        ) { result ->
            if (!result.ok || result.answer.isBlank()) return@run
            runCatching { Files.writeString(bugFile, append(Files.readString(bugFile), result.provider.title, result.answer)) }
            LocalFileSystem.getInstance().refreshNioFiles(listOf(bugFile))
        }
    }

    fun analyzeError(project: Project, group: ErrorGroup) {
        val runner = AgentRunner.getInstance(project)
        val instructions = runner.writeInstructions("erro-${LocalDateTime.now().format(FILE_TIME)}.md", instructions("Erro do plugin no log da IDE", PluginLog.markdown(listOf(group))))
            ?: return
        runner.run(
            AgentTask("Analisar o erro de ${group.latest.mechanism?.title ?: "plugin"}", "Analise o erro descrito em $instructions, seguindo as instruções do arquivo.", write = false),
            "Reportar Este Erro" to { _ -> ReportBugAction.open(project, group.latest) },
        )
    }
}
