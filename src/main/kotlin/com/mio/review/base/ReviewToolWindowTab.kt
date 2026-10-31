package com.mio.review.base

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Aba extra da tool window, registrada por partes do plugin que dependem de outros plugins (como a de
 * dependências, que exige o suporte a Java). Se o componente for `Disposable`, é descartado com a aba.
 */
interface ReviewToolWindowTab {
    val title: String
    val icon: Icon? get() = null
    val description: String? get() = null

    fun createComponent(project: Project, toolWindow: ToolWindow): JComponent

    companion object {
        val EP_NAME = ExtensionPointName<ReviewToolWindowTab>("com.mio.review.toolWindowTab")
    }
}
