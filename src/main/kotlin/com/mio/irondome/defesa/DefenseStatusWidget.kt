package com.mio.irondome.defesa

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.interceptor.InterceptorTab
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.Icon
import javax.swing.JComponent

class DefenseStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = DefenseStatusWidget.ID

    override fun getDisplayName() = "Iron Dome IA: Status da Defesa"

    override fun isAvailable(project: Project) = true

    override fun createWidget(project: Project): StatusBarWidget = DefenseStatusWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar) = true
}

/**
 * Um escudo colorido na status bar resumindo a defesa: verde (sem alvos pendentes, testes passando, risco baixo),
 * amarelo ou vermelho. O tooltip traz os números por mecanismo; o clique abre cada um e as ações da defesa.
 */
class DefenseStatusWidget(private val project: Project) : CustomStatusBarWidget {

    private val label = JBLabel().apply {
        border = JBUI.Borders.empty(0, 6)
        iconTextGap = JBUI.scale(4)
    }

    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showPopup()
        })
        project.messageBus.connect(this).subscribe(DefenseListener.TOPIC, object : DefenseListener {
            override fun statusChanged() = refresh()
        })
        refresh()
    }

    override fun ID() = ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) = Unit

    override fun dispose() = Unit

    private fun refresh() {
        if (project.isDisposed) return
        val status = DefenseStatus.getInstance(project)
        val level = status.level
        val lines = status.lines()
        label.icon = ShieldIcon(level)
        label.text = shortText(status, level)
        label.toolTipText = buildString {
            append("<html><b>Iron Dome IA: ${level.label}</b><br>")
            lines.forEach { line ->
                val color = "#" + Integer.toHexString(line.level.tone.accent.rgb and 0xFFFFFF).padStart(6, '0')
                append("<span style='color:$color'>●</span> <b>${line.mechanism}:</b> ${escape(line.text)}<br>")
            }
            append("<br><i>Clique para abrir os mecanismos e as ações da defesa.</i></html>")
        }
    }

    /** O mais urgente cabe na barra: o resto fica no tooltip. */
    private fun shortText(status: DefenseStatus, level: DefenseLevel): String = when {
        (status.testsFailed ?: 0) > 0 -> "${status.testsFailed} teste(s) falhando"
        status.ciFailing > 0 -> "CI quebrada"
        status.aiHigh > 0 -> "IA: ${status.aiHigh} impacto(s) alto(s)"
        status.lastSnapshot?.let { level != DefenseLevel.GREEN && it.risk.level.ordinal >= 1 } == true ->
            "Raio: ${status.lastSnapshot!!.dependents} afetadas"
        status.pendingTargets > 0 -> "${status.pendingTargets} alvo(s)"
        status.conflicts.isNotEmpty() -> "Fogo amigo"
        else -> "Defesa"
    }

    private fun showPopup() {
        val status = DefenseStatus.getInstance(project)
        val group = DefaultActionGroup()
        status.lines().forEach { line ->
            group.add(object : DumbAwareAction("${line.mechanism}: ${line.text}", null, ShieldIcon(line.level)) {
                override fun actionPerformed(e: AnActionEvent) = ReviewToolWindowFactory.showTab(project, tabOf(line.mechanism))
            })
        }
        group.add(Separator.getInstance())
        RELATED_ACTIONS.mapNotNull(ActionManager.getInstance()::getAction).forEach(group::add)
        if (status.conflicts.isNotEmpty()) {
            group.add(object : DumbAwareAction("Pedir Síntese dos Comentários à IA", null, AllIcons.Actions.IntentionBulb) {
                override fun actionPerformed(e: AnActionEvent) = status.synthesize()
            })
        }
        group.add(object : DumbAwareAction("Configurar Defesa Automática…", null, AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, DefenseConfigurable::class.java)
            }
        })
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Iron Dome IA: ${status.level.label}",
            group,
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        popup.show(RelativePoint(label, Point(0, -popup.content.preferredSize.height)))
    }

    private fun tabOf(mechanism: String) = when (mechanism) {
        "IA" -> InterceptorTab.TITLE
        "Fogo amigo" -> ReviewToolWindowFactory.RADAR
        else -> mechanism
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val ID = "IronDome.DefenseStatus"
        private val RELATED_ACTIONS = listOf("IronDome.RunRadiusTests", "IronDome.BackgroundImpactNow", "IronDome.PatrolNow")
    }
}

/** Um escudo pequeno na cor do nível, desenhado à mão para acompanhar o tema. */
class ShieldIcon(private val level: DefenseLevel) : Icon {
    override fun getIconWidth() = JBUI.scale(14)

    override fun getIconHeight() = JBUI.scale(14)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val w = iconWidth.toDouble()
            val h = iconHeight.toDouble()
            val shape = Path2D.Double().apply {
                moveTo(x + w / 2, y + 0.5)
                lineTo(x + w - 1.0, y + h * 0.18)
                curveTo(x + w - 1.0, y + h * 0.62, x + w * 0.72, y + h * 0.86, x + w / 2, y + h - 0.5)
                curveTo(x + w * 0.28, y + h * 0.86, x + 1.0, y + h * 0.62, x + 1.0, y + h * 0.18)
                closePath()
            }
            g2.color = level.tone.accent
            g2.fill(shape)
            g2.color = if (JBColor.isBright()) level.tone.foreground else level.tone.background
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}
