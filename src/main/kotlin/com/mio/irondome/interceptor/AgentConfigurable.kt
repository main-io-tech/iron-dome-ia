package com.mio.irondome.interceptor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Settings > Tools > Iron Dome IA > Interceptador (IA): qual CLI de IA usar e como. */
class AgentConfigurable : Configurable {

    private val provider = ComboBox(AgentProvider.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title} CLI (${it.executables.first()})" }
        addActionListener { updateHints() }
    }
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, 240, 5))
    private val cursorKey = JBPasswordField()
    private val found = JBLabel()
    private val status = JBLabel()
    private val warning = JBLabel().apply { foreground = JBColor(0x9A6700, 0xD29922) }
    private var savedKey = ""
    private val autoStatus = JBLabel()
    private val autoFailure = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }

    override fun getDisplayName() = "Interceptador (IA)"

    override fun createComponent(): JComponent {
        val test = JButton("Salvar e Testar").apply { addActionListener { test() } }
        val auto = JButton("Configurar Automaticamente", IronDomeIcons.ia)
        auto.addActionListener { autoSetup(auto, test) }
        return FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Configuração automática"))
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(auto)
                add(autoStatus)
            })
            .addComponent(comment("Procura o Cursor CLI e o Antigravity CLI instalados e testa cada um, com o modelo padrão, até um responder. Se nenhum responder, termine abaixo."))
            .addComponent(autoFailure)
            .addComponent(TitledSeparator("CLI de IA"))
            .addLabeledComponent("CLI:", provider)
            .addComponentToRightColumn(warning)
            .addLabeledComponent("Executável:", executable)
            .addComponentToRightColumn(found)
            .addLabeledComponent("Modelo:", model)
            .addComponentToRightColumn(comment("Opcional, como no --model do CLI. Vazio usa o modelo padrão da sua conta."))
            .addLabeledComponent("Tempo máximo (min):", timeout)
            .addComponentToRightColumn(comment("Uma tarefa que passar disso é interrompida."))
            .addComponent(TitledSeparator("Cursor"))
            .addLabeledComponent("Chave de API do Cursor:", cursorKey)
            .addComponentToRightColumn(comment("Opcional: sem ela, vale o login do agent login. Crie em cursor.com/dashboard > Integrations. Fica no cofre de senhas."))
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(test)
                add(status)
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean =
        provider.selectedItem != AgentSettings.provider ||
            executable.text.trim() != AgentSettings.executable.orEmpty() ||
            model.text.trim() != AgentSettings.model.orEmpty() ||
            (timeout.value as Int) != AgentSettings.timeoutMinutes ||
            String(cursorKey.password).trim() != savedKey

    override fun apply() {
        AgentSettings.provider = provider.selectedItem as AgentProvider
        AgentSettings.executable = executable.text
        AgentSettings.model = model.text
        AgentSettings.timeoutMinutes = timeout.value as Int
        AgentSettings.cursorApiKey = String(cursorKey.password)
        savedKey = AgentSettings.cursorApiKey.orEmpty()
        updateHints()
    }

    override fun reset() {
        provider.selectedItem = AgentSettings.provider
        executable.text = AgentSettings.executable.orEmpty()
        model.text = AgentSettings.model.orEmpty()
        timeout.value = AgentSettings.timeoutMinutes
        savedKey = AgentSettings.cursorApiKey.orEmpty()
        cursorKey.text = savedKey
        updateHints()
        if (AgentSettings.verified) show("Testada em ${AgentSettings.verifiedAt}.", ok = true)
        else show("Ainda não testada: os botões de IA ficam desligados até o teste passar.", ok = false)
    }

    private fun updateHints() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        val path = AgentSettings.resolveExecutable(chosen, executable.text.trim().ifEmpty { null })
        found.text = path?.let { "Encontrado: $it" } ?: "Não encontrado. ${chosen.installHint}"
        found.foreground = if (path != null) UIUtil.getContextHelpForeground() else JBColor.RED
        warning.text = if (chosen == AgentProvider.ANTIGRAVITY) {
            "<html>O agy -p pode travar quando chamado por outro programa (issue 318 do antigravity-cli); o tempo máximo interrompe.</html>"
        } else {
            ""
        }
    }

    private fun autoSetup(auto: JButton, test: JButton) {
        auto.isEnabled = false
        test.isEnabled = false
        autoFailure.isVisible = false
        AgentAutoSetup.run(
            onProgress = { message ->
                autoStatus.text = message
                autoStatus.foreground = UIUtil.getContextHelpForeground()
            },
            onDone = { result ->
                auto.isEnabled = true
                test.isEnabled = true
                reset()
                autoStatus.text = if (result.ok) result.message else "Não deu para configurar sozinho: termine abaixo."
                autoStatus.foreground = if (result.ok) JBColor(0x1A7F37, 0x3FB950) else JBColor.RED
                autoFailure.text = result.message
                autoFailure.isVisible = !result.ok
            },
        )
    }

    /** Salva e testa: só com o teste passando os botões de IA se habilitam. */
    private fun test() {
        apply()
        show("Testando ${AgentSettings.provider.title}…", ok = null)
        AgentVerifier.test { outcome -> show(outcome.message, ok = outcome.ok) }
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
