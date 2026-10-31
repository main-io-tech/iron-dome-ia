package com.mio.irondome.escudo

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowTab
import javax.swing.Icon
import javax.swing.JComponent

class TestsTab : ReviewToolWindowTab {

    override val title: String = "Escudo"
    override val icon: Icon = IronDomeIcons.escudo
    override val description = "Executa todos os testes JUnit do projeto e mostra se passaram, agrupados pela chave do @DisplayName das classes"

    override fun createComponent(project: Project, toolWindow: ToolWindow): JComponent = TestsPanel(project)
}
