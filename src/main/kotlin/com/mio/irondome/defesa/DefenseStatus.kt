package com.mio.irondome.defesa

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.IronDomeRoot
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.impacto.ChangeRisk
import com.mio.irondome.impacto.CommentSource
import com.mio.irondome.impacto.ImpactComment
import com.mio.irondome.impacto.ImpactComments
import com.mio.irondome.impacto.TestCoverage
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewCommentsListener
import java.util.concurrent.ConcurrentHashMap

/**
 * O raio de uma classe calculado em segundo plano ao salvar. [radiusTests] são os testes que citam a classe ou as
 * que dependem dela: os testes do raio.
 */
data class ClassSnapshot(
    val qualifiedName: String,
    val name: String,
    val filePath: String,
    val afferent: Int,
    val dependents: Int,
    val dependentsWithoutTests: Int,
    val coverage: TestCoverage,
    val testClasses: List<String>,
    val radiusTests: List<String>,
    val risk: RiskScore,
    val changed: Boolean,
    val computedAt: Long = System.currentTimeMillis(),
) {
    /** "Raio: 12 classes afetadas · 2 sem teste". */
    val summary: String
        get() = listOfNotNull(
            "afeta ${plural(dependents, "classe", "classes")}",
            "$dependentsWithoutTests sem teste".takeIf { dependentsWithoutTests > 0 },
            "risco ${risk.level.label.lowercase()}",
        ).joinToString(" · ")
}

/** O que cada defesa automática percebeu: quem desenha (status bar, Escudo) escuta aqui. */
interface DefenseListener {
    fun statusChanged() {}

