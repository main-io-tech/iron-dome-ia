package com.mio.irondome.caixapreta

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.Image
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Formulário do Reportar Bug. Não é modal, para a gravação poder acontecer com o formulário escondido
 * enquanto o usuário reproduz o bug na IDE.
 */
internal class ReportBugDialog(
    private val project: Project,
    private val area: Rectangle?,
    private val screenshot: BufferedImage?,
    mechanism: Mechanism,
    private val errors: List<PluginError>,
    private val context: List<Pair<String, String>>,
    linearProject: String?,
) : DialogWrapper(project, true) {

    private val titleField = JBTextField().apply { emptyText.text = "Resumo curto do problema" }
    private val mechanismBox = ComboBox(Mechanism.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title}  ·  ${it.qualifiedPackage}" }
        selectedItem = mechanism
    }
    private val description = textArea("O que deu errado, com a mensagem de erro se houver")
    private val steps = textArea("1. Abra a aba…  2. Clique em…")
    private val expected = textArea("O que deveria ter acontecido")

    private val includeScreenshot = JBCheckBox("Incluir o print da janela da IDE", screenshot != null).apply {
        isEnabled = screenshot != null
        if (screenshot == null) text = "Print indisponível: a janela da IDE não pôde ser capturada"
    }
    private val recordButton = JButton("Gravar GIF", AllIcons.Debugger.Db_set_breakpoint).apply {
        isEnabled = area != null
        addActionListener { startRecording() }
    }
    private val recordingStatus = JBLabel()
    private val discardRecording = ActionLink("Descartar") { discardRecording() }
    private val includeErrors = JBCheckBox(
        if (errors.isEmpty()) "Nenhum erro do plugin no log da IDE"
        else "Incluir ${if (errors.size == 1) "o erro" else "os ${errors.size} erros"} do plugin no log da IDE",
        errors.isNotEmpty(),
    ).apply { isEnabled = errors.isNotEmpty() }
    private val sendToLinear = JBRadioButton().apply { addActionListener { updateOkText() } }
    private val localOnly = JBRadioButton("Gerar só local: o bug.md abre no editor").apply { addActionListener { updateOkText() } }
    private val configureLinear = ActionLink("Configurar Linear…") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, LinearConfigurable::class.java)
        refreshLinear()
    }
    private val folderField = TextFieldWithBrowseButton().apply {
        text = BugReports.folder.toString()
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Pasta dos Relatórios de Bug"))
    }
    private val useProjectFolder = ActionLink("Pasta do projeto") {
        BugReports.projectFolder(project)?.let { folderField.text = it.toString() }
    }.apply { isVisible = BugReports.projectFolder(project) != null }
    private val useDefaultFolder = ActionLink("Pasta padrão") { folderField.text = BugReports.defaultFolder.toString() }

    private var recorder: GifRecorder? = null
    private var recording: GifRecorder.Recording? = null
    private var recordingError: String? = null
    private var submitted = false

    init {
        title = "Reportar Bug do Iron Dome IA"
        isModal = false
        ButtonGroup().apply {
            add(sendToLinear)
            add(localOnly)
        }
        init()
        showLinear(linearProject)
        updateRecording()
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun createCenterPanel(): JComponent {
        val captures = FormBuilder.createFormBuilder().addComponent(includeScreenshot)
        screenshot?.let { captures.addComponent(JBLabel(ImageIcon(thumbnail(it))).apply { border = JBUI.Borders.emptyLeft(24) }) }
        captures.addComponent(row(recordButton, recordingStatus, discardRecording))
        val origin = errors.firstNotNullOfOrNull { it.origin }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Título:", titleField)
            .addLabeledComponent("Onde aconteceu:", mechanismBox)
            .addLabeledComponent("O que aconteceu:", scroll(description), true)
            .addLabeledComponent("Passos para reproduzir:", scroll(steps), true)
            .addLabeledComponent("O que era esperado:", scroll(expected), true)
            .addComponent(TitledSeparator("Capturas"))
            .addComponent(captures.panel)
            .addComponent(TitledSeparator("Contexto"))
            .addComponent(includeErrors)
            .addComponent(comment(origin?.let { "Origem provável no plugin: $it" } ?: "O relatório aponta o pacote do mecanismo escolhido acima."))
            .addComponent(comment("Também vão as versões do plugin, da IDE, do Java e do sistema, a aba ativa e o arquivo aberto."))
            .addComponent(TitledSeparator("Destino"))
            .addComponent(row(sendToLinear, configureLinear))
            .addComponent(localOnly)
            .addLabeledComponent("Pasta local:", folderField)
            .addComponentToRightColumn(row(useProjectFolder, useDefaultFolder))
            .addComponentToRightColumn(comment("O bug.md e as capturas são gerados aqui nos dois casos, numa pasta por relatório."))
            .panel
            .apply { preferredSize = JBUI.size(640, preferredSize.height) }
    }

    override fun doValidate(): ValidationInfo? = when {
        titleField.text.isBlank() -> ValidationInfo("Dê um título ao bug.", titleField)
        description.text.isBlank() -> ValidationInfo("Conte o que aconteceu.", description)
        recorder != null -> ValidationInfo("Pare a gravação antes de enviar.")
        chosenFolder() == null -> ValidationInfo("Escolha uma pasta válida para o relatório.", folderField.textField)
        else -> null
    }

    private fun chosenFolder(): Path? =
        folderField.text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }

    private fun linearChosen() = sendToLinear.isEnabled && sendToLinear.isSelected

    override fun doOKAction() {
        val recorded = recording
        val report = BugReport(
            title = titleField.text.trim(),
            mechanism = mechanismBox.item ?: Mechanism.BASE,
            description = description.text,
            steps = steps.text,
            expected = expected.text,
            errors = if (includeErrors.isSelected) errors else emptyList(),
            context = context,
            screenshot = includeScreenshot.isSelected && screenshot != null,
            recording = recorded != null,
        )
        val folder = chosenFolder() ?: return
        submitted = true
        BugReports.folder = folder
        if (sendToLinear.isEnabled) BugReports.localOnly = localOnly.isSelected
        BugReports.submit(project, report, screenshot.takeIf { report.screenshot }, recorded?.file, folder, linearChosen())
        super.doOKAction()
    }

    override fun dispose() {
        recorder?.stop()
        if (!submitted) recording?.file?.let(Files::deleteIfExists)
        super.dispose()
    }

    /** Esconde o formulário e grava a janela da IDE até o usuário parar pela notificação ou o tempo acabar. */
    private fun startRecording() {
        val area = area ?: return
        discardRecording()
        val file = Files.createTempFile("iron-dome-bug-", ".gif")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(RECORDING_GROUP).createNotification(
            "Gravando a janela da IDE para o relatório de bug",
            "Reproduza o problema. A gravação para sozinha em ${GifRecorder.MAX_SECONDS} s.",
            NotificationType.INFORMATION,
        )
        val recorder = GifRecorder(area, file) { result ->
            notification.expire()
            finishRecording(file, result)
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Parar Gravação") { recorder.stop() })
        this.recorder = recorder
        window.isVisible = false
        notification.notify(project)
        recorder.start()
    }

    private fun finishRecording(file: Path, result: Result<GifRecorder.Recording>) {
        recorder = null
        if (isDisposed) {
            Files.deleteIfExists(file)
            return
        }
        result
            .onSuccess { recording = it }
            .onFailure {
                Files.deleteIfExists(file)
                recordingError = "Falha na gravação: ${it.message ?: it.javaClass.simpleName}"
            }
        updateRecording()
        window.isVisible = true
        window.toFront()
    }

    private fun discardRecording() {
        recording?.file?.let(Files::deleteIfExists)
        recording = null
        recordingError = null
        updateRecording()
    }

    private fun updateRecording() {
        val recorded = recording
        recordingStatus.text = when {
            recorded != null -> "GIF gravado: ${recorded.seconds} s, ${StringUtil.formatFileSize(recorded.bytes)}"
            recordingError != null -> recordingError
            area == null -> "Gravação indisponível: a janela da IDE não está visível"
            else -> "Grava a janela da IDE por até ${GifRecorder.MAX_SECONDS} s enquanto você reproduz o bug"
        }
        recordingStatus.foreground = if (recordingError != null && recorded == null) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        recordButton.text = if (recorded != null) "Gravar de Novo" else "Gravar GIF"
        discardRecording.isVisible = recorded != null
    }

    /** A API key fica no cofre de senhas, que pode ser lento: lido fora da EDT. */
    private fun refreshLinear() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val project = LinearSettings.projectLabel(LinearSettings.project).takeIf { LinearSettings.configured }
            ApplicationManager.getApplication().invokeLater({ if (!isDisposed) showLinear(project) }, ModalityState.any())
        }
    }

    /** [project] é nulo enquanto falta a API key. */
    private fun showLinear(project: String?) {
        sendToLinear.isEnabled = project != null
        sendToLinear.text = if (project != null) "Enviar ao Linear, no projeto $project" else "Enviar ao Linear (falta a API key)"
        configureLinear.text = if (project != null) "Alterar…" else "Configurar Linear…"
        if (project != null && !BugReports.localOnly) sendToLinear.isSelected = true else localOnly.isSelected = true
        updateOkText()
    }

    private fun updateOkText() {
        setOKButtonText(if (linearChosen()) "Enviar" else "Gerar")
    }

    private fun textArea(hint: String) = JBTextArea(3, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = hint
    }

    private fun scroll(area: JBTextArea) = JBScrollPane(area).apply { preferredSize = JBUI.size(600, 72) }

    private fun row(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        components.forEach(::add)
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun thumbnail(image: BufferedImage): Image {
        val width = JBUI.scale(THUMBNAIL_WIDTH)
        return image.getScaledInstance(width, (image.height * width.toDouble() / image.width).toInt().coerceAtLeast(1), Image.SCALE_SMOOTH)
    }

    companion object {
        const val RECORDING_GROUP = "Iron Dome IA: Gravação"
        private const val THUMBNAIL_WIDTH = 320
    }
}
