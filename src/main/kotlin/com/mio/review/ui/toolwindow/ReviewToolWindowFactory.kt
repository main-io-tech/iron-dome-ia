package com.mio.review.ui.toolwindow

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

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val comments = CommentsPanel(project)
        val changes = ChangesPanel(project)
        toolWindow.contentManager.addContent(factory.createContent(comments, "Comentários", false).apply { setDisposer(comments) })
        toolWindow.contentManager.addContent(factory.createContent(changes, "Alterações", false).apply { setDisposer(changes) })
        for (tab in ReviewToolWindowTab.EP_NAME.extensionList) {
            val component = tab.createComponent(project, toolWindow)
            toolWindow.contentManager.addContent(
                factory.createContent(component, tab.title, false).apply { (component as? Disposable)?.let(::setDisposer) },
            )
        }
    }

    companion object {
        const val ID = "Mio Review AI"
    }
}

internal fun refreshAction(onRefresh: () -> Unit): AnAction =
    object : DumbAwareAction("Atualizar", null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }
