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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.refreshAction
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
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewToolWindowFactory.ID) ?: return
        toolWindow.activate {
            toolWindow.contentManager.findContent(TITLE)?.let(toolWindow.contentManager::setSelectedContent)
        }
    }
}

/**
 * A configuração da IA dentro do painel: qual CLI, o teste da integração (sem ele os botões de IA ficam
 * desligados), o que cada mecanismo ganha com a IA e as execuções recentes.
 */
class InterceptorPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Run(val path: Path, val title: String, val status: String, val ok: Boolean)

    private val runner = AgentRunner.getInstance(project)
    private val header = SummaryHeader(IronDomeIcons.interceptador)
    private val provider = ComboBox(AgentProvider.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title} CLI (${it.executables.first()})" }
        addActionListener { updateProviderFields() }
    }
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val found = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, 240, 5))
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
    /** Por que a configuração automática não passou, no cabeçalho até a IA ficar pronta. */
    private var autoFailure: String? = null
    private val features = JBLabel()
    private val runsModel = DefaultListModel<Run>()
    private val runs = JBList(runsModel).apply {
        cellRenderer = RunRenderer()
        emptyText.text = "Nenhuma execução da IA neste projeto ainda"
        visibleRowCount = 8
    }

    init {
        manualPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("CLI:", provider)
            .addLabeledComponent("Executável:", executable)
            .addComponentToRightColumn(found)
            .addLabeledComponent("Modelo:", model)
            .addLabeledComponent("Tempo máximo (min):", timeout)
            .addLabeledComponent(cursorKeyLabel, cursorKey)
            .addComponentToRightColumn(cursorKeyHint)
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(testButton)
                add(status)
            })
            .panel
        val form = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("CLI de IA"))
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(autoButton)
                add(autoStatus)
            })
            .addComponent(comment("Procura o Cursor CLI e o Antigravity CLI instalados e testa cada um, com o modelo padrão, até um responder. Se nenhum responder, abre a configuração manual."))
            .addComponent(manualLink)
            .addComponent(manualPanel)
            .addComponent(TitledSeparator("Ações com IA"))
            .addComponent(features)
            .addComponent(TitledSeparator("Execuções recentes"))
            .addComponent(ScrollPaneFactory.createScrollPane(runs).apply { preferredSize = JBUI.size(400, 200) })
            .addComponent(comment("Duplo clique abre o registro: o pedido, os passos, os arquivos alterados e a resposta. Ficam em iron-dome/ia/."))
            .panel
            .apply { border = JBUI.Borders.empty(8, 12) }
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(form, true), BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronDome.InterceptorToolbar",
            DefaultActionGroup(
                refreshAction { reload() },
                object : com.intellij.openapi.project.DumbAwareAction("Configurações", "Abre Settings > Tools > Iron Dome IA > Interceptador (IA)", AllIcons.General.Settings) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, AgentConfigurable::class.java)
                    }
                },
            ),
            true,
        ).apply { targetComponent = this@InterceptorPanel }.component
        runs.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) runs.selectedValue?.let { runner.open(it.path) }
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AgentSettings.Listener.TOPIC,
            AgentSettings.Listener { ApplicationManager.getApplication().invokeLater({ updateState() }, project.disposed) },
        )
        project.messageBus.connect(this).subscribe(AgentRunner.RUNS, AgentRunner.Listener { ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed) })
        loadFields()
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

    private fun showAutoStatus(text: String, ok: Boolean?) {
        autoStatus.text = text
        autoStatus.foreground = color(ok)
    }

    override fun dispose() = Unit

    private fun loadFields() {
        provider.selectedItem = AgentSettings.provider
        executable.text = AgentSettings.executable.orEmpty()
        model.text = AgentSettings.model.orEmpty()
        timeout.value = AgentSettings.timeoutMinutes
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

    private fun updateProviderFields() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        val isCursor = chosen == AgentProvider.CURSOR
        cursorKey.isVisible = isCursor
        cursorKeyLabel.isVisible = isCursor
        cursorKeyHint.isVisible = isCursor
        val path = AgentSettings.resolveExecutable(chosen, executable.text.trim().ifEmpty { null })
        found.text = path?.let { "Encontrado: $it" } ?: "Não encontrado. ${chosen.installHint}"
        found.foreground = if (path != null) UIUtil.getContextHelpForeground() else JBColor.RED
    }

    private fun saveAndTest() {
        AgentSettings.provider = provider.selectedItem as AgentProvider
        AgentSettings.executable = executable.text
        AgentSettings.model = model.text
        AgentSettings.timeoutMinutes = timeout.value as Int
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
        val chosen = AgentSettings.provider
        val ready = AgentSettings.ready
        val busy = runner.running
        when {
            AgentAutoSetup.running -> header.update(
                "Configurando a IA…",
                Tone.YELLOW.accent,
                autoStatus.text,
            )
            AgentSettings.resolveExecutable() == null -> header.update(
                "IA não configurada",
                Tone.GRAY.accent,
                "O Interceptador chama a IA pelo ${chosen.title} CLI, na sua máquina, sobre o projeto aberto.",
                warning = autoFailure ?: chosen.installHint,
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
            "Radar e barra do editor" to "Disparar com IA: a IA executa a ordem de interceptação e marca os alvos atendidos",
            "Patrulha" to "Revisar com IA (no modo revisão): comentários da IA como rascunhos do PR",
            "Patrulha" to "Pedir Parecer da IA: a IA escreve o parecer de cada pedido no .md (sem IA, copia o pedido)",
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

    private companion object {
        const val MAX_RUNS = 30

        fun comment(text: String) = JBLabel("<html>$text</html>").apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            foreground = UIUtil.getContextHelpForeground()
        }
    }
}
