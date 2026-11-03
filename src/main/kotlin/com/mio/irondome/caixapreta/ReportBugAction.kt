package com.mio.irondome.caixapreta

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction

/**
 * Abre o formulário de bug com o print da IDE, os erros do plugin no log e o ambiente. O print é tirado
 * antes do formulário aparecer, para mostrar a IDE como estava.
 */
class ReportBugAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = BugContext.collect(project)
        val area = ScreenCapture.windowArea(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(MENU_CLOSE_DELAY_MS)
            val screenshot = area?.let(ScreenCapture::screenshot)
            val errors = runCatching { PluginLog.recentErrors() }.getOrDefault(emptyList())
            val linearProject = runCatching { LinearSettings.projectLabel(LinearSettings.project).takeIf { LinearSettings.configured } }.getOrNull()
            val mechanism = context.activeTab
                ?: errors.firstNotNullOfOrNull { error -> error.origin?.let(Mechanism::ofFrame) }
                ?: Mechanism.RADAR
            ApplicationManager.getApplication().invokeLater({
                ReportBugDialog(project, area, screenshot, mechanism, errors, context.entries, linearProject).show()
            }, project.disposed)
        }
    }

    private companion object {
        /** O menu que disparou a ação ainda pode estar na tela logo depois do clique. */
        const val MENU_CLOSE_DELAY_MS = 250L
    }
}
