package com.mio.review.radar

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Na árvore do projeto, quantos comentários pendentes cada arquivo ou pacote tem na revisão aberta. */
class TargetDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project?.takeIf { !it.isDisposed } ?: return
        val file = node.virtualFile ?: return
        val count = project.service<ReviewCommentService>().openCommentCount(file)
        if (count == 0) return
        val label = if (count == 1) "1 alvo" else "$count alvos"
        data.locationString = listOfNotNull(data.locationString?.takeIf { it.isNotBlank() }, label).joinToString(" · ")
    }
}

/** Redesenha a árvore do projeto só quando as contagens de [TargetDecorator] mudam. */
class TargetDecoratorRefresher(private val project: Project) : ReviewCommentsListener {

    private var shown: Map<String, Int> = emptyMap()

    override fun commentsChanged() {
        val counts = project.service<ReviewCommentService>().openCounts
        if (counts == shown) return
        shown = counts
        ProjectView.getInstance(project).refresh()
    }
}