    /** O raio de uma classe salva ficou pronto. */
    fun radiusComputed(snapshot: ClassSnapshot) {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(DefenseListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

enum class DefenseLevel(val label: String, val tone: Tone) {
    GREEN("Defesa em ordem", Tone.GREEN),
    YELLOW("Atenção", Tone.YELLOW),
    RED("Alerta", Tone.RED),
}

/** Uma linha do resumo da defesa: o mecanismo, o que ele viu e a gravidade. */
data class DefenseLine(val mechanism: String, val text: String, val level: DefenseLevel)

/**
 * O estado da defesa do projeto, juntando o que cada mecanismo viu: alvos pendentes, alterações sem commit, o raio
 * da última classe salva, os testes, os PRs e a análise da IA em segundo plano. Também vigia os comentários que se
 * sobrepõem e oferece a síntese pela IA.
 */
@Service(Service.Level.PROJECT)
class DefenseStatus(private val project: Project) : Disposable {

    private val snapshots = ConcurrentHashMap<String, ClassSnapshot>()

    @Volatile
    var lastSnapshot: ClassSnapshot? = null
        private set
    @Volatile
    var pendingTargets = 0
        private set
    @Volatile
    var uncommitted = 0
        private set
    @Volatile
    var conflicts: List<CommentOverlap> = emptyList()
        private set

    /** Nulo enquanto nenhum teste rodou nesta sessão. */
    @Volatile
    var testsFailed: Int? = null
        private set
    @Volatile
    var testsTotal = 0
        private set
    @Volatile
    var testsScope: String? = null
        private set

    @Volatile
    var ciFailing = 0
        private set
    @Volatile
    var reviewRequests = 0
        private set
    @Volatile
    var prAlerts: List<String> = emptyList()
        private set

    @Volatile
    var aiSummary: String? = null
        private set
    @Volatile
    var aiHigh = 0
        private set

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val notifiedConflicts = mutableSetOf<String>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { scheduleRecount() })
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRecount()
        })
        connection.subscribe(IronDomeRoot.Listener.TOPIC, IronDomeRoot.Listener {
            snapshots.clear()
            lastSnapshot = null
            notifiedConflicts.clear()
            ChangeHistoryService.getInstance(project).refresh()
            scheduleRecount()
        })
        scheduleRecount()
    }

    override fun dispose() = Unit

    fun snapshotOf(path: String): ClassSnapshot? = snapshots[path]

    fun snapshots(): Collection<ClassSnapshot> = snapshots.values

    fun publish(snapshot: ClassSnapshot) {
        snapshots[snapshot.filePath] = snapshot
        lastSnapshot = snapshot
        project.messageBus.syncPublisher(DefenseListener.TOPIC).radiusComputed(snapshot)
        changed()
    }

    fun testsFinished(failed: Int, total: Int, scope: String?) {
        testsFailed = failed
        testsTotal = total
        testsScope = scope
        changed()
    }

    fun patrol(ciFailing: Int, reviewRequests: Int, alerts: List<String>) {
        this.ciFailing = ciFailing
        this.reviewRequests = reviewRequests
        prAlerts = alerts
        changed()
    }

    fun aiFinished(summary: String, high: Int) {
        aiSummary = summary
        aiHigh = high
        changed()
    }

    val level: DefenseLevel get() = lines().maxOfOrNull { it.level } ?: DefenseLevel.GREEN

    /** O resumo por mecanismo, com o motivo de cada cor. */
    fun lines(): List<DefenseLine> = buildList {
        add(
            DefenseLine(
                "Radar",
                if (pendingTargets == 0) "Nenhum alvo pendente" else plural(pendingTargets, "alvo pendente", "alvos pendentes"),
                if (pendingTargets == 0) DefenseLevel.GREEN else DefenseLevel.YELLOW,
            ),
        )
        add(DefenseLine("Sentinela", if (uncommitted == 0) "Nada sem commit" else plural(uncommitted, "arquivo sem commit", "arquivos sem commit"), DefenseLevel.GREEN))
        lastSnapshot?.let { snapshot ->
            val level = when {
                snapshot.risk.level == ChangeRisk.HIGH && snapshot.coverage == TestCoverage.NONE -> DefenseLevel.RED
                snapshot.risk.level != ChangeRisk.LOW -> DefenseLevel.YELLOW
                else -> DefenseLevel.GREEN
            }
            add(DefenseLine("Raio de Impacto", "${snapshot.name}: ${snapshot.summary} (${snapshot.risk.explain().replaceFirstChar(Char::lowercase)})", level))
        }
        testsFailed?.let { failed ->
            val scope = testsScope?.let { " ($it)" }.orEmpty()
            add(
                DefenseLine(
                    "Escudo",
                    if (failed == 0) "Os $testsTotal testes passaram$scope" else "$failed de $testsTotal testes falharam$scope",
                    if (failed == 0) DefenseLevel.GREEN else DefenseLevel.RED,
                ),
            )
        }
        if (ciFailing > 0 || reviewRequests > 0 || prAlerts.isNotEmpty()) {
            val parts = listOfNotNull(
                plural(ciFailing, "PR com CI quebrada", "PRs com CI quebrada").takeIf { ciFailing > 0 },
                plural(reviewRequests, "revisão pedida a você", "revisões pedidas a você").takeIf { reviewRequests > 0 },
                prAlerts.lastOrNull(),
            )
            add(DefenseLine("Patrulha", parts.joinToString(" · "), if (ciFailing > 0) DefenseLevel.RED else DefenseLevel.YELLOW))
        }
        aiSummary?.let { add(DefenseLine("IA", it, if (aiHigh > 0) DefenseLevel.RED else DefenseLevel.YELLOW)) }
        if (conflicts.isNotEmpty()) {
            add(DefenseLine("Fogo amigo", plural(conflicts.size, "par de comentários no mesmo trecho", "pares de comentários no mesmo trecho"), DefenseLevel.YELLOW))
        }
    }

    private fun changed() {
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(DefenseListener.TOPIC).statusChanged()
        }, project.disposed)
    }

    private fun scheduleRecount() {
        alarm.cancelAllRequests()
        alarm.addRequest({ recount() }, RECOUNT_DELAY_MS)
    }

    private fun recount() {
        if (project.isDisposed) return
        val comments = pendingComments()
        pendingTargets = comments.count { it.source != CommentSource.PATRULHA }
        uncommitted = ChangeListManager.getInstance(project).allChanges.size
        conflicts = if (DefenseSettings.conflictDetection) CommentConflicts.overlaps(comments) else emptyList()
        conflicts.filter { notifiedConflicts.add(it.key) }.takeIf { it.isNotEmpty() }?.let(::notifyConflicts)
        changed()
    }

    /** Os comentários pendentes de todos os mecanismos; sem o Git, só os do Radar. Na EDT. */
    private fun pendingComments(): List<ImpactComment> = try {
        ImpactComments.collect(project)
    } catch (_: LinkageError) {
        project.service<ReviewCommentService>().activeComments().filter { !it.resolved && !it.removing }.map {
            ImpactComment(
                it.id, CommentSource.RADAR, it.filePath, it.target,
                if (it.target == CommentTarget.LINES) it.startLine + 1 else 0,
                if (it.target == CommentTarget.LINES) it.endLine + 1 else 0,
                it.text,
            )
        }
    }

    private fun notifyConflicts(found: List<CommentOverlap>) {
        val first = found.first()
        val title = if (found.size == 1) "Comentários no mesmo trecho" else "${found.size} pares de comentários no mesmo trecho"
        val content = "${first.first.id} (${first.first.source.label}) e ${first.second.id} (${first.second.source.label}) apontam para " +
            "${first.location} de ${first.path.substringAfterLast('/')}: podem pedir coisas opostas." +
            if (found.size > 1) " E mais ${found.size - 1}." else ""
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, NotificationType.INFORMATION)
            .setIcon(IronDomeIcons.radar)
            .addAction(NotificationAction.createSimpleExpiring("Pedir Síntese à IA") { synthesize(found) })
            .notify(project)
    }

    /** A IA lê os pares e diz se divergem e o que atende aos dois; a resposta vem no aviso de concluída. */
    fun synthesize(overlaps: List<CommentOverlap> = conflicts) {
        if (overlaps.isEmpty()) return
        val runner = AgentRunner.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val instructions = runner.writeInstructions("sintese-comentarios.md", CommentConflicts.synthesisInstructions(overlaps)) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                runner.run(
                    AgentTask(
                        "Síntese de comentários",
                        "Leia as instruções em $instructions e responda em português, sem alterar nenhum arquivo.",
                        write = false,
                    ),
                )
            }, project.disposed)
        }
    }

    companion object {
        private const val RECOUNT_DELAY_MS = 800

        fun getInstance(project: Project): DefenseStatus = project.service()
    }
}
