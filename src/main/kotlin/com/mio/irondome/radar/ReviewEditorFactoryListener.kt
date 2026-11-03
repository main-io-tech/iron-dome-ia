package com.mio.irondome.radar

import com.intellij.openapi.components.service
import com.mio.irondome.base.IdeSupport
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

class ReviewEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed || IdeSupport.isLimited(RADAR)) return
        project.service<ReviewCommentService>().render(event.editor)
    }
}
