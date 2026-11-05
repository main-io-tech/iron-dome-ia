package com.mio.irondome.impacto

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.vcsUtil.VcsUtil
import com.mio.irondome.sentinela.ChangeDiff

enum class FileChange(val label: String) {
    MODIFIED("Modificada"),
    MOVED("Movida ou renomeada"),
    ADDED("Nova"),
    UNVERSIONED("Nova, fora do git");

    override fun toString() = label
}

/** Alteração ainda não commitada no arquivo da classe, pela mesma regra da aba Alterações. */
class UncommittedChange(val kind: FileChange, val change: Change, val node: ClassNode) {

    fun diffItem() = ChangeDiff.Item(change, VcsUtil.getFilePath(node.file!!), node.filePath)

    companion object {
        fun of(project: Project, node: ClassNode): UncommittedChange? {
            val file = node.file ?: return null
            val manager = ChangeListManager.getInstance(project)
            manager.getChange(file)?.let { change ->
                val kind = when (change.type) {
                    Change.Type.NEW -> FileChange.ADDED
                    Change.Type.MOVED -> FileChange.MOVED
                    else -> FileChange.MODIFIED
                }
                return UncommittedChange(kind, change, node)
            }
            if (!manager.isUnversioned(file)) return null
            return UncommittedChange(FileChange.UNVERSIONED, ChangeDiff.unversioned(VcsUtil.getFilePath(file)), node)
        }
    }
}
