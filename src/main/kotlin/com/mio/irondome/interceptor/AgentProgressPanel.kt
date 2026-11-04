package com.mio.irondome.interceptor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IconTile
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.Pill
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.StatChip
import com.mio.irondome.base.Tone
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * A IA trabalhando, dentro da aba: a tarefa, o passo de agora com a trilha dos anteriores, os contadores de
 * ações, arquivos e tempo, uma barra pulsante e os botões de cancelar e de abrir o terminal. Aparece na aba
 * que pediu a execução ([AgentTask.origin]) e sempre no Interceptador; execuções sem aba de origem, como as
 * da Caixa-Preta, aparecem em todas.
 */
class AgentProgressPanel(private val project: Project, private val tab: String, parent: Disposable) : JPanel(BorderLayout(JBUI.scale(12), 0)) {

    private val runner = AgentRunner.getInstance(project)
    private val tone = Tone.TEAL
    private val spinner = AsyncProcessIcon("IronDome.Agent").apply { isOpaque = false }
    private val heading = JBLabel("IA trabalhando").apply {
        font = JBFont.small().asBold()
        foreground = tone.foreground
    }
    private val title = JBLabel().apply { font = JBFont.label().asBold() }
    private val pills = JPanel(HorizontalLayout(JBUI.scale(6), SwingConstants.CENTER)).apply { isOpaque = false }
    private val step = JBLabel().apply { font = JBFont.label() }
    private val trail = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val detail = JBLabel().apply {
        font = JBFont.small().asItalic()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val chips = JPanel(HorizontalLayout(JBUI.scale(6), SwingConstants.CENTER)).apply { isOpaque = false }
    private val bar = PulseBar(tone)
    private val cancel = JButton("Cancelar").apply {
        icon = AllIcons.Actions.Suspend
        addActionListener { runner.progress?.cancel(); update() }
    }
    private val showTerminal = JButton("Mostrar Terminal").apply {
        icon = AllIcons.Nodes.Console
        addActionListener { ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_ID)?.activate(null) }
    }
    private val timer = Timer(TICK_MS) { update() }

    init {
        isOpaque = true
        background = tone.background
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 14, 10, 14))
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(IconTile(IronDomeIcons.ia, tone, scale = 1.35f, size = 34), BorderLayout.NORTH)
        }, BorderLayout.WEST)
        val headingLine = JPanel(HorizontalLayout(JBUI.scale(8), SwingConstants.CENTER)).apply {
            isOpaque = false
            add(spinner)
            add(heading)
            add(pills)
        }
        val stepLine = JPanel(HorizontalLayout(JBUI.scale(6), SwingConstants.CENTER)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.Actions.Forward))
            add(step)
        }
        add(JPanel(VerticalLayout(JBUI.scale(3), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(headingLine)
            add(title)
            add(stepLine)
            add(trail)
            add(detail)
            add(chips)
            add(bar)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
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
            bar.running = false
            return
        }
        if (!timer.isRunning) timer.start()
        spinner.resume()
        bar.running = !progress.cancelled
        heading.text = when {
            progress.cancelled -> "Cancelando…"
            progress.inTerminal -> "IA trabalhando no terminal"
            else -> "IA trabalhando"
        }
        title.text = progress.task.title
        pills.removeAll()
        pills.add(Pill(progress.provider.title, tone, bold = true))
        pills.add(Pill(if (progress.task.write) "Pode alterar arquivos" else "Só leitura", if (progress.task.write) Tone.ORANGE else Tone.GRAY))
        if (progress.inTerminal) pills.add(Pill("Terminal da IDE", Tone.PURPLE))
        progress.task.origin?.takeIf { it != tab }?.let { pills.add(Pill("Origem: $it", Tone.GRAY)) }
        step.text = progress.step
        val previous = progress.trail
        trail.text = if (previous.isEmpty()) "" else "Antes: " + previous.joinToString("  ‹  ")
        trail.isVisible = previous.isNotEmpty()
        detail.text = progress.detail
        detail.isVisible = progress.detail.isNotBlank() && !progress.cancelled
        val elapsed = (System.currentTimeMillis() - progress.startedAt) / 1000
        chips.removeAll()
        chips.add(StatChip("%d:%02d".format(elapsed / 60, elapsed % 60), "decorridos", tone))
        if (progress.actions > 0) chips.add(StatChip(progress.actions.toString(), if (progress.actions == 1) "ação" else "ações", Tone.BLUE))
        if (progress.filesTouched > 0) {
            chips.add(StatChip(progress.filesTouched.toString(), if (progress.filesTouched == 1) "arquivo alterado" else "arquivos alterados", Tone.ORANGE))
        }
        showTerminal.isVisible = progress.inTerminal
        cancel.text = if (progress.inTerminal) "Parar de Esperar" else "Cancelar"
        cancel.isEnabled = !progress.cancelled
        bar.tick()
        revalidate()
        repaint()
    }

    private companion object {
        const val TICK_MS = 500
        const val TERMINAL_ID = "Terminal"
    }
}

/** Barra fina com um brilho que percorre o trilho enquanto [running], para dizer "em andamento" sem porcentagem. */
class PulseBar(private val tone: Tone) : JComponent() {

    var running = true
    private var phase = 0f
    private val animation = Timer(40) {
        phase = (phase + 0.012f) % 1f
        repaint()
    }

    init {
        isOpaque = false
    }

    fun tick() {
        if (running && !animation.isRunning) animation.start()
        if (!running && animation.isRunning) animation.stop()
    }

    override fun removeNotify() {
        animation.stop()
        super.removeNotify()
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(160), JBUI.scale(8))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val height = JBUI.scale(4).toFloat()
            val top = (this.height - height) / 2
            val track = RoundRectangle2D.Float(0f, top, width.toFloat(), height, height, height)
            g2.color = Tone.GRAY.background
            g2.fill(track)
            if (!running) return
            g2.clip(track)
            val span = width * 0.35f
            val x = -span + (width + span) * phase
            val accent = tone.accent
            val clear = Color(accent.red, accent.green, accent.blue, 0)
            g2.paint = GradientPaint(x, 0f, clear, x + span / 2, 0f, accent)
            g2.fill(RoundRectangle2D.Float(x, top, span / 2, height, height, height))
            g2.paint = GradientPaint(x + span / 2, 0f, accent, x + span, 0f, clear)
            g2.fill(RoundRectangle2D.Float(x + span / 2, top, span / 2, height, height, height))
        } finally {
            g2.dispose()
        }
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
