package com.mio.irondome.escudo

import com.intellij.openapi.project.Project
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** As falhas dos testes para a IA explicar e, se o usuário pedir depois, corrigir. */
object FailureExplanation {

    const val MAX_FAILURES = 10
    private const val STACK_MAX_LINES = 60
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun instructions(failures: List<TestNode>): String = buildString {
        appendLine("# Testes que falharam")
        appendLine()
        appendLine("Para cada falha abaixo, leia o teste e o código de produção que ele exercita no projeto e responda em")
        appendLine("português, em Markdown:")
        appendLine()
        appendLine("1. **Causa:** por que falhou, em poucas frases.")
        appendLine("2. **Onde:** o arquivo e a linha do problema.")
        appendLine("3. **Quem está errado:** o código de produção ou o próprio teste.")
        appendLine("4. **Correção proposta:** o trecho de código corrigido.")
        appendLine()
        appendLine("Falhas com a mesma causa podem ser explicadas juntas. **Não altere nenhum arquivo.**")
        failures.take(MAX_FAILURES).forEach { failure ->
            appendLine()
            appendLine("## ${failure.label}")
            appendLine()
            failure.codeReference?.let { appendLine("- **Teste:** `$it`") }
            failure.codeName?.let { appendLine("- **Método:** `$it`") }
            failure.errorMessage?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("```text")
                appendLine(it.trimEnd())
                appendLine("```")
            }
            failure.stacktrace?.takeIf { it.isNotBlank() }?.let { trace ->
                val lines = trace.trimEnd().lines()
                appendLine()
                appendLine("```text")
                lines.take(STACK_MAX_LINES).forEach(::appendLine)
                if (lines.size > STACK_MAX_LINES) appendLine("… (${lines.size - STACK_MAX_LINES} linhas omitidas)")
                appendLine("```")
            }
        }
        if (failures.size > MAX_FAILURES) {
            appendLine()
            appendLine("_Mais ${failures.size - MAX_FAILURES} falhas não listadas; rode de novo depois de corrigir estas._")
        }
    }

    /** A IA explica sem alterar nada; a resposta oferece Corrigir com IA, que aplica a correção proposta. */
    fun explain(project: Project, node: TestNode) {
        val failures = node.tests.filter { it.outcome == TestOutcome.FAILED }
        if (failures.isEmpty()) return
        val runner = AgentRunner.getInstance(project)
        val name = "testes-${LocalDateTime.now().format(FILE_TIME)}.md"
        val instructions = runner.writeInstructions(name, instructions(failures)) ?: return
        val what = if (failures.size == 1) "a falha de ${failures.single().label}" else "${failures.size} falhas de teste"
        runner.run(
            AgentTask("Explicar $what", "Explique as falhas de teste descritas em $instructions, seguindo as instruções do arquivo.", write = false),
            "Corrigir com IA" to { _ ->
                runner.run(
                    AgentTask(
                        "Corrigir $what",
                        "Corrija as falhas de teste descritas em $instructions: altere o código de produção, ou o teste se ele estiver errado, " +
                            "e explique o que mudou. Não apague nem desative testes.",
                        write = true,
                    ),
                )
            },
        )
    }
}
