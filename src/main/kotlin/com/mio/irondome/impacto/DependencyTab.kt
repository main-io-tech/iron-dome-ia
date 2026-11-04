package com.mio.irondome.impacto

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.ReviewToolWindowTab
import javax.swing.Icon
import javax.swing.JComponent

class DependencyTab : ReviewToolWindowTab {

    override val title: String = TITLE
    override val icon: Icon = IronDomeIcons.impacto
    override val description = "Quem depende da classe, de quem ela depende, o risco de alterá-la e os testes que a protegem"

    override fun createComponent(project: Project, toolWindow: ToolWindow): JComponent = DependencyPanel(project, toolWindow)

    companion object {
        const val TITLE = "Raio de Impacto"

        /** Abre a tool window na aba de dependências e entrega o painel a [use]. */
        fun open(project: Project, use: (DependencyPanel) -> Unit) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewToolWindowFactory.ID) ?: return
            toolWindow.activate {
                val content = ReviewToolWindowFactory.findContent(toolWindow, TITLE) ?: return@activate
                ReviewToolWindowFactory.select(content)
                (content.component as? DependencyPanel)?.let(use)
            }
        }
    }
}
