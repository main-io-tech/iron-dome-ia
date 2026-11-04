package com.mio.irondome.interceptor

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
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
    private val host = ComboBox(AgentHost.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        addActionListener { updateHints() }
    }
    private val distro = ComboBox<String>().apply {
        isEditable = true
        addActionListener { updateHints() }
    }
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, 240, 5))
    private val cursorKey = JBPasswordField()
    private val runMode = ComboBox(AgentRunMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val found = JBLabel()
    private val status = JBLabel()
    private val warning = JBLabel().apply { foreground = JBColor(0x9A6700, 0xD29922) }
    private var savedKey = ""
    private val autoStatus = JBLabel()
    private val terminalStatus = JBLabel()
    private val autoFailure = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }

    override fun getDisplayName() = "Interceptador (IA)"

    override fun createComponent(): JComponent {
        val test = JButton("Salvar e Testar").apply { addActionListener { test() } }
        val auto = JButton("Configurar Automaticamente", IronDomeIcons.ia)
        auto.addActionListener { autoSetup(auto, test) }
        if (AgentLaunch.wslSupported) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val names = AgentLaunch.distributions()
                ApplicationManager.getApplication().invokeLater({
                    val chosen = selectedDistro() ?: AgentSettings.wslDistro
                    distro.removeAllItems()
                    names.forEach(distro::addItem)
                    distro.selectedItem = chosen ?: names.firstOrNull()
                }, ModalityState.any())
            }
        }
        val builder = FormBuilder.createFormBuilder()
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
        if (AgentLaunch.wslSupported) {
            builder.addLabeledComponent("Onde está o CLI:", host)
                .addLabeledComponent("Distribuição do WSL:", distro)
        }
        return builder
            .addLabeledComponent("Executável:", executable)
            .addComponentToRightColumn(found)
            .addLabeledComponent("Modelo:", model)
            .addComponentToRightColumn(comment("Opcional, como no --model do CLI. Vazio usa o modelo padrão da sua conta."))
            .addLabeledComponent("Tempo máximo (min):", timeout)
            .addComponentToRightColumn(comment("Uma tarefa que passar disso é interrompida."))
            .addLabeledComponent("Onde rodar a IA:", runMode)
            .addComponentToRightColumn(JBLabel("<html>${InterceptorPanel.RUN_MODE_HINT}</html>").apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = UIUtil.getContextHelpForeground()
            })
            .addComponent(TitledSeparator("Cursor"))
            .addLabeledComponent("Chave de API do Cursor:", cursorKey)
            .addComponentToRightColumn(comment("Opcional: sem ela, vale o login do agent login. Crie em cursor.com/dashboard > Integrations. Fica no cofre de senhas."))
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(test)
                add(status)
            })
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(JButton("Salvar e Testar no Terminal", AllIcons.Debugger.Console).apply {
                    isEnabled = AgentTerminal.available() != null
                    toolTipText = if (isEnabled) "Abre o terminal do projeto aberto e roda o CLI com esta configuração, no Windows ou no WSL"
                    else "O plugin de terminal da IDE está desligado"
                    addActionListener { testTerminal(this) }
                })
                add(terminalStatus)
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean =
        provider.selectedItem != AgentSettings.provider ||
            host.selectedItem != AgentSettings.host ||
            (host.selectedItem == AgentHost.WSL && selectedDistro() != AgentSettings.wslDistro) ||
            executable.text.trim() != AgentSettings.executable.orEmpty() ||
            model.text.trim() != AgentSettings.model.orEmpty() ||
            (timeout.value as Int) != AgentSettings.timeoutMinutes ||
            runMode.selectedItem != AgentSettings.runMode ||
            String(cursorKey.password).trim() != savedKey

    override fun apply() {
        AgentSettings.provider = provider.selectedItem as AgentProvider
        AgentSettings.host = host.selectedItem as? AgentHost ?: AgentHost.LOCAL
        if (host.selectedItem == AgentHost.WSL) AgentSettings.wslDistro = selectedDistro()
        AgentSettings.executable = executable.text
        AgentSettings.model = model.text
        AgentSettings.timeoutMinutes = timeout.value as Int
        AgentSettings.runMode = runMode.selectedItem as AgentRunMode
        AgentSettings.cursorApiKey = String(cursorKey.password)
        savedKey = AgentSettings.cursorApiKey.orEmpty()
        updateHints()
    }

    override fun reset() {
        provider.selectedItem = AgentSettings.provider
        host.selectedItem = AgentSettings.host
        distro.selectedItem = AgentSettings.wslDistro
        executable.text = AgentSettings.executable.orEmpty()
        model.text = AgentSettings.model.orEmpty()
        timeout.value = AgentSettings.timeoutMinutes
        runMode.selectedItem = AgentSettings.runMode
        savedKey = AgentSettings.cursorApiKey.orEmpty()
        cursorKey.text = savedKey
        updateHints()
        if (AgentSettings.verified) show("Testada em ${AgentSettings.verifiedAt}${AgentSettings.launch()?.where.orEmpty()}.", ok = true)
        else show("Ainda não testada: os botões de IA ficam desligados até o teste passar.", ok = false)
        AgentSettings.terminalVerifiedAt?.let { showTerminal("Terminal testado em $it.", ok = true) }
    }

    private fun selectedDistro(): String? = ((distro.selectedItem as? String) ?: distro.editor.item?.toString())?.trim()?.ifEmpty { null }

    private fun updateHints() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        val inWsl = host.selectedItem == AgentHost.WSL && AgentLaunch.wslSupported
        distro.isEnabled = inWsl
        if (inWsl) {
            found.text = selectedDistro()?.let { "No WSL $it, o CLI é procurado pelo shell de login (~/.local/bin) ao testar; vazio usa ${chosen.executables.first()}." }
                ?: "Escolha a distribuição do WSL."
            found.foreground = if (selectedDistro() != null) UIUtil.getContextHelpForeground() else JBColor.RED
        } else {
            val path = AgentSettings.resolveExecutable(chosen, executable.text.trim().ifEmpty { null })
            found.text = path?.let { "Encontrado: $it" } ?: "Não encontrado. ${chosen.installHint}"
            found.foreground = if (path != null) UIUtil.getContextHelpForeground() else JBColor.RED
        }
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

    /** O terminal é de um projeto: vale o primeiro aberto. */
    private fun testTerminal(button: JButton) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        if (project == null) {
            showTerminal("Abra um projeto: o teste usa o terminal dele.", ok = false)
            return
        }
        apply()
        button.isEnabled = false
        showTerminal("Abrindo o terminal de ${project.name} e esperando a IA gravar OK…", ok = null)
        AgentVerifier.testTerminal(project) { outcome ->
            button.isEnabled = true
            showTerminal(outcome.message, outcome.ok)
        }
    }

    private fun showTerminal(text: String, ok: Boolean?) {
        terminalStatus.text = "<html>$text</html>"
        terminalStatus.foreground = when (ok) {
            true -> JBColor(0x1A7F37, 0x3FB950)
            false -> JBColor.RED
            null -> UIUtil.getContextHelpForeground()
        }
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
