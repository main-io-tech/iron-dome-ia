package com.mio.irondome.interceptor

import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
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
        val launch = AgentSettings.launch()
        if (launch == null) {
            AgentSettings.clearVerified()
            val missing = if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else "Executável não encontrado. ${provider.installHint}"
            onDone(Outcome(false, missing))
            return
        }
        val model = AgentSettings.model
        testing = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = if (provider == AgentProvider.CURSOR) AgentSettings.cursorApiKey else null
            val workspace = System.getProperty("user.home")
            val outcome = runCatching {
                val task = AgentTask("Teste", "Responda apenas OK", write = false)
                val command = AgentCommand.build(provider, launch.executable, task, launch.workspace(workspace), model, 2)
                ExecUtil.execAndGetOutput(launch.process(command, workspace, key), TIMEOUT_MS)
            }.fold(
                onSuccess = { output ->
                    val answer = output.stdoutLines.mapNotNull(AgentEvents::parse).filterIsInstance<AgentEvent.Result>().firstOrNull()
                    val model = output.stdoutLines.mapNotNull(AgentEvents::parse).filterIsInstance<AgentEvent.Init>().firstOrNull()?.model
                    when {
                        output.isTimeout -> Outcome(false, "Sem resposta em ${TIMEOUT_MS / 60_000} minutos.")
                        output.exitCode != 0 -> Outcome(false, output.stderrLines.lastOrNull { it.isNotBlank() } ?: "O CLI terminou com o código ${output.exitCode}.")
                        answer == null -> Outcome(false, "O CLI respondeu, mas sem o resultado esperado. Confira se ele está atualizado.")
                        answer.error -> Outcome(false, answer.text.ifBlank { "A IA informou um erro." })
                        else -> Outcome(true, "${provider.title}${launch.where}${model?.let { " · modelo $it" }.orEmpty()} respondeu: ${answer.text.trim().take(40)}")
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

    @Volatile
    var testingTerminal = false
        private set

    /**
     * Testa o caminho do terminal da IDE com a configuração salva, no Windows ou no WSL: abre uma aba do terminal,
     * pede à IA que grave OK num arquivo e espera o arquivo. Não muda o teste principal, que habilita os botões.
     * [onDone] roda na EDT.
     */
    fun testTerminal(project: Project, onDone: (Outcome) -> Unit) {
        val terminal = AgentTerminal.available()
            ?: return onDone(Outcome(false, "O plugin de terminal da IDE está desligado."))
        val provider = AgentSettings.provider
        val launch = AgentSettings.launch()
            ?: return onDone(Outcome(false, if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else "Executável não encontrado."))
        val root = project.basePath ?: return onDone(Outcome(false, "O projeto não tem pasta no disco."))
        val dir = AgentRunner.getInstance(project).transcriptsDir()?.resolve(TERMINAL_TEST_DIR)
            ?: return onDone(Outcome(false, "O projeto não tem pasta no disco."))
        val file = dir.resolve("teste-terminal-${System.currentTimeMillis()}.md")
        val relative = Path.of(root).relativize(file).toString().replace('\\', '/')
        val shellPath = terminal.shellPath(project)
        val shell = ShellKind.of(shellPath, SystemInfo.isWindows)
        val prompt = "Teste de integracao do Iron Dome IA: grave apenas a palavra OK no arquivo $relative (crie as pastas se faltarem) e termine, sem fazer mais nada."
        val command = launch.terminal(provider, prompt, AgentSettings.model, 2, shell, root, interactive = false)
        try {
            Files.createDirectories(dir)
            terminal.run(project, root, "IA: Teste", command)
        } catch (e: Exception) {
            return onDone(Outcome(false, "O terminal não abriu: ${e.message ?: e.javaClass.simpleName}"))
        }
        testingTerminal = true
        val shellName = shellPath?.replace('\\', '/')?.substringAfterLast('/') ?: shell.name.lowercase()
        ApplicationManager.getApplication().executeOnPooledThread {
            val started = System.currentTimeMillis()
            var answer: String? = null
            while (System.currentTimeMillis() - started < TIMEOUT_MS) {
                if (Files.isRegularFile(file)) {
                    answer = runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                    if (answer != null) break
                }
                Thread.sleep(1_000)
            }
            runCatching { Files.deleteIfExists(file) }
            val seconds = (System.currentTimeMillis() - started) / 1000
            val outcome = when {
                answer == null -> Outcome(
                    false,
                    "A resposta não chegou em ${TIMEOUT_MS / 60_000} minutos. Confira a aba \"IA: Teste\" do terminal: o comando, o login e o PATH do $shellName.",
                )
                else -> Outcome(true, "${provider.title}${launch.where} respondeu no terminal ($shellName) em $seconds s.")
            }
            ApplicationManager.getApplication().invokeLater({
                testingTerminal = false
                if (outcome.ok) AgentSettings.terminalVerifiedAt = LocalDateTime.now().format(DATE)
                onDone(outcome)
            }, ModalityState.any())
        }
    }

    private const val TERMINAL_TEST_DIR = "respostas"
}
