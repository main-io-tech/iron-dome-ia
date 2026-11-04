package com.mio.irondome.escudo

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.ReviewToolWindowFactory
import com.mio.irondome.base.plural
import com.mio.irondome.defesa.ClassSnapshot
import com.mio.irondome.defesa.DefenseListener
import com.mio.irondome.defesa.DefenseSettings
import com.mio.irondome.defesa.DefenseStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * O Escudo que reage ao que muda: quando o raio de uma classe salva fica pronto, sugere os testes que cobrem ela e
 * as que dependem dela, ou os roda sozinho quando o código para de mudar.
 */
class RadiusTestsListener(private val project: Project) : DefenseListener {

    override fun radiusComputed(snapshot: ClassSnapshot) {
        if (snapshot.radiusTests.isEmpty()) return
        when {
            DefenseSettings.shieldAutoRun -> RadiusTests.scheduleRun(project, snapshot)
            DefenseSettings.shieldSuggest -> RadiusTests.suggest(project, snapshot)
        }
    }
}

object RadiusTests {

    private const val AUTO_RUN_DELAY_MS = 5_000L
    private const val SUGGEST_AGAIN_MS = 15 * 60 * 1000L
    private val suggested = ConcurrentHashMap<String, Long>()
    private val scheduled = ConcurrentHashMap<Project, Long>()

    /** Abre o Escudo (sem roubar o foco quando [activate] é falso) e roda os testes do raio de [snapshot]. */
    fun run(project: Project, snapshot: ClassSnapshot, activate: Boolean = true) {
        ApplicationManager.getApplication().invokeLater({
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewToolWindowFactory.ID) ?: return@invokeLater
            val content = toolWindow.contentManager.findContent(TestsTab.TITLE) ?: return@invokeLater
            val panel = content.component as? TestsPanel ?: return@invokeLater
            if (activate) toolWindow.activate { toolWindow.contentManager.setSelectedContent(content) }
            if (!panel.runClasses(snapshot.radiusTests, snapshot.name) && activate) {
                notify(project, "O Escudo já está rodando testes", "Espere a execução atual terminar.", NotificationType.WARNING)
            }
        }, project.disposed)
    }

    /** Espera o código parar de mudar: só a última classe salva no intervalo roda. */
    fun scheduleRun(project: Project, snapshot: ClassSnapshot) {
        val stamp = System.nanoTime()
        scheduled[project] = stamp
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (scheduled[project] == stamp && !project.isDisposed) run(project, snapshot, activate = false)
        }, AUTO_RUN_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun suggest(project: Project, snapshot: ClassSnapshot) {
        val key = "${project.locationHash}|${snapshot.qualifiedName}|${snapshot.radiusTests.hashCode()}"
        val now = System.currentTimeMillis()
        val last = suggested[key]
        if (last != null && now - last < SUGGEST_AGAIN_MS) return
        suggested[key] = now
        val content = "${plural(snapshot.radiusTests.size, "teste cobre", "testes cobrem")} ${snapshot.name}" +
            (if (snapshot.dependents > 0) " e as ${plural(snapshot.dependents, "classe que depende", "classes que dependem")} dela" else "") +
            ": ${snapshot.radiusTests.take(4).joinToString(", ") { it.substringAfterLast('.') }}" +
            (if (snapshot.radiusTests.size > 4) " e mais ${snapshot.radiusTests.size - 4}" else "") + "."
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification("Escudo: testes do raio de ${snapshot.name}", content, NotificationType.INFORMATION)
            .setIcon(IronDomeIcons.escudo)
            .addAction(NotificationAction.createSimpleExpiring("Rodar Testes do Raio") { run(project, snapshot) })
            .addAction(NotificationAction.createSimpleExpiring("Rodar Sempre Sozinho") {
                DefenseSettings.shieldAutoRun = true
                run(project, snapshot)
            })
            .notify(project)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, type)
            .setIcon(IronDomeIcons.escudo)
            .notify(project)
    }
}

/** Roda os testes do raio da última classe salva. */
class RunRadiusTestsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val snapshot = e.project?.let { DefenseStatus.getInstance(it).lastSnapshot }
        e.presentation.isEnabled = snapshot != null && snapshot.radiusTests.isNotEmpty()
        e.presentation.text = snapshot?.takeIf { it.radiusTests.isNotEmpty() }
            ?.let { "Rodar os ${it.radiusTests.size} Testes do Raio de ${it.name}" } ?: "Rodar Testes do Raio"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DefenseStatus.getInstance(project).lastSnapshot?.let { RadiusTests.run(project, it) }
    }
}
