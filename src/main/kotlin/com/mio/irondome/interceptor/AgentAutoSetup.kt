package com.mio.irondome.interceptor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.nio.file.Path

/**
 * Configura a IA sozinha: procura os CLIs instalados, Cursor primeiro, e testa cada um com o modelo padrão
 * até um responder. Se nenhum passar, a configuração fica no primeiro encontrado e o motivo de cada falha
 * vai no resultado, para o usuário terminar a configuração manual a partir dali.
 */
object AgentAutoSetup {

    class Result(val ok: Boolean, val message: String)

    @Volatile
    var running = false
        private set

    /** [onProgress] e [onDone] rodam na EDT. */
    fun run(onProgress: (String) -> Unit, onDone: (Result) -> Unit) {
        if (running || AgentVerifier.testing) return
        running = true
        AgentSettings.autoSetupTried = true
        onProgress("Procurando os CLIs de IA instalados…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = AgentProvider.entries.mapNotNull { provider -> AgentSettings.find(provider)?.let { provider to it } }
            ApplicationManager.getApplication().invokeLater({
                if (found.isEmpty()) {
                    finish(onDone, Result(false, notFound()))
                } else {
                    tryNext(found, 0, emptyList(), onProgress, onDone)
                }
            }, ModalityState.any())
        }
    }

    private fun tryNext(
        found: List<Pair<AgentProvider, Path>>,
        index: Int,
        failures: List<String>,
        onProgress: (String) -> Unit,
        onDone: (Result) -> Unit,
    ) {
        if (index == found.size) {
            val (first, _) = found.first()
            use(first)
            val missing = AgentProvider.entries.filter { provider -> found.none { it.first == provider } }
            val message = (failures + missing.map { "${it.title} CLI não encontrado." }).joinToString(" ")
            finish(onDone, Result(false, message))
            return
        }
        val (provider, path) = found[index]
        use(provider)
        onProgress("Testando o ${provider.title} CLI em $path…")
        AgentVerifier.test { outcome ->
            if (outcome.ok) {
                finish(onDone, Result(true, "Configurada com o ${provider.title} CLI. ${outcome.message}"))
            } else {
                tryNext(found, index + 1, failures + "${provider.title}: ${outcome.message}", onProgress, onDone)
            }
        }
    }

    /** Executável e modelo automáticos; a chave do Cursor, se já existir, continua valendo. */
    private fun use(provider: AgentProvider) {
        AgentSettings.provider = provider
        AgentSettings.executable = null
        AgentSettings.model = null
    }

    private fun finish(onDone: (Result) -> Unit, result: Result) {
        running = false
        onDone(result)
    }

    private fun notFound(): String =
        "Nenhum CLI de IA encontrado. " + AgentProvider.entries.joinToString(" ") { "${it.title}: ${it.installHint}" }
}
