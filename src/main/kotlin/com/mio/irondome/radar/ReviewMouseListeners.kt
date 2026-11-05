package com.mio.irondome.radar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.mio.irondome.interceptor.FireWithAiAction
import java.awt.Cursor
import javax.swing.SwingUtilities

class ReviewMouseMotionListener : EditorMouseMotionListener {

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        if (!ReviewGutter.isCommentable(editor)) return
        val clickable = hitOf(e) != ReviewCommentRenderer.Hit.NONE
        setHandCursor(editor, clickable)
        val line = e.logicalPosition.line
        if (e.inlay != null || line >= editor.document.lineCount) {
            ReviewGutter.hide(editor)
        } else {
            ReviewGutter.showAt(editor, line)
        }
    }
}

/**
 * Duplo clique no número da linha abre o formulário de comentário. Num resolvido, clique no
 * cabeçalho recolhe ou expande, e clique no OK dispensa. Nos demais, Editar abre o formulário e, nos alvos do
 * Radar, Disparar manda só aquele alvo para a IA.
 */
class ReviewMouseListener : EditorMouseListener {

    override fun mouseClicked(e: EditorMouseEvent) {
        val mouse = e.mouseEvent
        if (!SwingUtilities.isLeftMouseButton(mouse)) return
        val editor = e.editor
        if (!ReviewGutter.isCommentable(editor)) return

        val renderer = e.inlay?.renderer as? ReviewCommentRenderer
        if (renderer != null) {
            val project = editor.project?.takeIf { !it.isDisposed } ?: return
            val service = project.service<ReviewCommentService>()
            val card = renderer.card
            if (card != null) {
                when (hitOf(e)) {
                    ReviewCommentRenderer.Hit.TOGGLE -> card.onToggle()
                    ReviewCommentRenderer.Hit.HIDE -> card.onHide()
                    ReviewCommentRenderer.Hit.EDIT -> card.onEdit?.invoke()
                    ReviewCommentRenderer.Hit.FIRE, ReviewCommentRenderer.Hit.NONE -> return
                }
                setHandCursor(editor, false)
                e.consume()
                return
            }
            when (hitOf(e)) {
                ReviewCommentRenderer.Hit.TOGGLE -> service.toggleExpanded(renderer.comment)
                ReviewCommentRenderer.Hit.HIDE -> service.hide(listOf(renderer.comment))
                ReviewCommentRenderer.Hit.EDIT -> TargetComments.edit(project, renderer.comment, editor)
                ReviewCommentRenderer.Hit.FIRE -> FireWithAiAction.fireTargets(project, listOf(renderer.comment))
                ReviewCommentRenderer.Hit.NONE -> return
            }
            setHandCursor(editor, false)
            e.consume()
            return
        }

        if (mouse.clickCount != 2) return
        if (e.area != EditorMouseEventArea.LINE_NUMBERS_AREA && e.area != EditorMouseEventArea.LINE_MARKERS_AREA) return
        val line = e.logicalPosition.line
        if (line >= editor.document.lineCount) return
        e.consume()
        CommentForm.open(editor, ReviewGutter.linesFor(editor, line))
    }

    override fun mouseExited(e: EditorMouseEvent) {
        ReviewGutter.hide(e.editor)
        setHandCursor(e.editor, false)
    }
}

private fun hitOf(e: EditorMouseEvent): ReviewCommentRenderer.Hit {
    val inlay = e.inlay ?: return ReviewCommentRenderer.Hit.NONE
    val renderer = inlay.renderer as? ReviewCommentRenderer ?: return ReviewCommentRenderer.Hit.NONE
    return renderer.hitTest(inlay, e.mouseEvent.point)
}

private fun setHandCursor(editor: Editor, hand: Boolean) {
    (editor as? EditorEx)?.setCustomCursor(
        ReviewCommentRenderer::class.java,
        if (hand) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null,
    )
}
