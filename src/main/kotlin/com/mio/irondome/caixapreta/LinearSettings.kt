package com.mio.irondome.caixapreta

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A API key fica no cofre de senhas da IDE; o projeto e o time, nas preferências. Sem configuração, os
 * bugs vão para o projeto do Iron Dome IA no Linear, no primeiro time dele.
 */
object LinearSettings {
    const val DEFAULT_PROJECT = "https://linear.app/mainio/project/iron-dome-ai-f447654e94fc/overview"

    private const val PROJECT_KEY = "IronDome.Linear.Project"
    private const val TEAM_KEY = "IronDome.Linear.Team"
    private val credentials = CredentialAttributes(generateServiceName("Iron Dome IA", "Linear API key"))
    private val PROJECT_SEGMENT = Regex("""/project/([^/?#]+)""")
    private val SLUG_ID = Regex("""(?:^|-)([0-9a-f]{12})$""")

    var apiKey: String?
        get() = PasswordSafe.instance.getPassword(credentials)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) = PasswordSafe.instance.setPassword(credentials, value?.trim()?.takeIf { it.isNotEmpty() })

    var project: String
        get() = PropertiesComponent.getInstance().getValue(PROJECT_KEY)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROJECT
        set(value) = PropertiesComponent.getInstance().setValue(PROJECT_KEY, value.trim().takeIf { it.isNotEmpty() && it != DEFAULT_PROJECT })

    /** Só é preciso quando o projeto tem mais de um time; vazio usa o primeiro. */
    var teamKey: String?
        get() = PropertiesComponent.getInstance().getValue(TEAM_KEY)?.takeIf { it.isNotBlank() }
        set(value) = PropertiesComponent.getInstance().setValue(TEAM_KEY, normalizeTeam(value).takeIf { it.isNotEmpty() })

    val configured: Boolean get() = apiKey != null && projectSlugId(project) != null

    fun normalizeTeam(value: String?) = value.orEmpty().trim().uppercase()

    /** O id curto do projeto, aceitando a URL (`.../project/iron-dome-ai-f447654e94fc/overview`), o slug ou só o id. */
    fun projectSlugId(value: String?): String? {
        val text = value.orEmpty().trim()
        val segment = PROJECT_SEGMENT.find(text)?.groupValues?.get(1) ?: text
        return SLUG_ID.find(segment)?.groupValues?.get(1)
    }

    /** Nome do projeto como aparece na URL, como `iron-dome-ai`, para mostrar antes de consultar o Linear. */
    fun projectLabel(value: String?): String {
        val text = value.orEmpty().trim()
        val segment = PROJECT_SEGMENT.find(text)?.groupValues?.get(1) ?: text
        return segment.replace(SLUG_ID, "").ifEmpty { segment }
    }
}

/** Settings > Tools > Iron Dome IA: para onde o Reportar Bug envia os relatórios. */
class LinearConfigurable : Configurable {

    private val apiKey = JBPasswordField()
    private val project = JBTextField()
    private val team = JBTextField().apply { emptyText.text = "Primeiro time do projeto" }
    private val status = JBLabel()
    private var savedKey = ""

    override fun getDisplayName() = "Iron Dome IA"

    override fun createComponent(): JComponent {
        val test = JButton("Testar Conexão").apply { addActionListener { testConnection() } }
        return FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Reportar Bug: envio ao Linear"))
            .addLabeledComponent("API key pessoal:", apiKey)
            .addComponentToRightColumn(comment("Crie em Linear > Settings > Security & access > Personal API keys. Fica guardada no cofre de senhas da IDE."))
            .addLabeledComponent("Projeto:", project)
            .addComponentToRightColumn(comment("A URL do projeto no Linear. Por padrão, o projeto do Iron Dome IA."))
            .addLabeledComponent("Chave do time:", team)
            .addComponentToRightColumn(comment("Opcional: só se o projeto tiver mais de um time, como ENG em ENG-123."))
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(test)
                add(status)
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified() =
        String(apiKey.password).trim() != savedKey ||
            project.text.trim().ifEmpty { LinearSettings.DEFAULT_PROJECT } != LinearSettings.project ||
            LinearSettings.normalizeTeam(team.text) != LinearSettings.teamKey.orEmpty()

    override fun apply() {
        LinearSettings.apiKey = String(apiKey.password)
        LinearSettings.project = project.text
        LinearSettings.teamKey = team.text
        savedKey = LinearSettings.apiKey.orEmpty()
        project.text = LinearSettings.project
    }

    override fun reset() {
        savedKey = LinearSettings.apiKey.orEmpty()
        apiKey.text = savedKey
        project.text = LinearSettings.project
        team.text = LinearSettings.teamKey.orEmpty()
        status.text = ""
    }

    private fun testConnection() {
        val key = String(apiKey.password).trim()
        val slugId = LinearSettings.projectSlugId(project.text.ifBlank { LinearSettings.DEFAULT_PROJECT })
        val teamKey = LinearSettings.normalizeTeam(team.text).ifEmpty { null }
        when {
            key.isEmpty() -> return show("Preencha a API key.", ok = false)
            slugId == null -> return show("URL do projeto não reconhecida.", ok = false)
        }
        show("Conectando…", ok = null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val found = LinearClient(key).project(slugId)
                found to found.teamFor(teamKey)
            }
            ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = { (found, team) -> show("Conectado: projeto ${found.name}, time ${team.name} (${team.key}).", ok = true) },
                    onFailure = { show(it.message ?: it.javaClass.simpleName, ok = false) },
                )
            }, ModalityState.any())
        }
    }

    private fun show(text: String, ok: Boolean?) {
        status.text = text
        status.foreground = when (ok) {
            true -> JBColor(0x1A7F37, 0x3FB950)
            false -> JBColor.RED
            null -> UIUtil.getContextHelpForeground()
        }
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
}
