package com.mio.irondome.interceptor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.irondome.base.FitWidthPanel
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.MechanismList
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.refreshAction
import com.mio.irondome.base.wrapping
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** A aba Interceptador no painel do Iron Dome IA. */
object InterceptorTab {
    const val TITLE = "Interceptador"

    /** Abre o painel do Iron Dome IA na aba Interceptador. */
    fun show(project: Project) {
        ReviewToolWindowFactory.showTab(project, TITLE)
    }
}

/**
 * A configuração da IA dentro do painel: qual CLI, o teste da integração (sem ele os botões de IA ficam
 * desligados), o que cada mecanismo ganha com a IA e as execuções recentes.
 */
class InterceptorPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Run(val path: Path, val title: String, val status: String, val ok: Boolean)

    private val runner = AgentRunner.getInstance(project)
    private val header = SummaryHeader(IronDomeIcons.interceptador, Tone.TEAL)
    private val provider = ComboBox(AgentProvider.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title} CLI (${it.executables.first()})" }
        addActionListener { updateProviderFields() }
    }
    private val hostLabel = JBLabel("Onde está o CLI:")
    private val host = ComboBox(AgentHost.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        addActionListener { updateProviderFields() }
    }
    private val distroLabel = JBLabel("Distribuição do WSL:")
    private val distro = ComboBox<String>().apply {
        isEditable = true
        addActionListener { updateProviderFields() }
    }
    /** O CLI achado em cada distribuição, por `provedor|distribuição`: procurar roda o wsl.exe. */
    private val wslFound = mutableMapOf<String, String?>()
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val found = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, AgentSettings.MAX_TIMEOUT_MINUTES, 5))
    private val extraArgs = JBTextField().apply { emptyText.text = "Ex.: --approve-mcps" }
    private val cursorKey = JBPasswordField()
    private val cursorKeyLabel = JBLabel("Chave de API do Cursor:")
    private val cursorKeyHint = comment("Opcional: sem ela, vale o login do agent login. Fica no cofre de senhas da IDE.")
    private val testButton = JButton("Salvar e Testar", AllIcons.Actions.Execute).apply { addActionListener { saveAndTest() } }
    private val status = JBLabel()
    private val autoButton = JButton("Configurar Automaticamente", IronDomeIcons.ia).apply { addActionListener { autoSetup() } }
    private val autoStatus = JBLabel()
    private val manualLink = ActionLink("") { setManualOpen(!manualOpen) }
    private val manualPanel: JPanel
    private var manualOpen = false
    private val runMode = ComboBox(AgentRunMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        selectedItem = AgentSettings.runMode
        addActionListener { (selectedItem as? AgentRunMode)?.let { if (it != AgentSettings.runMode) AgentSettings.runMode = it } }
    }
    private val terminalButton = JButton("Testar no Terminal", AllIcons.Debugger.Console).apply { addActionListener { testTerminal() } }
    private val terminalStatus = JBLabel()
    private var shown: List<Any?> = emptyList()
    /** Por que a configuração automática não passou, no cabeçalho até a IA ficar pronta. */
    private var autoFailure: String? = null
    private val features = JBLabel()
    private val runsModel = DefaultListModel<Run>()
    private val runs = MechanismList(runsModel, IronDomeIcons.ia, Tone.TEAL).apply {
        cellRenderer = RunRenderer()
        emptyText.text = "Nenhuma execução da IA neste projeto ainda"
        visibleRowCount = 8
    }

    init {
        manualPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("CLI:", provider)
            .addLabeledComponent(hostLabel, host)
            .addLabeledComponent(distroLabel, distro)
            .addLabeledComponent("Executável:", executable)
            .addComponentToRightColumn(found)
            .addLabeledComponent("Modelo:", model)
            .addLabeledComponent("Tempo máximo (min):", timeout)
            .addLabeledComponent("Argumentos extras:", extraArgs)
            .addLabeledComponent(cursorKeyLabel, cursorKey)
            .addComponentToRightColumn(cursorKeyHint)
            .addComponentToRightColumn(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
                add(testButton)
                add(status)
            })
            .panel
        val form = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("CLI de IA"))
            .addComponent(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
                add(autoButton)
                add(autoStatus)
            })
            .addComponent(comment("Procura o ${AgentProvider.entries.joinToString(", ") { "${it.title} CLI" }} instalados e testa cada um, com o modelo padrão, até um responder. Se nenhum responder, abre a configuração manual."))
            .addComponent(manualLink)
            .addComponent(manualPanel)
            .addComponent(TitledSeparator("Execução"))
            .addLabeledComponent("Onde rodar a IA:", runMode)
            .addComponentToRightColumn(comment(RUN_MODE_HINT))
            .addComponentToRightColumn(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
                add(terminalButton)
                add(terminalStatus)
            })
            .addComponent(TitledSeparator("Ações com IA"))
            .addComponent(features)
            .addComponent(TitledSeparator("Execuções recentes"))
            .addComponent(ScrollPaneFactory.createScrollPane(runs).apply { preferredSize = JBUI.size(120, 200) })
            .addComponent(comment("Duplo clique abre o registro: o pedido, os passos, os arquivos alterados e a resposta. Ficam em iron-dome/ia/."))
            .panel
            .apply { border = JBUI.Borders.empty(8, 12) }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(VerticalLayout(0)).apply {
                add(header)
                add(AgentProgressPanel(project, InterceptorTab.TITLE, this@InterceptorPanel))
            }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(FitWidthPanel(form), true), BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronDome.InterceptorToolbar",
            DefaultActionGroup(
                refreshAction {
                    wslFound.clear()
                    loadDistros()
                    reload()
                },
                object : com.intellij.openapi.project.DumbAwareAction("Configurações", "Abre Settings > Tools > Iron Dome IA > Interceptador (IA)", AllIcons.General.Settings) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, AgentConfigurable::class.java)
                    }
                },
            ),
            true,
        ).apply { targetComponent = this@InterceptorPanel }.wrapping().component
        runs.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) runs.selectedValue?.let { runner.open(it.path) }
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AgentSettings.Listener.TOPIC,
            AgentSettings.Listener {
                ApplicationManager.getApplication().invokeLater({
                    if (persisted() != shown) loadFields()
                    updateState()
                }, project.disposed)
            },
        )
        project.messageBus.connect(this).subscribe(AgentRunner.RUNS, AgentRunner.Listener { ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed) })
        val wsl = AgentLaunch.wslSupported
        hostLabel.isVisible = wsl
        host.isVisible = wsl
        loadFields()
        loadDistros()
        reload()
        val autoNow = !AgentSettings.ready && !AgentSettings.autoSetupTried && AgentSettings.untouched
        setManualOpen(!AgentSettings.ready && !autoNow)
        if (autoNow) ApplicationManager.getApplication().invokeLater({ autoSetup() }, project.disposed)
    }

    private fun setManualOpen(open: Boolean) {
        manualOpen = open
        manualPanel.isVisible = open
        manualLink.text = if (open) "Ocultar a configuração manual" else "Configurar manualmente"
        revalidate()
        repaint()
    }

    /** Sem janela de progresso: o andamento aparece ao lado do botão e no cabeçalho. */
    private fun autoSetup() {
        autoButton.isEnabled = false
        testButton.isEnabled = false
        AgentAutoSetup.run(
            onProgress = { message ->
                showAutoStatus(message, null)
                updateState()
            },
            onDone = { result ->
                autoButton.isEnabled = true
                testButton.isEnabled = true
                autoFailure = result.message.takeUnless { result.ok }
                loadFields()
                showAutoStatus(if (result.ok) result.message else "Não deu para configurar sozinho: termine na configuração manual.", result.ok)
                showStatus("", null)
                setManualOpen(!result.ok)
                updateState()
            },
        )
    }

    /** Com a configuração salva: no Windows ou no WSL, como estiver. */
    private fun testTerminal() {
        terminalButton.isEnabled = false
        terminalStatus.text = "Abrindo o terminal e esperando a IA gravar OK…"
        terminalStatus.foreground = color(null)
        AgentVerifier.testTerminal(project) { outcome ->
            lastTerminalTest = AgentSettings.fingerprint() to outcome
            updateTerminalTest()
        }
    }

    /** O último teste no terminal, enquanto a configuração for a mesma. */
    private var lastTerminalTest: Pair<String, AgentVerifier.Outcome>? = null

    private fun updateTerminalTest() {
        val terminal = AgentTerminal.available() != null
        terminalButton.isEnabled = terminal && !AgentVerifier.testingTerminal && AgentSettings.launch() != null
        terminalButton.toolTipText = when {
            !terminal -> "O plugin de terminal da IDE está desligado"
            AgentSettings.launch() == null -> "Configure o CLI primeiro"
            else -> "Abre o terminal da IDE e roda o CLI${AgentSettings.launch()?.where.orEmpty()} com a configuração salva"
        }
        if (AgentVerifier.testingTerminal) return
        val last = lastTerminalTest?.takeIf { it.first == AgentSettings.fingerprint() }?.second
        val at = AgentSettings.terminalVerifiedAt
        terminalStatus.text = when {
            last != null -> "<html>${last.message}</html>"
            at != null -> "Terminal testado em $at."
            terminal -> "Ainda não testado no terminal."
            else -> ""
        }
        terminalStatus.foreground = color(last?.ok ?: at?.let { true })
    }

    private fun showAutoStatus(text: String, ok: Boolean?) {
        autoStatus.text = text
        autoStatus.foreground = color(ok)
    }

    override fun dispose() = Unit

    /** O que está salvo e os campos mostram; se mudar sem passar pelo painel, como em Settings, os campos são relidos. */
    private fun persisted(): List<Any?> = listOf(
        AgentSettings.provider,
        AgentSettings.host,
        AgentSettings.wslDistro,
        AgentSettings.executable,
        AgentSettings.model,
        AgentSettings.timeoutMinutes,
        AgentSettings.extraArgs,
    )

    private fun loadFields() {
        shown = persisted()
        provider.selectedItem = AgentSettings.provider
        host.selectedItem = AgentSettings.host
        distro.selectedItem = AgentSettings.wslDistro
        executable.text = AgentSettings.executable.orEmpty()
        model.text = AgentSettings.model.orEmpty()
        timeout.value = AgentSettings.timeoutMinutes
        extraArgs.text = AgentSettings.extraArgs.orEmpty()
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = AgentSettings.cursorApiKey.orEmpty()
            ApplicationManager.getApplication().invokeLater({ cursorKey.text = key }, project.disposed)
        }
        updateProviderFields()
    }

    private fun reload() {
        updateState()
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = runner.transcripts().take(MAX_RUNS).map { path ->
                val (title, status) = AgentTranscript.summary(runCatching { Files.readAllLines(path).take(12) }.getOrDefault(emptyList()))
                Run(path, title ?: path.fileName.toString(), status ?: "", status == "concluída")
            }
            ApplicationManager.getApplication().invokeLater({
                runsModel.clear()
                loaded.forEach(runsModel::addElement)
            }, project.disposed)
        }
    }

    /** As distribuições do WSL, para escolher; listar roda o wsl.exe. */
    private fun loadDistros() {
        if (!AgentLaunch.wslSupported) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val names = AgentLaunch.distributions()
            ApplicationManager.getApplication().invokeLater({
                val chosen = distro.selectedItem as? String ?: AgentSettings.wslDistro
                distro.removeAllItems()
                names.forEach(distro::addItem)
                distro.selectedItem = chosen ?: names.firstOrNull()
                updateProviderFields()
            }, project.disposed)
        }
    }

    private fun updateProviderFields() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        val isCursor = chosen == AgentProvider.CURSOR
        cursorKey.isVisible = isCursor
        cursorKeyLabel.isVisible = isCursor
        cursorKeyHint.isVisible = isCursor
        val inWsl = host.selectedItem == AgentHost.WSL && AgentLaunch.wslSupported
        distroLabel.isVisible = inWsl
        distro.isVisible = inWsl
        val configured = executable.text.trim().ifEmpty { null }
        if (!inWsl) {
            val path = AgentSettings.resolveExecutable(chosen, configured)
            showFound(path?.toString(), chosen)
            return
        }
        val name = (distro.selectedItem as? String)?.trim()?.ifEmpty { null }
        when {
            name == null -> showFound(null, chosen, "Escolha a distribuição do WSL.")
            configured != null -> showFound("WSL $name: $configured", chosen)
            else -> {
                val key = "${chosen.name}|$name"
                if (key in wslFound) {
                    showFound(wslFound[key]?.let { "WSL $name: $it" }, chosen)
                    return
                }
                found.text = "Procurando o ${chosen.title} CLI no WSL $name…"
                found.foreground = UIUtil.getContextHelpForeground()
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = AgentLaunch.findInWsl(chosen, name)
                    ApplicationManager.getApplication().invokeLater({
                        wslFound[key] = path
                        updateProviderFields()
                    }, project.disposed)
                }
            }
        }
    }

    private fun showFound(where: String?, chosen: AgentProvider, missing: String = "Não encontrado. ${chosen.installHint}") {
        found.text = where?.let { "Encontrado: $it" } ?: missing
        found.foreground = if (where != null) UIUtil.getContextHelpForeground() else JBColor.RED
    }

    private fun saveAndTest() {
        AgentSettings.provider = provider.selectedItem as AgentProvider
        AgentSettings.host = if (AgentLaunch.wslSupported) host.selectedItem as? AgentHost ?: AgentHost.LOCAL else AgentHost.LOCAL
        AgentSettings.wslDistro = (distro.selectedItem as? String) ?: distro.editor.item?.toString()
        AgentSettings.executable = executable.text
        AgentSettings.model = model.text
        AgentSettings.timeoutMinutes = timeout.value as Int
        AgentSettings.extraArgs = extraArgs.text
        shown = persisted()
        val key = String(cursorKey.password)
        testButton.isEnabled = false
        showStatus("Testando ${AgentSettings.provider.title}…", null)
        ApplicationManager.getApplication().executeOnPooledThread {
            AgentSettings.cursorApiKey = key
            ApplicationManager.getApplication().invokeLater({
                AgentVerifier.test { outcome ->
                    testButton.isEnabled = true
                    showStatus(outcome.message, outcome.ok)
                    updateState()
                }
            }, project.disposed)
        }
    }

    private fun updateState() {
        updateProviderFields()
        updateTerminalTest()
        val chosen = AgentSettings.provider
        val ready = AgentSettings.ready
        val busy = runner.running
        when {
            AgentAutoSetup.running -> header.update(
                "Configurando a IA…",
                Tone.YELLOW.accent,
                autoStatus.text,
            )
            AgentSettings.launch() == null -> header.update(
                "IA não configurada",
                Tone.GRAY.accent,
                "O Interceptador chama a IA pelo ${chosen.title} CLI, na sua máquina ou no WSL, sobre o projeto aberto.",
                warning = autoFailure ?: if (AgentSettings.host == AgentHost.WSL) "Escolha a distribuição do WSL." else chosen.installHint,
            )
            !ready -> header.update(
                "Teste a integração",
                Tone.YELLOW.accent,
                "${chosen.title} CLI encontrado; falta testar com a configuração atual.",
                warning = autoFailure ?: "Os botões de IA ficam desligados até Salvar e Testar passar.",
            )
            else -> header.update(
                if (busy != null) "IA trabalhando: ${busy.title}" else "IA pronta",
                Tone.GREEN.accent,
                "${chosen.title} CLI${AgentSettings.model?.let { " · modelo $it" }.orEmpty()} · testada em ${AgentSettings.verifiedAt.orEmpty()}",
            )
        }
        if (!AgentVerifier.testing && status.text.isEmpty()) {
            if (ready) showStatus("Testada em ${AgentSettings.verifiedAt.orEmpty()}.", true)
        }
        features.text = featuresHtml(ready)
    }

    private fun featuresHtml(ready: Boolean): String {
        val mark = if (ready) "<font color='#1A7F37'>●</font>" else "<font color='#8C959F'>○</font>"
        val items = listOf(
            "Radar e barra do editor" to "Disparar Todos com IA, ou Disparar Selecionados e Disparar no cartão do alvo: a IA executa a ordem de interceptação e marca os alvos atendidos",
            "Patrulha" to "Revisar com IA (no modo revisão): comentários da IA como rascunhos do PR",
            "Escudo" to "Explicar Falha com IA e, na resposta, Corrigir com IA",
            "Caixa-Preta" to "Analisar com IA, no bug gerado e em Ver Logs de Erro",
        )
        val state = if (ready) "Habilitadas." else "Desligadas até a integração ser testada."
        return "<html>$state<br>" + items.joinToString("<br>") { (where, what) -> "$mark <b>$where:</b> $what" } + "</html>"
    }

    private fun showStatus(text: String, ok: Boolean?) {
        status.text = text
        status.foreground = color(ok)
    }

    private fun color(ok: Boolean?) = when (ok) {
        true -> JBColor(0x1A7F37, 0x3FB950)
        false -> JBColor.RED
        null -> UIUtil.getContextHelpForeground()
    }

    private class RunRenderer : ColoredListCellRenderer<Run>() {
        override fun customizeCellRenderer(list: JList<out Run>, value: Run?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = if (value.ok) AllIcons.General.InspectionsOK else AllIcons.General.Warning
            append(value.title)
            append("  ${value.status}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ${value.path.fileName.toString().take(15)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    internal companion object {
        const val MAX_RUNS = 30
        const val RUN_MODE_HINT = "No terminal, a IA roda à vista numa aba do terminal da IDE, onde dá para acompanhar e conversar com ela " +
            "(o Cursor abre interativo, com o login do agent login). Ela grava a resposta final em iron-dome/ia/respostas/, " +
            "e o plugin segue dali, como na aba. Testar no Terminal confere esse caminho com a configuração salva, no Windows ou no WSL."

        fun comment(text: String) = JBLabel("<html>$text</html>").apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            foreground = UIUtil.getContextHelpForeground()
            setAllowAutoWrapping(true)
        }
    }
}
