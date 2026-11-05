package com.mio.irondome.patrulha

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.Tone
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewCommentsListener
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

/**
 * No modo revisão do PR, com o checkout no head dele, o editor destaca o que o PR mudou em cada arquivo, como o
 * diff do GitHub: fundo e barra na margem nas linhas novas e alteradas, uma marca onde linhas saíram e as mesmas
 * cores na barra de rolagem. Assim dá para comentar direto no arquivo, sem abrir o diff para achar as linhas.
 * Só na EDT.
 */
@Service(Service.Level.PROJECT)
class ChangedLines(private val project: Project) {

    private class Snapshot(val number: Int, val files: PullRequestFiles)

    /** O que está desenhado nos editores agora. */
    private var shown: Snapshot? = null
    /** PRs e heads (`12@sha`) cujos arquivos já foram pedidos ao GitHub, para não pedir de novo a cada redesenho. */
    private val requested = mutableSetOf<String>()

    /** Redesenha os editores abertos se o PR em revisão, o head ou os arquivos lidos dele mudaram. */
    fun refresh() {
        val current = current()
        if (current?.number == shown?.number && current?.files === shown?.files) return
        shown = current
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { paint(it, current) }
    }

    fun editorCreated(editor: Editor) {
        refresh()
        paint(editor, shown)
    }

    /** O PR em revisão com os arquivos dele, se o checkout está no head; sem os arquivos lidos, pede ao GitHub. */
    private fun current(): Snapshot? {
        val number = project.service<ReviewCommentService>().activePullRequest.takeIf { it != 0 } ?: return null
        val patrulha = PatrulhaService.getInstance(project)
        val context = patrulha.context()?.takeIf { it.account != null } ?: return null
        val head = context.repository.currentRevision ?: return null
        val files = patrulha.cachedFiles(number, head) ?: return null.also { load(number, head) }
        return Snapshot(number, files)
    }

    private fun load(number: Int, head: String) {
        if (!requested.add("$number@$head")) return
        object : Task.Backgroundable(project, "Patrulha: lendo o que o PR #$number alterou", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    PatrulhaService.getInstance(project).checkedOutFiles(number)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Não deu para ler os arquivos do PR #$number", e)
                }
            }

            override fun onFinished() = refresh()
        }.queue()
    }

    private fun paint(editor: Editor, snapshot: Snapshot?) {
        editor.getUserData(HIGHLIGHTERS)?.forEach { it.dispose() }
        editor.putUserData(HIGHLIGHTERS, null)
        if (snapshot == null || editor.editorKind != EditorKind.MAIN_EDITOR || editor.project != project) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = project.service<ReviewCommentService>().relativePath(file) ?: return
        val changed = snapshot.files.files.firstOrNull { it.path == path && !it.removed } ?: return
        val changes =
            if (changed.patch == null && changed.added) listOf(LineChange(ChangeKind.ADDED, 1..editor.document.lineCount, 0))
            else DiffLines.changes(changed.patch)
        editor.putUserData(HIGHLIGHTERS, changes.mapNotNull { highlight(editor, snapshot.number, it) })
    }

    private fun highlight(editor: Editor, number: Int, change: LineChange): RangeHighlighter? {
        val document = editor.document
        val lastLine = document.lineCount - 1
        if (lastLine < 0) return null
        // A remoção no fim do arquivo não tem linha abaixo: a marca fica embaixo da última.
        val atEnd = change.kind == ChangeKind.DELETED && change.lines.first - 1 > lastLine
        val first = (change.lines.first - 1).coerceIn(0, lastLine)
        val last = (change.lines.last - 1).coerceIn(first, lastLine)
        val style = Style.of(change.kind)
        val attributes = TextAttributes().apply {
            backgroundColor = style.background
            errorStripeColor = style.accent
        }
        return editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(first),
            document.getLineEndOffset(last),
            HighlighterLayer.CARET_ROW - 1,
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE,
        ).apply {
            lineMarkerRenderer = ChangeMarker(style.accent, change.kind == ChangeKind.DELETED, atEnd)
            isThinErrorStripeMark = true
            errorStripeTooltip = tooltip(number, change)
        }
    }

    private fun tooltip(number: Int, change: LineChange): String {
        val lines = change.lines.last - change.lines.first + 1
        val removed = if (change.removed == 1) "1 linha" else "${change.removed} linhas"
        return when (change.kind) {
            ChangeKind.ADDED -> "${if (lines == 1) "Linha nova" else "$lines linhas novas"} no PR #$number: o comentário aqui vai na linha do diff"
            ChangeKind.MODIFIED -> "Alterado no PR #$number, no lugar de $removed: o comentário aqui vai na linha do diff"
            ChangeKind.DELETED -> "$removed ${if (change.removed == 1) "removida" else "removidas"} no PR #$number aqui"
        }
    }

    private enum class Style(val accent: Color, val background: Color?) {
        ADDED(Tone.GREEN.accent, JBColor(Color(0xE6FFEC), Color(0x22332A))),
        MODIFIED(Tone.BLUE.accent, JBColor(Color(0xE7F1FF), Color(0x21303F))),
        DELETED(Tone.RED.accent, null),
        ;

        companion object {
            fun of(kind: ChangeKind) = when (kind) {
                ChangeKind.ADDED -> ADDED
                ChangeKind.MODIFIED -> MODIFIED
                ChangeKind.DELETED -> DELETED
            }
        }
    }

    /** Barra na margem das linhas novas e alteradas; nas remoções, um triângulo entre as linhas. */
    private class ChangeMarker(private val color: Color, private val deleted: Boolean, private val atEnd: Boolean) : LineMarkerRendererEx {

        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = color
            if (!deleted) {
                g.fillRect(r.x, r.y, JBUI.scale(3), r.height)
                return
            }
            val size = JBUI.scale(4)
            val y = if (atEnd) r.y + r.height else r.y
            g.fillPolygon(intArrayOf(r.x, r.x + 2 * size, r.x), intArrayOf(y - size, y, y + size), 3)
        }

        override fun getPosition() = LineMarkerRendererEx.Position.RIGHT
    }

    companion object {
        private val HIGHLIGHTERS = Key.create<List<RangeHighlighter>>("irondome.patrulha.changedLines")
        private val LOG = logger<ChangedLines>()

        fun getInstance(project: Project): ChangedLines = project.service()
    }
}

class ChangedLinesEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project?.takeIf { !it.isDisposed } ?: return
        ChangedLines.getInstance(project).editorCreated(event.editor)
    }
}

/** Entrar e sair do modo revisão e trocar de commit mudam o que o editor destaca. */
class ChangedLinesRefresher(private val project: Project) : ReviewCommentsListener, GitRepositoryChangeListener {

    override fun commentsChanged() = ChangedLines.getInstance(project).refresh()

    override fun repositoryChanged(repository: GitRepository) {
        ApplicationManager.getApplication().invokeLater({ ChangedLines.getInstance(project).refresh() }, project.disposed)
    }
}
