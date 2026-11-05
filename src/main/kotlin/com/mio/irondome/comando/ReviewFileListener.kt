package com.mio.irondome.comando

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.mio.irondome.radar.ReviewCommentService

/** Detecta quando a IA marca `[x]` num `.md` de revisão e atualiza o editor. */
class ReviewFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val paths = events
            .filterIsInstance<VFileContentChangeEvent>()
            .map { it.path }
            .filter { it.endsWith(".md") }
        if (paths.isEmpty()) return

        ApplicationManager.getApplication().invokeLater(
            { project.service<ReviewCommentService>().importStatus(paths) },
            project.disposed,
        )
    }
}
