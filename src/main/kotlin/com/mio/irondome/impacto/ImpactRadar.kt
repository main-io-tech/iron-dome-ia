package com.mio.irondome.impacto

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import com.mio.irondome.defesa.ChangeHistoryService
import com.mio.irondome.defesa.ClassSnapshot
import com.mio.irondome.defesa.DefenseSettings
import com.mio.irondome.defesa.DefenseStatus
import com.mio.irondome.defesa.RiskScore
import com.mio.irondome.radar.ReviewCommentService
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * O Raio de Impacto passivo: ao salvar uma classe Java ou Kotlin, o raio só dela (quem depende dela e os testes que
 * cobrem as duas pontas) é calculado em segundo plano e vira informação ambiente, na status bar e acima da classe.
 */
@Service(Service.Level.PROJECT)
class ImpactRadar(private val project: Project) : Disposable {

    private val pending = ConcurrentHashMap.newKeySet<VirtualFile>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    override fun dispose() = Unit

    fun saved(files: Collection<VirtualFile>) {
        if (!DefenseSettings.passiveRadar) return
        pending += files.filter { it.extension in SOURCE_EXTENSIONS && !ReviewCommentService.isReviewOutput(it.path) }
        if (pending.isEmpty()) return
        alarm.cancelAllRequests()
        alarm.addRequest(::process, DELAY_MS)
    }

    /** Calcula agora o raio de [file], fora da EDT. Nulo se não for uma classe do projeto. */
    fun compute(file: VirtualFile): ClassSnapshot? {
        val focus = ReadAction.nonBlocking(Callable {
            if (!file.isValid || !ProjectFileIndex.getInstance(project).isInContent(file)) return@Callable null
            PsiManager.getInstance(project).findFile(file)?.let { DependencyAnalyzer.classAt(it, null) }
        }).inSmartMode(project).executeSynchronously() ?: return null
        val indicator = EmptyProgressIndicator()
        val graph = ProgressManager.getInstance().runProcess(Computable {
            DependencyAnalyzer(project, indicator).analyze(focus, 1, Direction.AFFERENT)
        }, indicator)
        val node = graph.nodes.firstOrNull { it.qualifiedName == focus } ?: return null
        val dependents = graph.nodes.filter { it.level == 1 }
        val radiusTests = buildList {
            if (node.tests.coverage == TestCoverage.TEST_CLASS) add(node.qualifiedName)
            addAll(node.tests.testClasses)
            dependents.forEach { addAll(it.tests.testClasses) }
        }.distinct()
        val manager = ChangeListManager.getInstance(project)
        val history = ChangeHistoryService.getInstance(project).currentOrLoad().of(node.filePath)
        return ClassSnapshot(
            qualifiedName = node.qualifiedName,
            name = node.name,
            filePath = node.filePath,
            afferent = node.afferent,
            dependents = dependents.size,
            dependentsWithoutTests = dependents.count { it.tests.coverage == TestCoverage.NONE },
            coverage = node.tests.coverage,
            testClasses = node.tests.testClasses,
            radiusTests = radiusTests,
            risk = RiskScore.of(node.afferent, node.inCycle, node.tests.coverage, history),
            changed = manager.getChange(file) != null || manager.isUnversioned(file),
        )
    }

    private fun process() {
        if (project.isDisposed) return
        if (DumbService.isDumb(project)) {
            alarm.addRequest(::process, DELAY_MS)
            return
        }
        val files = pending.toList().take(MAX_PER_ROUND)
        pending.removeAll(files.toSet())
        files.forEach { file ->
            try {
                compute(file)?.let { snapshot ->
                    DefenseStatus.getInstance(project).publish(snapshot)
                    ApplicationManager.getApplication().invokeLater({
                        if (file.isValid) PsiManager.getInstance(project).findFile(file)?.let(DaemonCodeAnalyzer.getInstance(project)::restart)
                    }, project.disposed)
                }
            } catch (e: ProcessCanceledException) {
                pending += file
            } catch (e: Exception) {
                LOG.warn("O raio passivo de ${file.name} falhou", e)
            }
        }
        if (pending.isNotEmpty()) alarm.addRequest(::process, DELAY_MS)
    }

    companion object {
        private const val DELAY_MS = 1_500
        private const val MAX_PER_ROUND = 3
        private val SOURCE_EXTENSIONS = setOf("java", "kt")
        private val LOG = logger<ImpactRadar>()

        fun getInstance(project: Project): ImpactRadar = project.service()
    }
}

/** Arquivos gravados no disco, inclusive pela IA, entram no radar passivo. */
class ImpactRadarListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val files = events.filterIsInstance<VFileContentChangeEvent>().map { it.file }
        if (files.isNotEmpty() && DefenseSettings.passiveRadar) ImpactRadar.getInstance(project).saved(files)
    }
}
