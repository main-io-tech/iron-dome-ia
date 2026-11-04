package com.mio.irondome.patrulha

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import com.mio.irondome.radar.ReviewCommentService
import java.io.IOException

/**
 * Diff dos arquivos de um PR numa aba só, como na aba "Files changed" do GitHub: a base é o ancestral comum
 * com a branch de destino. As setas do diff passam pelos demais arquivos; abrir outro PR fecha o anterior.
 * Com o PR no checkout, o lado do PR é o arquivo local, com navegação e edição.
 */
@Service(Service.Level.PROJECT)
class PullRequestDiff(private val project: Project) {

    private var openDiff: VirtualFile? = null

    fun show(context: RepoContext, pr: PullRequest, files: PullRequestFiles, index: Int) {
        if (files.files.isEmpty()) return
        val local = context.repository.currentRevision == pr.headSha
        val producers = files.files.map { Producer(project, context, pr, files.mergeBase, it, local) }
        val file = ChainDiffVirtualFile(
            SimpleDiffRequestChain.fromProducers(producers, index.coerceIn(0, producers.size - 1)),
            "PR #${pr.number}: ${pr.title}",
        )
        openDiff?.let { FileEditorManager.getInstance(project).closeFile(it) }
        openDiff = file
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }

    /** Roda em segundo plano, chamado pelo diff quando o arquivo aparece: lê as duas versões do GitHub. */
    private class Producer(
        private val project: Project,
        private val context: RepoContext,
        private val pr: PullRequest,
        private val mergeBase: String,
        private val file: ChangedFile,
        private val local: Boolean,
    ) : DiffRequestProducer {

        override fun getName(): String = file.path

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            val factory = DiffContentFactory.getInstance()
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
            if (fileType.isBinary) {
                val note = factory.create("Arquivo binário (${file.statusLabel.lowercase()}): veja no GitHub.")
                return SimpleDiffRequest(file.path, note, note, "Base", "PR #${pr.number}")
            }
            return try {
                val client = PatrulhaService.getInstance(project).client(this.context)
                val before: DiffContent = if (file.added || mergeBase.isEmpty()) {
                    factory.createEmpty()
                } else {
                    client.fileContent(this.context.repo, file.previousPath ?: file.path, mergeBase)
                        ?.let { factory.create(project, it, fileType) } ?: factory.createEmpty()
                }
                val localFile = if (local && !file.removed) project.service<ReviewCommentService>().findFile(file.path) else null
                val after: DiffContent = when {
                    file.removed -> factory.createEmpty()
                    localFile != null -> factory.create(project, localFile)
                    else -> client.fileContent(this.context.repo, file.path, pr.headSha)?.let { factory.create(project, it, fileType) }
                        ?: factory.createEmpty()
                }
                val title = file.previousPath?.let { "$it → ${file.path}" } ?: file.path
                SimpleDiffRequest(
                    title,
                    before,
                    after,
                    "Base · ${file.previousPath ?: file.path} em ${mergeBase.take(7).ifEmpty { pr.baseRef }}",
                    if (localFile != null) "PR #${pr.number} · local, no checkout" else "PR #${pr.number} · ${pr.headSha.take(7)}",
                )
            } catch (e: GitHubException) {
                throw DiffRequestProducerException(e.message ?: "GitHub", e)
            } catch (e: PatrulhaException) {
                throw DiffRequestProducerException(e.message ?: "Patrulha", e)
            } catch (e: IOException) {
                throw DiffRequestProducerException("Sem conexão com o GitHub: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }
}
