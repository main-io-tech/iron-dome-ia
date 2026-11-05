package com.mio.irondome.base

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.mio.irondome.base.web.WebTab
import com.mio.irondome.interceptor.InterceptorPanel
import com.mio.irondome.interceptor.InterceptorTab
import com.mio.irondome.interceptor.InterceptorWeb
import com.mio.irondome.radar.CommentsPanel
import com.mio.irondome.sentinela.ChangesPanel
import javax.swing.Icon
import javax.swing.JComponent

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Arrastar uma aba reordena, e levá-la para um lado divide o painel: dois mecanismos lado a lado.
        toolWindow.component.putClientProperty(ToolWindowContentUi.ALLOW_DND_FOR_TABS, true)
        val actions = ActionManager.getInstance()
        toolWindow.setTitleActions(listOfNotNull(ProjectRootSelector(), actions.getAction(TabErrorPanel.REPORT_BUG_ACTION)))
        val gear = listOf(TabErrorPanel.COMPATIBILITY_ACTION, TabErrorPanel.ERROR_LOG_ACTION).mapNotNull(actions::getAction)
        if (gear.isNotEmpty()) toolWindow.setAdditionalGearActions(DefaultActionGroup(gear))
        addTabs(project, toolWindow)
        // Outro projeto escolhido: as abas renascem com os comentários, as alterações e o Git dele.
        project.messageBus.connect(toolWindow.disposable).subscribe(IronDomeRoot.Listener.TOPIC, IronDomeRoot.Listener {
            val selected = toolWindow.contentManager.selectedContent?.displayName
            contents(toolWindow).forEach { it.manager?.removeContent(it, true) }
            addTabs(project, toolWindow)
            selected?.let { findContent(toolWindow, it) }?.let(::select)
        })
    }

    private fun addTabs(project: Project, toolWindow: ToolWindow) {
        add(toolWindow, RADAR, IronDomeIcons.radar, "Os alvos marcados para a IA: comentários da revisão aberta e o histórico") {
            CommentsPanel(project)
        }
        add(toolWindow, "Sentinela", IronDomeIcons.sentinela, "O perímetro vigiado: arquivos com alterações ainda não commitadas") {
            ChangesPanel(project)
        }
        for (tab in ReviewToolWindowTab.EP_NAME.extensionList) {
            add(toolWindow, tab.title, tab.icon, tab.description) { tab.createComponent(project, toolWindow) }
        }
        add(toolWindow, InterceptorTab.TITLE, IronDomeIcons.arsenal, "A IA: qual CLI usar, o teste da integração e as execuções recentes") {
            val web = if (WebTab.supported) {
                runCatching { InterceptorWeb(project) }
                    .onFailure { if (it is ControlFlowException) throw it else LOG.warn("O Arsenal web não abriu; vale o painel sem navegador", it) }
                    .getOrNull()
            } else {
                null
            }
            web ?: InterceptorPanel(project)
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
        @Suppress("UNCHECKED_CAST")
        val tracked = toolWindow.component.getClientProperty(CONTENTS_KEY) as? MutableList<Content>
            ?: mutableListOf<Content>().also { toolWindow.component.putClientProperty(CONTENTS_KEY, it) }
        tracked.removeAll { it.manager == null }
        tracked += content
    }

    companion object {
        const val ID = "Iron Dome IA"
        const val RADAR = "Radar"
        private const val CONTENTS_KEY = "IronDome.Contents"
        private val LOG = logger<ReviewToolWindowFactory>()

        /**
         * As abas do painel, também as arrastadas para uma divisão: essas vão para outro gerenciador de abas, que
         * `toolWindow.contentManager` não enxerga.
         */
        fun contents(toolWindow: ToolWindow): List<Content> =
            (toolWindow.component.getClientProperty(CONTENTS_KEY) as? List<*>).orEmpty()
                .filterIsInstance<Content>()
                .filter { it.manager != null }

        fun findContent(toolWindow: ToolWindow, title: String): Content? = contents(toolWindow).firstOrNull { it.displayName == title }

        fun select(content: Content) {
            content.manager?.setSelectedContent(content)
        }

        /** Abre o painel do Iron Dome IA na aba [title]. */
        fun showTab(project: Project, title: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                findContent(toolWindow, title)?.let(::select)
            }
        }
    }
}

/** No título do painel, com mais de um projeto na janela: qual deles o Iron Dome vigia. */
internal class ProjectRootSelector : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val candidates = project?.let { IronDomeRoot.getInstance(it).candidates() }.orEmpty()
        e.presentation.isVisible = candidates.size > 1
        if (project == null || candidates.size <= 1) return
        val current = project.rootPath()
        e.presentation.text = candidates.firstOrNull { it.path == current }?.name ?: current?.substringAfterLast('/') ?: "Projeto"
        e.presentation.icon = AllIcons.Nodes.Module
        e.presentation.description = "O projeto que o Iron Dome vigia: os comentários, as alterações, o Git e a pasta iron-dome/ são dele"
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return DefaultActionGroup()
        val root = IronDomeRoot.getInstance(project)
        val current = project.rootPath()
        return DefaultActionGroup(
            root.candidates().map { candidate ->
                object : DumbAwareToggleAction(candidate.name, candidate.path, AllIcons.Nodes.Module) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = candidate.path == current
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        root.select(candidate.path)?.let { Messages.showWarningDialog(project, it, "Trocar o Projeto do Iron Dome") }
                    }
                }
            },
        )
    }
}

internal fun refreshAction(onRefresh: () -> Unit): AnAction =
    object : DumbAwareAction("Atualizar", null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }
