package com.mio.review.listeners

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.mio.review.ReviewCommentService

class ReviewEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed) return
        project.service<ReviewCommentService>().render(event.editor)
    }
}
