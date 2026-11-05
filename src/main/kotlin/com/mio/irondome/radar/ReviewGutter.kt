package com.mio.irondome.radar

import com.intellij.openapi.actionSystem.AnAction
import com.mio.irondome.base.IdeSupport
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
import javax.swing.Icon

/** Botões "+" (comentar) e "-" (remover comentário) que aparecem na margem da linha sob o mouse. */
object ReviewGutter {

    private val hoverHighlighters = Key.create<List<RangeHighlighter>>("irondome.gutterHover")
    private val commentable = Key.create<Boolean>("irondome.commentable")

    /** O que depende só do editor fica guardado; o arquivo é conferido a cada vez, porque o modo revisão de PR muda o que aceita. */
    fun isCommentable(editor: Editor): Boolean {
        val mainEditor = editor.getUserData(commentable) ?: run {
            val project = editor.project
            (!IdeSupport.isLimited(RADAR) && editor.editorKind == EditorKind.MAIN_EDITOR && project != null && !project.isDisposed)
                .also { editor.putUserData(commentable, it) }
        }
        return mainEditor && service(editor)?.accepts(editor.document) == true
    }

    fun showAt(editor: Editor, line: Int) {
        val current = editor.getUserData(hoverHighlighters).orEmpty()
        if (current.isNotEmpty() && current.all { it.isValid } &&
            editor.document.getLineNumber(current.first().startOffset) == line
        ) return
        hide(editor)

        val markupModel = editor.markupModel
        val highlighters = mutableListOf<RangeHighlighter>()
        highlighters += markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null).apply {
            gutterIconRenderer = AddCommentIconRenderer(editor, line)
        }
        val removable = removableAt(editor, line)
        if (removable.isNotEmpty()) {
            val allMarked = removable.all { service(editor)?.isMarkedForRemoval(it) == true }
            highlighters += markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null).apply {
                gutterIconRenderer =
                    if (allMarked) UndoRemoveIconRenderer(editor, line) else RemoveCommentIconRenderer(editor, line)
            }
        }
        editor.putUserData(hoverHighlighters, highlighters)
    }

    fun hide(editor: Editor) {
        editor.getUserData(hoverHighlighters)?.forEach { it.dispose() }
        editor.putUserData(hoverHighlighters, null)
    }

    /** A seleção inteira, se a linha clicada estiver nela; senão, só a linha. */
    fun linesFor(editor: Editor, line: Int): IntRange {
        val selected = editor.selectedLines()
        return if (editor.selectionModel.hasSelection() && line in selected) selected else line..line
    }

    fun commentsAt(editor: Editor, line: Int): List<ReviewComment> =
        service(editor)?.commentsAt(editor.document, line..line).orEmpty()

    /** Comentários resolvidos não podem ser removidos pela margem. */
    fun removableAt(editor: Editor, line: Int): List<ReviewComment> =
        commentsAt(editor, line).filterNot { it.resolved }

    fun markForRemovalAt(editor: Editor, line: Int) {
        service(editor)?.markForRemoval(removableAt(editor, line))
        refreshAt(editor, line)
    }

    fun unmarkForRemovalAt(editor: Editor, line: Int) {
        service(editor)?.unmarkForRemoval(removableAt(editor, line))
        refreshAt(editor, line)
    }

    private fun refreshAt(editor: Editor, line: Int) {
        hide(editor)
        showAt(editor, line)
    }

    private fun service(editor: Editor): ReviewCommentService? =
        editor.project?.takeIf { !it.isDisposed }?.service<ReviewCommentService>()
}

private class AddCommentIconRenderer(
    private val editor: Editor,
    private val line: Int,
) : GutterIconRenderer(), DumbAware {

    /** Lido ao criar o botão, que é recriado a cada linha sob o mouse. */
    private val pullRequest = editor.project?.takeIf { !it.isDisposed }?.service<ReviewCommentService>()?.activePullRequest ?: 0

    override fun getIcon(): Icon = if (pullRequest != 0) ReviewIcons.addPullRequestComment else ReviewIcons.addComment

    override fun getTooltipText(): String = if (pullRequest != 0) "Comentar no PR #$pullRequest" else "Marcar alvo para a IA"

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction =
        DumbAwareAction.create { CommentForm.open(editor, ReviewGutter.linesFor(editor, line)) }

    override fun equals(other: Any?): Boolean =
        other is AddCommentIconRenderer && other.editor === editor && other.line == line

    override fun hashCode(): Int = 31 * System.identityHashCode(editor) + line
}

private class RemoveCommentIconRenderer(
    private val editor: Editor,
    private val line: Int,
) : GutterIconRenderer(), DumbAware {

    override fun getIcon(): Icon = ReviewIcons.removeComment

    override fun getTooltipText(): String {
        val removable = ReviewGutter.removableAt(editor, line)
        val ids = removable.joinToString(", ") { it.id }
        val service = editor.project?.service<ReviewCommentService>()
        return when {
            removable.isNotEmpty() && removable.all { it.pullRequest != 0 } -> "Remover o comentário $ids do PR"
            service != null && removable.all(service::isDraft) -> "Cancelar alvo $ids"
            else -> "Cancelar alvo $ids no próximo disparo"
        }
    }

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = DumbAwareAction.create { ReviewGutter.markForRemovalAt(editor, line) }

    override fun equals(other: Any?): Boolean =
        other is RemoveCommentIconRenderer && other.editor === editor && other.line == line

    override fun hashCode(): Int = 37 * System.identityHashCode(editor) + line
}

private class UndoRemoveIconRenderer(
    private val editor: Editor,
    private val line: Int,
) : GutterIconRenderer(), DumbAware {

    override fun getIcon(): Icon = ReviewIcons.undoRemove

    override fun getTooltipText(): String {
        val ids = ReviewGutter.removableAt(editor, line).joinToString(", ") { it.id }
        return "Desfazer cancelamento do alvo $ids"
    }

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = DumbAwareAction.create { ReviewGutter.unmarkForRemovalAt(editor, line) }

    override fun equals(other: Any?): Boolean =
        other is UndoRemoveIconRenderer && other.editor === editor && other.line == line

    override fun hashCode(): Int = 41 * System.identityHashCode(editor) + line
}
