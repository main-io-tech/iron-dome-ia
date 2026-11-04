package com.mio.irondome.impacto

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.util.Alarm
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.defesa.ChangeHistoryService
import com.mio.irondome.defesa.ClassDossier
import com.mio.irondome.defesa.DefenseSettings
import com.mio.irondome.defesa.DefenseStatus
import com.mio.irondome.defesa.Triage
import com.mio.irondome.defesa.TriageResult
import com.mio.irondome.interceptor.AgentResult
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.radar.ReviewCommentService
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * A análise de impacto da IA em segundo plano: quando as alterações ficam paradas por alguns minutos (o
 * desenvolvedor pausou), a triagem decide se vale; valendo, a IA analisa só para ler, o resultado fica pronto no Raio
 * de Impacto e um aviso diz o que ela achou. Com orçamento diário e intervalo mínimo, para não gastar tokens à toa.
 */
@Service(Service.Level.PROJECT)
class BackgroundImpact(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile
    private var lastSignature: String? = null
    @Volatile
    private var lastRunAt = 0L
    @Volatile
    var lastReport: ImpactReport? = null
        private set

    override fun dispose() = Unit

    fun start() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = changed()
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(DefenseSettings.Listener.TOPIC, DefenseSettings.Listener { changed() })
    }

    /** As alterações mudaram: a contagem de minutos parados recomeça. */
    fun changed() {
        alarm.cancelAllRequests()
        if (!DefenseSettings.aiBackgroundImpact) return
        alarm.addRequest({ analyze(manual = false) }, DefenseSettings.aiIdleMinutes * 60_000L)
    }

    /** Pelo botão: sem esperar, sem orçamento nem intervalo; a triagem ainda vale, e o motivo aparece se ela barrar. */
    fun analyzeNow() {
        alarm.cancelAllRequests()
        alarm.addRequest({ analyze(manual = true) }, 0)
    }

    private fun analyze(manual: Boolean) {
        if (project.isDisposed) return
        val runner = AgentRunner.getInstance(project)
        if (!runner.available()) {
            if (manual) notify("IA indisponível", "Configure e teste a IA no Interceptador para a análise automática.", NotificationType.WARNING)
            return
        }
        if (runner.running != null) {
            if (manual) notify("A IA já está trabalhando", "Espere ${runner.running?.title} terminar.", NotificationType.WARNING)
            else alarm.addRequest({ analyze(manual = false) }, RETRY_MS)
            return
        }
        if (!manual && (System.currentTimeMillis() - lastRunAt < DefenseSettings.aiCooldownMinutes * 60_000L || budgetLeft() <= 0)) return

        val radar = project.service<ReviewCommentService>()
        val files = ChangeListManager.getInstance(project).allChanges.mapNotNull { it.virtualFile }
            .filter { it.extension in SOURCE_EXTENSIONS && !ReviewCommentService.isReviewOutput(it.path) }
            .distinct()
        val paths = files.mapNotNull(radar::relativePath)
        if (paths.isEmpty()) {
            if (manual) notify("Nada para analisar", "Nenhuma classe Java ou Kotlin com alteração sem commit.", NotificationType.INFORMATION)
            return
        }
        val signature = files.sortedBy { it.path }.joinToString("|") { "${it.path}@${it.modificationStamp}" }
        if (!manual && signature == lastSignature) return
        lastSignature = signature

        val root = project.basePath ?: return
        val diff = gitDiff(root, paths)
        val status = DefenseStatus.getInstance(project)
        val impactRadar = ImpactRadar.getInstance(project)
        val snapshots = files.take(MAX_SNAPSHOTS).mapNotNull { file ->
            radar.relativePath(file)?.let(status::snapshotOf) ?: runCatching { impactRadar.compute(file) }.getOrNull()?.also(status::publish)
        }
        val missing = ChangeHistoryService.getInstance(project).currentOrLoad().missingPartners(paths)
        val triage = Triage.classify(diff, snapshots.map { it.risk.level }, missing.size)
        if (DefenseSettings.aiTriage && !triage.worthIt) {
            LOG.info("Impacto automático pulado pela triagem: ${triage.reasons}")
            if (manual) notify("A triagem barrou a análise", "${triage.verdict.label}: ${triage.reasons.joinToString(", ")}.", NotificationType.INFORMATION)
            return
        }
        val focus = snapshots.maxByOrNull { it.risk.points }?.qualifiedName ?: run {
            if (manual) notify("Nada para analisar", "As classes alteradas não foram encontradas no índice do projeto.", NotificationType.INFORMATION)
            return
        }
        val indicator = EmptyProgressIndicator()
        val graph = ProgressManager.getInstance().runProcess(Computable { DependencyAnalyzer(project, indicator).analyze(focus, 1, Direction.BOTH) }, indicator)
        var pending: List<ImpactComment> = emptyList()
        ApplicationManager.getApplication().invokeAndWait { if (!project.isDisposed) pending = ImpactComments.collect(project) }
        val comments = ImpactComments.byClass(graph.nodes, pending)
        val kinds = graph.nodes.mapNotNull { node -> UncommittedChange.of(project, node)?.let { node.qualifiedName to it.kind } }.toMap()
        val dossier = ClassDossier.collect(project, paths)
        val content = ImpactAnalysis.instructions(graph, comments, kinds, diff, pending, null, dossier)
        val instructions = runner.writeInstructions(ImpactAnalysis.fileName(focus), content) ?: return
        if (!manual) spendBudget()
        lastRunAt = System.currentTimeMillis()
        val name = DependencyAnalyzer.simpleName(focus)
        ApplicationManager.getApplication().invokeLater({
            if (runner.running != null) return@invokeLater
            runner.run(
                AgentTask(
                    "Impacto automático em $name",
                    "Analise o impacto das alterações descritas em $instructions, seguindo as instruções do arquivo, e responda só com o JSON pedido.",
                    write = false,
                    origin = DependencyTab.TITLE,
                    automatic = true,
                    quiet = true,
                ),
            ) { result -> finished(result, graph, triage) }
        }, project.disposed)
    }

    private fun finished(result: AgentResult, graph: DependencyGraph, triage: TriageResult) {
        if (!result.ok) return
        val report = ImpactAnalysis.parse(result.answer, graph)
        if (report == null) {
            notify("A IA não devolveu o impacto", "A resposta não trouxe o JSON pedido.", NotificationType.WARNING, result)
            return
        }
        lastReport = report
        val counts = report.impacts.groupingBy { it.level }.eachCount()
        val high = counts[ImpactLevel.HIGH] ?: 0
        val parts = listOfNotNull(
            counts[ImpactLevel.HIGH]?.let { if (it == 1) "1 impacto alto" else "$it impactos altos" },
            counts[ImpactLevel.MEDIUM]?.let { if (it == 1) "1 médio" else "$it médios" },
            counts[ImpactLevel.LOW]?.let { if (it == 1) "1 baixo" else "$it baixos" },
        )
        val headline = if (parts.isEmpty()) "nenhuma classe afetada" else parts.joinToString(", ")
        DefenseStatus.getInstance(project).aiFinished("Impacto em ${DependencyAnalyzer.simpleName(report.focus)}: $headline", high)
        val why = "Analisado porque ${triage.reasons.joinToString(", ")}."
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(
                "Análise de impacto pronta: $headline",
                escape(listOf(report.summary, why).filter { it.isNotBlank() }.joinToString(" ")),
                if (high > 0) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .setIcon(IronDomeIcons.impacto)
            .addAction(NotificationAction.createSimpleExpiring("Abrir no Raio de Impacto") { DependencyTab.open(project) { it.showReport(report) } })
        result.transcript?.let { path -> notification.addAction(NotificationAction.createSimple("Ver Resposta") { AgentRunner.getInstance(project).open(path) }) }
        notification.notify(project)
    }

    private fun budgetLeft(): Int {
        val (day, used) = budgetState()
        return DefenseSettings.aiDailyBudget - if (day == LocalDate.now().toString()) used else 0
    }

    private fun spendBudget() {
        val (day, used) = budgetState()
        val today = LocalDate.now().toString()
        PropertiesComponent.getInstance().setValue(BUDGET_KEY, "$today:${if (day == today) used + 1 else 1}")
    }

    private fun budgetState(): Pair<String, Int> {
        val value = PropertiesComponent.getInstance().getValue(BUDGET_KEY).orEmpty()
        return value.substringBefore(':') to (value.substringAfter(':', "0").toIntOrNull() ?: 0)
    }

    private fun gitDiff(root: String, paths: List<String>): String = runCatching {
        val line = GeneralCommandLine(listOf("git", "diff", "HEAD", "--no-color", "--") + paths).withWorkDirectory(root).withCharset(StandardCharsets.UTF_8)
        ExecUtil.execAndGetOutput(line, GIT_TIMEOUT_MS).takeIf { it.exitCode == 0 && !it.isTimeout }?.stdout.orEmpty()
    }.getOrDefault("")

    private fun notify(title: String, content: String, type: NotificationType, result: AgentResult? = null) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, escape(content), type)
            .setIcon(IronDomeIcons.impacto)
        result?.transcript?.let { path -> notification.addAction(NotificationAction.createSimple("Ver Resposta") { AgentRunner.getInstance(project).open(path) }) }
        notification.notify(project)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val RETRY_MS = 60_000
        private const val MAX_SNAPSHOTS = 5
        private const val GIT_TIMEOUT_MS = 15_000
        private const val BUDGET_KEY = "IronDome.Defesa.AiBudgetUsed"
        private val SOURCE_EXTENSIONS = setOf("java", "kt")
        private val LOG = logger<BackgroundImpact>()

        fun getInstance(project: Project): BackgroundImpact = project.service()
    }
}

class BackgroundImpactStartup : ProjectActivity {
    override suspend fun execute(project: Project) = BackgroundImpact.getInstance(project).start()
}

class BackgroundImpactNowAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && AgentRunner.getInstance(project).let { it.available() && it.running == null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { BackgroundImpact.getInstance(it).analyzeNow() }
    }
}
