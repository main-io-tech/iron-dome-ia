package com.mio.irondome.base

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import java.util.concurrent.ConcurrentHashMap

/** Abas que não abriram nesta sessão da IDE, pelo título, com a mensagem do erro. */
object TabFailures {

    private val failures = ConcurrentHashMap<String, String>()
    private val linkage = ConcurrentHashMap.newKeySet<String>()

    val all: Map<String, String> get() = failures.toMap()

    /** Abas que não abriram porque a IDE não tem uma classe ou método que elas usam. */
    val fromIdeVersion: Set<String> get() = linkage.toSet()

    fun record(title: String, error: Throwable) {
        failures[title] = describe(error)
        if (isIdeVersionError(error)) linkage += title else linkage -= title
    }

    fun clear(title: String) {
        failures.remove(title)
        linkage.remove(title)
    }

    fun describe(error: Throwable): String {
        val message = error.message?.lines()?.firstOrNull()?.trim().orEmpty()
        val text = if (message.isEmpty()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1) + "…" else text
    }

    /** `NoSuchMethodError`, `NoClassDefFoundError` e afins: o código do plugin pede algo que esta IDE não tem. */
    fun isIdeVersionError(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(5).any { it is LinkageError }

    private const val MAX_LENGTH = 200
}

/** Ocupa o lugar de uma aba que não abriu, com o erro e os caminhos para entender e reportar. */
internal class TabErrorPanel(title: String, error: Throwable) : JBPanelWithEmptyText() {

    init {
        emptyText.apply {
            text = "Não foi possível abrir a aba $title"
            appendLine(TabFailures.describe(error), SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            if (TabFailures.isIdeVersionError(error)) {
                appendLine(
                    "Esta versão do IntelliJ (${IdeSupport.ideVersion}) não tem algo que a aba usa. Testado com ${IdeSupport.TESTED_VERSION}.",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
                )
            } else {
                appendLine("O erro foi registrado no idea.log.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            appendLine("Ver Logs de Erro", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, ERROR_LOG_ACTION) }
            appendLine("Verificar Compatibilidade", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, COMPATIBILITY_ACTION) }
            appendLine("Reportar Bug", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runAction(this@TabErrorPanel, REPORT_BUG_ACTION) }
        }
    }

    companion object {
        const val COMPATIBILITY_ACTION = "IronDome.CheckCompatibility"
        const val REPORT_BUG_ACTION = "IronDome.ReportBug"
        const val ERROR_LOG_ACTION = "IronDome.ShowErrorLog"
    }
}

/** No lugar de uma aba desligada no modo limitado: esta versão do IntelliJ não tem o que ela usa. */
internal class UnavailableTabPanel(title: String, missing: List<String>) : JBPanelWithEmptyText() {

    init {
        emptyText.apply {
            text = "$title indisponível nesta versão do IntelliJ"
            appendLine("Falta: ${missing.joinToString()}.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            appendLine(
                "IntelliJ ${IdeSupport.ideVersion} (build ${IdeSupport.ideBuild}); testado com ${IdeSupport.TESTED_VERSION}.",
                SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
            )
            appendLine("Verificar Compatibilidade", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                runAction(this@UnavailableTabPanel, TabErrorPanel.COMPATIBILITY_ACTION)
            }
        }
    }
}

private fun runAction(component: JBPanelWithEmptyText, actionId: String) {
    val action = ActionManager.getInstance().getAction(actionId) ?: return
    ActionManager.getInstance().tryToExecute(action, null, component, ActionPlaces.TOOLWINDOW_CONTENT, true)
}
