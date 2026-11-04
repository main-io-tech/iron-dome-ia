package com.mio.irondome.interceptor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Testa a integração com a configuração salva: uma pergunta curta, sem alterar arquivos, que confirma o
 * executável, o login e o modelo. Se passar, a IA fica marcada como testada e os botões de IA se habilitam.
 */
object AgentVerifier {

    class Outcome(val ok: Boolean, val message: String)

    private const val TIMEOUT_MS = 120_000
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    @Volatile
    var testing = false
        private set

    /** [onDone] roda na EDT. */
    fun test(onDone: (Outcome) -> Unit) {
        val provider = AgentSettings.provider
        val executable = AgentSettings.resolveExecutable()
        if (executable == null) {
            AgentSettings.clearVerified()
            onDone(Outcome(false, "Executável não encontrado. ${provider.installHint}"))
            return
        }
        val model = AgentSettings.model
        testing = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = if (provider == AgentProvider.CURSOR) AgentSettings.cursorApiKey else null
            val workspace = System.getProperty("user.home")
            val command = AgentCommand.build(provider, executable.toString(), AgentTask("Teste", "Responda apenas OK", write = false), workspace, model, 2)
            val line = GeneralCommandLine(command).withWorkDirectory(workspace).withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            key?.let { line.withEnvironment("CURSOR_API_KEY", it) }
            val outcome = runCatching { ExecUtil.execAndGetOutput(line, TIMEOUT_MS) }.fold(
                onSuccess = { output ->
                    val answer = output.stdoutLines.mapNotNull(AgentEvents::parse).filterIsInstance<AgentEvent.Result>().firstOrNull()
                    val model = output.stdoutLines.mapNotNull(AgentEvents::parse).filterIsInstance<AgentEvent.Init>().firstOrNull()?.model
                    when {
                        output.isTimeout -> Outcome(false, "Sem resposta em ${TIMEOUT_MS / 60_000} minutos.")
                        output.exitCode != 0 -> Outcome(false, output.stderrLines.lastOrNull { it.isNotBlank() } ?: "O CLI terminou com o código ${output.exitCode}.")
                        answer == null -> Outcome(false, "O CLI respondeu, mas sem o resultado esperado. Confira se ele está atualizado.")
                        answer.error -> Outcome(false, answer.text.ifBlank { "A IA informou um erro." })
                        else -> Outcome(true, "${provider.title}${model?.let { " · modelo $it" }.orEmpty()} respondeu: ${answer.text.trim().take(40)}")
                    }
                },
                onFailure = { Outcome(false, it.message ?: it.javaClass.simpleName) },
            )
            ApplicationManager.getApplication().invokeLater({
                testing = false
                if (outcome.ok) AgentSettings.markVerified(LocalDateTime.now().format(DATE)) else AgentSettings.clearVerified()
                onDone(outcome)
            }, ModalityState.any())
        }
    }
}
