package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.mio.irondome.radar.CommentsPanel
import com.mio.irondome.sentinela.ChangesPanel
import javax.swing.Icon
import javax.swing.JComponent

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        add(toolWindow, CommentsPanel(project), "Radar", IronDomeIcons.radar, "Os alvos marcados para a IA: comentários da revisão aberta e o histórico")
        add(toolWindow, ChangesPanel(project), "Sentinela", IronDomeIcons.sentinela, "O perímetro vigiado: arquivos com alterações ainda não commitadas")
        for (tab in ReviewToolWindowTab.EP_NAME.extensionList) {
            add(toolWindow, tab.createComponent(project, toolWindow), tab.title, tab.icon, tab.description)
        }
    }

    private fun add(toolWindow: ToolWindow, component: JComponent, title: String, icon: Icon?, description: String?) {
        val content = ContentFactory.getInstance().createContent(component, title, false).apply {
            this.icon = icon
            this.description = description
            putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            (component as? Disposable)?.let(::setDisposer)
        }
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Iron Dome IA"
    }
}

internal fun refreshAction(onRefresh: () -> Unit): AnAction =
    object : DumbAwareAction("Atualizar", null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }
