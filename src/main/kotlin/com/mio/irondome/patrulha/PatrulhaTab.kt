package com.mio.irondome.patrulha

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowTab
import javax.swing.Icon
import javax.swing.JComponent

class PatrulhaTab : ReviewToolWindowTab {

    override val title: String = TITLE
    override val icon: Icon = IronDomeIcons.patrulha
    override val description = "Os pull requests abertos no GitHub: comente com o Radar, envie os pedidos de alteração e verifique se foram atendidos"

    override fun createComponent(project: Project, toolWindow: ToolWindow): JComponent = PatrulhaPanel(project)

    companion object {
        const val TITLE = "Patrulha"
    }
}
