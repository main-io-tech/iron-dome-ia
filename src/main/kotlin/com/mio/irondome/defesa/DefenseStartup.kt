package com.mio.irondome.defesa

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Ao abrir o projeto: o histórico do git já começa a ser lido e os comentários no mesmo trecho passam a ser vigiados. */
class DefenseStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ChangeHistoryService.getInstance(project).refresh()
        CommentConflictWatcher.getInstance(project)
    }
}
