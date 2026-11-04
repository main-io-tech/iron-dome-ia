package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.mio.irondome.interceptor.InterceptorPanel
import com.mio.irondome.interceptor.InterceptorTab
import com.mio.irondome.radar.CommentsPanel
import com.mio.irondome.sentinela.ChangesPanel
import javax.swing.Icon
import javax.swing.JComponent

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val actions = ActionManager.getInstance()
        actions.getAction(TabErrorPanel.REPORT_BUG_ACTION)?.let { toolWindow.setTitleActions(listOf(it)) }
        val gear = listOf(TabErrorPanel.COMPATIBILITY_ACTION, TabErrorPanel.ERROR_LOG_ACTION).mapNotNull(actions::getAction)
        if (gear.isNotEmpty()) toolWindow.setAdditionalGearActions(DefaultActionGroup(gear))
        add(toolWindow, RADAR, IronDomeIcons.radar, "Os alvos marcados para a IA: comentários da revisão aberta e o histórico") {
            CommentsPanel(project)
        }
        add(toolWindow, "Sentinela", IronDomeIcons.sentinela, "O perímetro vigiado: arquivos com alterações ainda não commitadas") {
            ChangesPanel(project)
        }
        for (tab in ReviewToolWindowTab.EP_NAME.extensionList) {
            add(toolWindow, tab.title, tab.icon, tab.description) { tab.createComponent(project, toolWindow) }
        }
        add(toolWindow, InterceptorTab.TITLE, IronDomeIcons.interceptador, "A IA: qual CLI usar, o teste da integração e as execuções recentes") {
            InterceptorPanel(project)
        }
    }

    /** Uma aba que não abre não pode levar as outras junto: o erro vai para o log e para o lugar dela. */
    private fun add(toolWindow: ToolWindow, title: String, icon: Icon?, description: String?, create: () -> JComponent) {
        val missing = IdeSupport.missing[title]
        val component = if (missing != null) {
            UnavailableTabPanel(title, missing)
        } else try {
            create().also { TabFailures.clear(title) }
        } catch (error: Throwable) {
            if (error is ControlFlowException || error is VirtualMachineError) throw error
            LOG.warn("A aba $title do Iron Dome IA não pôde ser aberta", error)
            TabFailures.record(title, error)
            TabErrorPanel(title, error)
        }
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
        const val RADAR = "Radar"
        private val LOG = logger<ReviewToolWindowFactory>()

        /** Abre o painel do Iron Dome IA na aba [title]. */
        fun showTab(project: Project, title: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                toolWindow.contentManager.findContent(title)?.let(toolWindow.contentManager::setSelectedContent)
            }
        }
    }
}

internal fun refreshAction(onRefresh: () -> Unit): AnAction =
    object : DumbAwareAction("Atualizar", null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }
