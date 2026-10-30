package com.mio.review.ui.toolwindow

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile

/**
 * Diff de alterações não commitadas contra o último commit, numa aba só: as setas do diff passam
 * pelos demais arquivos e abrir outro diff fecha o anterior.
 */
class ChangeDiff(private val project: Project) {

    class Item(val change: Change, val path: FilePath, val title: String)

    private var openDiff: VirtualFile? = null

    fun show(items: List<Item>, index: Int) {
        val file = ChainDiffVirtualFile(
            SimpleDiffRequestChain.fromProducers(items.map { Producer(project, it) }, index),
            "Alterações não commitadas",
        )
        openDiff?.let { FileEditorManager.getInstance(project).closeFile(it) }
        openDiff = file
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, false)
    }

    companion object {
        /** Arquivos fora do git viram uma alteração sem versão anterior. */
        fun unversioned(path: FilePath) = Change(null, CurrentContentRevision.create(path))
    }

    /** Roda em segundo plano, chamado pelo diff: ler a versão do commit pode acionar o git. */
    private class Producer(private val project: Project, private val item: Item) : DiffRequestProducer {

        override fun getName(): String = item.title

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            val factory = DiffContentFactory.getInstance()
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(item.path.name)
            val before = try {
                item.change.beforeRevision?.content?.let { factory.create(project, it, fileType) }
            } catch (e: VcsException) {
                throw DiffRequestProducerException(e)
            } ?: factory.createEmpty()
            val after = item.path.virtualFile?.takeIf { item.change.afterRevision != null }
                ?.let { factory.create(project, it) }
                ?: factory.createEmpty()
            return SimpleDiffRequest(item.title, before, after, "Último commit", "Local")
        }
    }
}
