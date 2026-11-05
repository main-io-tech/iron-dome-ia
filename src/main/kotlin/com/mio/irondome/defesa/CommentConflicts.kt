package com.mio.irondome.defesa

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.IronDomeRoot
import com.mio.irondome.impacto.CommentSource
import com.mio.irondome.impacto.ImpactComment
import com.mio.irondome.impacto.ImpactComments
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewCommentsListener

/** Dois comentários pendentes sobre o mesmo trecho: podem pedir coisas opostas. */
data class CommentOverlap(val first: ImpactComment, val second: ImpactComment) {
    val path: String get() = first.path

    /** As linhas em comum, 1-based. */
    val lines: IntRange
        get() = maxOf(first.startLine, second.startLine)..minOf(first.endLine, second.endLine)

    val location: String
        get() = if (lines.first == lines.last) "linha ${lines.first}" else "linhas ${lines.first}–${lines.last}"

    /** De mecanismos diferentes (Radar × Patrulha, humano × IA): mais chance de divergirem. */
    val crossSource: Boolean get() = first.source != second.source

    val key: String get() = listOf(first.id, second.id).sorted().joinToString("+")
}

/**
 * Quando dois comentários apontam para o mesmo trecho e podem dizer coisas opostas, o plugin detecta a
 * sobreposição por intervalo e pede à IA uma síntese. A detecção é só interseção de intervalos.
 */
object CommentConflicts {

    /** Pares de comentários de linhas com interseção no mesmo arquivo, sem repetir pares. */
    fun overlaps(comments: List<ImpactComment>): List<CommentOverlap> {
        val byPath = comments.filter { it.target == CommentTarget.LINES && it.startLine > 0 }.groupBy { it.path }
        return byPath.values.flatMap { same ->
            val sorted = same.sortedWith(compareBy({ it.startLine }, { it.endLine }, { it.id }))
            val pairs = mutableListOf<CommentOverlap>()
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    if (sorted[j].startLine > sorted[i].endLine) break
                    if (sorted[i].id != sorted[j].id) pairs += CommentOverlap(sorted[i], sorted[j])
                }
            }
            pairs
        }
    }

    /** O pedido de síntese à IA: ler os dois, dizer se divergem e propor o que atende aos dois. */
    fun synthesisInstructions(overlaps: List<CommentOverlap>): String = buildString {
        appendLine("# Comentários que se sobrepõem")
        appendLine()
        appendLine("Dois ou mais comentários de revisão apontam para o mesmo trecho de código. Para cada par, leia o trecho no")
        appendLine("projeto e os dois comentários, e responda, em português do Brasil:")
        appendLine()
        appendLine("1. **Divergem?** Se os dois pedem a mesma coisa com palavras diferentes, diga que convergem e resuma o pedido.")
        appendLine("2. **O que cada um quer de verdade:** a intenção por trás de cada pedido (legibilidade, desempenho, segurança…).")
        appendLine("3. **Síntese:** uma alteração que atenda às duas intenções, ou, se for impossível, qual prevalece e por quê.")
        appendLine()
        appendLine("**Não altere nenhum arquivo.** Seja direto: até 8 frases por par.")
        overlaps.forEachIndexed { index, overlap ->
            appendLine()
            appendLine("## Par ${index + 1} · `${overlap.path}`, ${overlap.location}")
            appendLine()
            appendLine("### ${overlap.first.id} · ${overlap.first.source.label} · ${overlap.first.location}")
            appendLine()
            appendLine(overlap.first.text.trim())
            appendLine()
            appendLine("### ${overlap.second.id} · ${overlap.second.source.label} · ${overlap.second.location}")
            appendLine()
            appendLine(overlap.second.text.trim())
        }
    }
}

/**
 * Vigia os comentários pendentes do Radar e da Patrulha: quando dois passam a apontar para o mesmo trecho, avisa
 * uma vez por par e oferece a síntese pela IA.
 */
@Service(Service.Level.PROJECT)
class CommentConflictWatcher(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val notified = mutableSetOf<String>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { schedule() })
        connection.subscribe(IronDomeRoot.Listener.TOPIC, IronDomeRoot.Listener {
            notified.clear()
            schedule()
        })
        schedule()
    }

    override fun dispose() = Unit

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest(::check, DELAY_MS)
    }

    private fun check() {
        if (project.isDisposed || !DefenseSettings.conflictDetection) return
        val fresh = CommentConflicts.overlaps(pendingComments()).filter { notified.add(it.key) }
        if (fresh.isNotEmpty()) notify(fresh)
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

    private fun notify(found: List<CommentOverlap>) {
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
    private fun synthesize(overlaps: List<CommentOverlap>) {
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
        private const val DELAY_MS = 800

        fun getInstance(project: Project): CommentConflictWatcher = project.service()
    }
}
