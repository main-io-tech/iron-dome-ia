package com.mio.irondome.interceptor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.Tone
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * A IA trabalhando, dentro da aba: o que ela está fazendo, há quanto tempo e o botão de cancelar. Aparece na
 * aba que pediu a execução ([AgentTask.origin]) e sempre no Interceptador; execuções sem aba de origem,
 * como as da Caixa-Preta, aparecem em todas.
 */
class AgentProgressPanel(private val project: Project, private val tab: String, parent: Disposable) : JPanel(BorderLayout(JBUI.scale(10), 0)) {

    private val runner = AgentRunner.getInstance(project)
    private val spinner = AsyncProcessIcon("IronDome.Agent").apply { isOpaque = false }
    private val title = JBLabel().apply { font = JBFont.label().asBold() }
    private val step = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val detail = JBLabel().apply {
        font = JBFont.small().asItalic()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val bar = JProgressBar().apply {
        isIndeterminate = true
        putClientProperty("ProgressBar.stripeWidth", JBUI.scale(2))
        preferredSize = JBUI.size(120, 4)
    }
    private val cancel = ActionLink("Cancelar") { runner.progress?.cancel(); update() }
    private val showTerminal = ActionLink("Mostrar Terminal") {
        ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_ID)?.activate(null)
    }
    private val timer = Timer(TICK_MS) { update() }

    init {
        isOpaque = true
        background = Tone.BLUE.background
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12))
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(spinner, BorderLayout.NORTH)
        }, BorderLayout.WEST)
        add(JPanel(VerticalLayout(JBUI.scale(3), SwingConstants.LEFT)).apply {
            isOpaque = false
            add(title)
            add(step)
            add(detail)
            add(bar)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(showTerminal)
            add(cancel)
        }, BorderLayout.EAST)
        isVisible = false
        project.messageBus.connect(parent).subscribe(
            AgentRunner.RUNS,
            AgentRunner.Listener { ApplicationManager.getApplication().invokeLater({ update() }, project.disposed) },
        )
        Disposer.register(parent) { timer.stop() }
        Disposer.register(parent, spinner)
        update()
    }

    private fun shows(progress: AgentProgress): Boolean =
        tab == InterceptorTab.TITLE || progress.task.origin == null || progress.task.origin == tab

    private fun update() {
        val progress = runner.progress?.takeIf(::shows)
        val visible = progress != null
        if (visible != isVisible) {
            isVisible = visible
            revalidate()
            repaint()
        }
        if (progress == null) {
            timer.stop()
            spinner.suspend()
            return
        }
        if (!timer.isRunning) timer.start()
        spinner.resume()
        title.text = if (progress.inTerminal) "IA trabalhando no terminal: ${progress.task.title}" else "IA trabalhando: ${progress.task.title}"
        showTerminal.isVisible = progress.inTerminal
        cancel.text = if (progress.inTerminal) "Parar de Esperar" else "Cancelar"
        val elapsed = (System.currentTimeMillis() - progress.startedAt) / 1000
        val clock = "%d:%02d".format(elapsed / 60, elapsed % 60)
        step.text = listOf(if (progress.cancelled) "Cancelando…" else progress.step, clock, progress.provider.title).joinToString("  ·  ")
        detail.text = progress.detail
        detail.isVisible = progress.detail.isNotBlank() && !progress.cancelled
        cancel.isEnabled = !progress.cancelled
    }

    private companion object {
        const val TICK_MS = 500
        const val TERMINAL_ID = "Terminal"
    }
}

/**
 * Enquanto a IA trabalha, o ícone do painel do Iron Dome IA e o da aba que pediu viram o ícone de IA, para o
 * andamento ser visto mesmo com o painel fechado ou noutra aba. A barra lateral só aceita ícones que escalam,
 * então nada de ícone animado ali; o indicador girando fica na barra de progresso da aba.
 */
internal object AgentActivity {

    private val originals = mutableMapOf<String, Icon?>()

    fun started(project: Project, task: AgentTask) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewToolWindowFactory.ID) ?: return
        toolWindow.setIcon(IronDomeIcons.ia)
        val content = toolWindow.contentManager.findContent(task.origin ?: InterceptorTab.TITLE) ?: return
        originals[content.displayName] = content.icon
        content.icon = IronDomeIcons.ia
    }

    fun finished(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewToolWindowFactory.ID) ?: return
        toolWindow.setIcon(IronDomeIcons.toolWindow)
        originals.forEach { (name, icon) -> toolWindow.contentManager.findContent(name)?.icon = icon }
        originals.clear()
    }
}
