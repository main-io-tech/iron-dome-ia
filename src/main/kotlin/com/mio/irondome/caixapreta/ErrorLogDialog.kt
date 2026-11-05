package com.mio.irondome.caixapreta

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.interceptor.AgentRunner
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Iron Dome IA: Ver Logs de Erro, no menu Help, na engrenagem do painel e nas abas que não abriram. */
class ShowErrorLogAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ErrorLogDialog.show(e.project ?: return)
    }
}

/**
 * Os erros e avisos do plugin no log da IDE, agrupados por ocorrência, com a pilha completa, para copiar
 * ou abrir o Reportar Bug já com o erro escolhido. Não é modal, para ficar aberto enquanto se reproduz o erro.
 */
internal class ErrorLogDialog private constructor(private val project: Project, private var errors: List<PluginError>) : DialogWrapper(project, true) {

    private val model = CollectionListModel<ErrorGroup>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Renderer()
        emptyText.text = "Nenhum erro do Iron Dome IA no log da IDE"
        emptyText.appendLine("Quando o plugin registrar um problema no idea.log, ele aparece aqui.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }
    private val detail = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
        lineWrap = false
    }
    private val mechanismFilter = ComboBox(arrayOf<Mechanism?>(null) + Mechanism.entries).apply {
        renderer = SimpleListCellRenderer.create("Todos os mecanismos") { it?.title ?: "Todos os mecanismos" }
        addActionListener { reload() }
    }
    private val showWarnings = JBCheckBox("Mostrar avisos (WARN)", true).apply { addActionListener { reload() } }
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val reportAction = object : AbstractAction("Reportar Este Erro") {
        override fun actionPerformed(e: ActionEvent) {
            val group = list.selectedValue ?: return
            close(OK_EXIT_CODE)
            ReportBugAction.open(project, group.latest)
        }
    }

    /** Só com a IA configurada e testada, na aba Arsenal. */
    private val analyzeAction = object : AbstractAction("Analisar com IA", IronDomeIcons.ia) {
        override fun actionPerformed(e: ActionEvent) {
            val group = list.selectedValue ?: return
            close(OK_EXIT_CODE)
            BugAnalysis.analyzeError(project, group)
        }
    }

    init {
        title = "Logs de Erro do Iron Dome IA"
        isModal = false
        setCancelButtonText("Fechar")
        list.addListSelectionListener { if (!it.valueIsAdjusting) showDetail() }
        init()
        reload()
    }

    override fun createActions(): Array<Action> = arrayOf(reportAction, cancelAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : AbstractAction("Copiar Erro") {
            override fun actionPerformed(e: ActionEvent) {
                list.selectedValue?.let { copy(listOf(it)) }
            }
        },
        object : AbstractAction("Copiar Todos") {
            override fun actionPerformed(e: ActionEvent) {
                copy(model.items)
            }
        },
        analyzeAction,
        object : AbstractAction("Abrir idea.log") {
            override fun actionPerformed(e: ActionEvent) {
                val log = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(PluginLog.file) ?: return
                close(OK_EXIT_CODE)
                FileEditorManager.getInstance(project).openFile(log, true)
            }
        },
        object : AbstractAction("Atualizar") {
            override fun actionPerformed(e: ActionEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val fresh = read()
                    ApplicationManager.getApplication().invokeLater({
                        errors = fresh
                        reload()
                    }, project.disposed)
                }
            }
        },
    )

    override fun createCenterPanel(): JComponent {
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JBLabel("Mecanismo:"))
            add(mechanismFilter)
            add(showWarnings)
            add(summary)
        }
        val splitter = OnePixelSplitter(true, 0.4f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = JBScrollPane(detail)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(filters, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(
                JBLabel("<html>Lido de ${PluginLog.file}. Copie um erro para colar numa conversa ou issue, ou use Reportar Este Erro.</html>").apply {
                    componentStyle = UIUtil.ComponentStyle.SMALL
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.SOUTH,
            )
            preferredSize = JBUI.size(900, 600)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    private fun reload() {
        val selected = list.selectedValue?.latest
        val mechanism = mechanismFilter.selectedItem as? Mechanism
        val shown = errors.filter { error ->
            (showWarnings.isSelected || error.level != "WARN") && (mechanism == null || error.mechanism == mechanism)
        }
        val groups = PluginLog.group(shown)
        model.replaceAll(groups)
        summary.text = when {
            groups.isEmpty() -> ""
            groups.size == shown.size -> "${shown.size} ${if (shown.size == 1) "ocorrência" else "ocorrências"}"
            else -> "${groups.size} erros diferentes, ${shown.size} ocorrências"
        }
        val index = groups.indexOfFirst { it.latest == selected }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) list.selectedIndex = index else showDetail()
    }

    private fun showDetail() {
        val group = list.selectedValue
        reportAction.isEnabled = group != null
        analyzeAction.isEnabled = group != null && AgentRunner.getInstance(project).available()
        detail.text = group?.let(PluginLog::describe).orEmpty()
        detail.caretPosition = 0
    }

    private fun copy(groups: List<ErrorGroup>) {
        if (groups.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(PluginLog.markdown(groups)))
    }

    private class Renderer : ColoredListCellRenderer<ErrorGroup>() {
        override fun customizeCellRenderer(list: JList<out ErrorGroup>, value: ErrorGroup?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            val error = value.latest
            icon = if (error.level == "WARN") AllIcons.General.Warning else AllIcons.General.Error
            append(error.time, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ")
            append(error.mechanism?.title ?: "Plugin", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.count > 1) append("  ×${value.count}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append("  ")
            append(error.message.lines().first().take(MESSAGE_MAX).ifBlank { error.exception.orEmpty() })
        }
    }

    companion object {
        private const val LOG_ERRORS = 200
        private const val MESSAGE_MAX = 160

        private fun read(): List<PluginError> =
            runCatching { PluginLog.recentErrors(LOG_ERRORS, includeRotated = true) }.getOrDefault(emptyList())

        /** Lê o log fora da EDT e abre a janela. */
        fun show(project: Project) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val errors = read()
                ApplicationManager.getApplication().invokeLater({ ErrorLogDialog(project, errors).show() }, project.disposed)
            }
        }
    }
}
