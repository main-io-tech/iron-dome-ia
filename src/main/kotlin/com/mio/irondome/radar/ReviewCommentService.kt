package com.mio.irondome.radar

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.mio.irondome.comando.ReviewMarkdown
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * Acessado só pela EDT.
 *
 * Há no máximo uma revisão aberta por projeto, gravada em `iron-dome/ordem-interceptacao-<id>.md`. Ela é
 * encerrada quando todos os comentários dela ficam resolvidos; a partir daí o `.md` não é mais
 * regravado e o próximo comentário abre uma revisão nova.
 *
 * Cada comentário guarda a versão que está no `.md` ([ReviewComment.exported]), persistida com o
 * resto do estado: comentários não salvos continuam não salvos depois de reabrir a IDE, e salvar um
 * arquivo regrava o `.md` sem levar junto o que mudou nos outros.
 */
@Service(Service.Level.PROJECT)
@State(name = "AiReviewComments", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewCommentService(private val project: Project) : PersistentStateComponent<ReviewState> {

    private var state = ReviewState()

    /** Comentários não resolvidos da revisão aberta por caminho, lido fora da EDT pela árvore do projeto. */
    @Volatile
    var openCounts: Map<String, Int> = emptyMap()
        private set
    private val markers = mutableMapOf<ReviewComment, RangeMarker>()
    /** Com o formulário de edição aberto no lugar do cartão. */
    private val editing = mutableSetOf<ReviewComment>()

    override fun getState(): ReviewState = state

    override fun loadState(loaded: ReviewState) {
        markers.values.forEach { it.dispose() }
        markers.clear()
        editing.clear()
        state = loaded
        adoptLegacyComments()
        openCounts = countOpen()
    }

    /**
     * Estados gravados antes de [ReviewComment.exported] existir: o que já está no `.md` (ou fora da
     * revisão aberta) conta como salvo; o resto continua rascunho.
     */
    private fun adoptLegacyComments() {
        val legacy = state.comments.filter { it.exported == null }
        if (legacy.isEmpty()) return
        val inMarkdown = activeReviewFile()?.takeIf(Files::exists)
            ?.let { runCatching { ReviewMarkdown.parse(Files.readString(it)).map(SavedComment::id).toSet() }.getOrNull() }
            .orEmpty()
        legacy.filter { it.reviewId != state.activeReviewId || it.resolved || it.id in inMarkdown }
            .forEach { it.exported = ExportedVersion.of(it) }
    }

    fun relativePath(file: VirtualFile): String? {
        if (!file.isInLocalFileSystem) return null
        val baseDir = project.guessProjectDir() ?: return null
        return VfsUtilCore.getRelativePath(file, baseDir)
    }

    /** Caminho de um arquivo ou pacote que pode virar alvo inteiro: nem a raiz do projeto, nem as revisões. */
    fun targetPath(file: VirtualFile): String? =
        relativePath(file)?.takeIf { it.isNotEmpty() && it != REVIEW_DIR && !it.startsWith("$REVIEW_DIR/") }

    fun openCommentCount(file: VirtualFile): Int = relativePath(file)?.let { openCounts[it] } ?: 0

    fun accepts(document: Document): Boolean = pathOf(document) != null

    fun hasPendingChanges(): Boolean = pendingPaths().isNotEmpty()

    fun hasPendingChanges(file: VirtualFile): Boolean = relativePath(file) in pendingPaths()

    /** Arquivos com comentários adicionados, editados ou marcados para remoção desde o último salvamento. */
    fun pendingPaths(): Set<String> =
        state.comments.filter { isUnsaved(it) || it.removing }.map { it.filePath }.toSet()

    fun isMarkedForRemoval(comment: ReviewComment): Boolean = comment.removing

    /** Adicionado ou editado depois do último salvamento, com a mudança ainda fora do `.md`. */
    fun isUnsaved(comment: ReviewComment): Boolean {
        val exported = comment.exported ?: return true
        return exported.text != comment.text || exported.discuss != comment.discuss
    }

    fun isDraft(comment: ReviewComment): Boolean = comment.exported == null

    fun statusOf(comment: ReviewComment): CommentStatus = when {
        comment.removing -> CommentStatus.REMOVING
        isUnsaved(comment) -> CommentStatus.UNSAVED
        comment.resolved -> CommentStatus.RESOLVED
        else -> CommentStatus.PENDING
    }

    /** 0 quando não há revisão aberta. */
    val activeReviewId: Int get() = state.activeReviewId

    /** Ordenados por arquivo e linha, inclusive os não salvos e os dispensados. */
    fun activeComments(): List<ReviewComment> {
        val reviewId = state.activeReviewId
        if (reviewId == 0) return emptyList()
        return state.comments.filter { it.reviewId == reviewId }.sortedWith(ReviewComment.ORDER)
    }

    /** `.md` gravados em `iron-dome/`, do mais recente para o mais antigo. */
    fun savedReviews(): List<Pair<Int, Path>> {
        val root = reviewRoot()?.takeIf(Files::isDirectory) ?: return emptyList()
        val name = Regex("""${Regex.escape(REVIEW_PREFIX)}(\d+)\.md""")
        return Files.list(root).use { files ->
            files.toList().mapNotNull { file ->
                name.matchEntire(file.fileName.toString())?.let { it.groupValues[1].toInt() to file }
            }
        }.sortedByDescending { it.first }
    }

    fun findFile(path: String): VirtualFile? = project.guessProjectDir()?.findFileByRelativePath(path)

    /**
     * Rascunhos, que ainda não estão no `.md`, saem na hora. Os demais continuam no editor, riscados,
     * até o próximo salvamento do arquivo. Resolvidos são ignorados: só saem por [remove], via
     * "Limpar Alvos Interceptados".
     */
    fun markForRemoval(comments: Collection<ReviewComment>) {
        val removable = comments.filterNot { it.resolved || it in editing }
        val (discarded, marked) = removable.partition(::isDraft)
        if (discarded.isNotEmpty()) {
            remove(discarded)
            releaseEmptyReview()
        }
        val newlyMarked = marked.filterNot { it.removing }
        if (newlyMarked.isEmpty()) return
        newlyMarked.forEach { it.removing = true }
        refreshEditorsOf(newlyMarked)
        notifyChanged()
    }

    fun unmarkForRemoval(comments: Collection<ReviewComment>) {
        val unmarked = comments.filter { it.removing }
        if (unmarked.isEmpty()) return
        unmarked.forEach { it.removing = false }
        refreshEditorsOf(unmarked)
        notifyChanged()
    }

    fun addComment(
        file: VirtualFile,
        document: Document,
        lines: IntRange,
        text: String,
        discuss: Boolean,
    ): ReviewComment? {
        val path = relativePath(file) ?: return null
        // Abrir uma revisão nova reinicia a numeração, então precisa vir antes do id.
        val reviewId = openReview()
        val comment = ReviewComment(
            id = "C${state.nextCommentId++}",
            reviewId = reviewId,
            filePath = path,
            startLine = lines.first,
            endLine = lines.last,
            snippet = linesText(document, lines.first, lines.last),
            text = text,
            discuss = discuss,
        )
        state.comments += comment
        markers[comment] = createMarker(document, comment)
        refreshEditors(document)
        notifyChanged()
        return comment
    }

    /** Comentário sobre [target] inteiro: um arquivo ou, se for pasta, o pacote. */
    fun addTargetComment(target: VirtualFile, text: String, discuss: Boolean): ReviewComment? {
        val path = targetPath(target) ?: return null
        val reviewId = openReview()
        val comment = ReviewComment(
            id = "C${state.nextCommentId++}",
            reviewId = reviewId,
            filePath = path,
            text = text,
            discuss = discuss,
            target = if (target.isDirectory) CommentTarget.PACKAGE else CommentTarget.FILE,
        )
        state.comments += comment
        refreshEditorsOf(listOf(comment))
        notifyChanged()
        return comment
    }

    fun isEditable(comment: ReviewComment): Boolean =
        !comment.resolved && !comment.removing && comment in state.comments

    /** Esconde o cartão enquanto o formulário de edição estiver aberto. */
    fun startEditing(comment: ReviewComment): Boolean {
        if (!isEditable(comment) || !editing.add(comment)) return false
        refreshEditorsOf(listOf(comment))
        return true
    }

    fun stopEditing(comment: ReviewComment) {
        if (editing.remove(comment)) refreshEditorsOf(listOf(comment))
    }

    /** A mudança só vai para o `.md` no próximo salvamento. */
    fun update(comment: ReviewComment, text: String, discuss: Boolean) {
        editing.remove(comment)
        if (isEditable(comment) && (text != comment.text || discuss != comment.discuss)) {
            comment.text = text
            comment.discuss = discuss
            notifyChanged()
        }
        refreshEditorsOf(listOf(comment))
    }

    fun currentLines(comment: ReviewComment, document: Document): IntRange {
        val marker = markerFor(comment, document)
        return document.getLineNumber(marker.startOffset)..document.getLineNumber(marker.endOffset)
    }

    fun commentsAt(document: Document, lines: IntRange): List<ReviewComment> {
        val path = pathOf(document) ?: return emptyList()
        return visibleCommentsFor(path).filter { comment ->
            if (comment.target != CommentTarget.LINES) return@filter false
            val current = currentLines(comment, document)
            current.first <= lines.last && lines.first <= current.last
        }
    }

    fun remove(comments: Collection<ReviewComment>) {
        if (comments.isEmpty()) return
        val removed = comments.toSet()
        state.comments.removeAll(removed)
        editing.removeAll(removed)
        removed.forEach { markers.remove(it)?.dispose() }
        refreshEditorsOf(removed)
        notifyChanged()
    }

    fun resolvedComments(): List<ReviewComment> = state.comments.filter { it.resolved && !it.dismissed }

    fun toggleExpanded(comment: ReviewComment) {
        if (!comment.resolved) return
        comment.expanded = !comment.expanded
        refreshEditorsOf(listOf(comment))
    }

    /**
     * Tira resolvidos do editor sem mexer no `.md`. Os da revisão aberta ficam no estado, ocultos,
     * para continuarem no `.md` nos próximos salvamentos.
     */
    fun dismiss(comments: Collection<ReviewComment>) {
        val dismissed = comments.filter { it.resolved && !it.dismissed }
        if (dismissed.isEmpty()) return
        dismissed.forEach { it.dismissed = true }
        pruneDismissed()
        refreshEditorsOf(dismissed)
        notifyChanged()
    }

    fun render(editor: Editor) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val document = editor.document
        val path = pathOf(document) ?: return
        val inlayModel = editor.inlayModel
        inlayModel.getBlockElementsInRange(0, document.textLength, ReviewCommentRenderer::class.java)
            .forEach { it.dispose() }

        for (comment in visibleCommentsFor(path).filterNot { it in editing }) {
            if (comment.target == CommentTarget.FILE) {
                inlayModel.addBlockElement(
                    0,
                    false,
                    true,
                    0,
                    ReviewCommentRenderer(comment, isMarkedForRemoval(comment), isUnsaved(comment), 0),
                )
                continue
            }
            val marker = markerFor(comment, document)
            val lineStart = document.getLineStartOffset(document.getLineNumber(marker.startOffset))
            val lineEnd = document.getLineEndOffset(document.getLineNumber(marker.endOffset))
            inlayModel.addBlockElement(
                lineEnd,
                true,
                false,
                0,
                ReviewCommentRenderer(
                    comment,
                    isMarkedForRemoval(comment),
                    isUnsaved(comment),
                    indentOf(editor, lineStart),
                ),
            )
        }
    }

    /**
     * Salvamento explícito (Ctrl+S salva todos; [file] limita a um arquivo): regrava o `.md` se os
     * comentários mudaram ou se o código comentado se moveu ou foi alterado.
     */
    fun exportOnSave(file: VirtualFile? = null) {
        val scope = scopeOf(file) ?: return
        val comments = activeComments().filter { scope.includes(it) }
        comments.map { it.filePath }.distinct().forEach { path -> documentOf(path)?.let { syncFromMarkers(path, it) } }
        if (comments.none { it.removing || it.exported?.matches(it) != true }) return
        export(scope)
        notifyChanged()
    }

    /**
     * Salvamentos automáticos da IDE não gravam o `.md`, mas guardam a posição atual dos comentários
     * para ela não se perder se a IDE fechar antes de salvar a revisão.
     */
    fun syncPositions(file: VirtualFile) {
        val path = relativePath(file) ?: return
        documentOf(path)?.let { syncFromMarkers(path, it) }
    }

    fun exportAll(): List<Path> {
        val written = listOfNotNull(export(Scope.ALL))
        notifyChanged()
        return written
    }

    /** Grava no `.md` só os comentários de [file]; os outros arquivos ficam como estão no `.md`. */
    fun exportFile(file: VirtualFile): Path? {
        val scope = scopeOf(file) ?: return null
        return export(scope).also { notifyChanged() }
    }

    /** Arquivos cujos comentários entram num salvamento: todos, ou só um. */
    private class Scope(val path: String?) {
        fun includes(comment: ReviewComment) = path == null || comment.filePath == path

        companion object {
            val ALL = Scope(null)
        }
    }

    private fun scopeOf(file: VirtualFile?): Scope? =
        if (file == null) Scope.ALL else relativePath(file)?.let(::Scope)

    /** Chamado quando um `.md` de `iron-dome/` muda no disco (por exemplo, a IA marcou `[x]`). */
    fun importStatus(changedPaths: Collection<String>) {
        val target = activeReviewFile()?.toString()?.replace('\\', '/') ?: return
        if (changedPaths.none { it.replace('\\', '/') == target }) return
        val paths = activeComments().map { it.filePath }.distinct()
        val changed = importResolved()
        concludeIfDone()
        if (changed) {
            paths.forEach { path -> documentOf(path)?.let(::refreshEditors) }
            notifyChanged()
        }
    }

    private fun openReview(): Int {
        importResolved()
        concludeIfDone()
        if (state.activeReviewId == 0) {
            var id = state.nextReviewId
            val root = reviewRoot()
            while (root != null && Files.exists(root.resolve(reviewFileName(id)))) id++
            state.activeReviewId = id
            state.nextReviewId = id + 1
            state.activeReviewCreatedAt = LocalDateTime.now().toString()
            state.nextCommentId = 1
        }
        return state.activeReviewId
    }

    /** Revisão aberta só com rascunhos descartados: libera o id para o próximo comentário reaproveitar. */
    private fun releaseEmptyReview() {
        val reviewId = state.activeReviewId
        if (reviewId == 0 || activeComments().isNotEmpty()) return
        if (activeReviewFile()?.let(Files::exists) == true) return
        state.activeReviewId = 0
        state.nextReviewId = reviewId
    }

    private fun concludeIfDone() {
        val comments = activeComments()
        if (comments.isNotEmpty() && comments.all { it.resolved }) {
            state.activeReviewId = 0
            pruneDismissed()
        }
    }

    /** Dispensados fora da revisão aberta não voltam mais ao `.md` e podem sair do estado. */
    private fun pruneDismissed() {
        val pruned = state.comments.filter { it.dismissed && it.reviewId != state.activeReviewId }.toSet()
        if (pruned.isEmpty()) return
        state.comments.removeAll(pruned)
        pruned.forEach { markers.remove(it)?.dispose() }
    }

    /**
     * Dentro de [scope], o `.md` passa a refletir os comentários como estão agora. Fora dele, entram
     * como foram salvos da última vez: rascunhos ficam de fora e removidos continuam até o arquivo
     * deles ser salvo.
     */
    private fun export(scope: Scope): Path? {
        if (importResolved()) refreshEditorsOf(activeComments())
        val target = activeReviewFile() ?: return null
        val inScope = activeComments().filter { scope.includes(it) }
        inScope.map { it.filePath }.distinct().forEach { path -> documentOf(path)?.let { syncFromMarkers(path, it) } }
        remove(inScope.filter { it.removing })
        val saved = activeComments().filter { scope.includes(it) }
        saved.forEach { it.exported = ExportedVersion.of(it) }
        refreshEditorsOf(saved)
        importResolved()

        val comments = activeComments().mapNotNull { it.asExported() }.sortedWith(ReviewComment.ORDER)
        if (comments.isEmpty()) {
            Files.deleteIfExists(target)
            LocalFileSystem.getInstance().refreshNioFiles(listOf(target), true, false, null)
            return null
        }

        val createdAt = runCatching { LocalDateTime.parse(state.activeReviewCreatedAt) }.getOrDefault(LocalDateTime.now())
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            ReviewMarkdown.render(reviewName(state.activeReviewId), comments, createdAt, LocalDateTime.now()),
        )
        LocalFileSystem.getInstance().refreshNioFiles(listOf(target), true, false, null)
        concludeIfDone()
        return target
    }

    private fun importResolved(): Boolean {
        val target = activeReviewFile() ?: return false
        if (!Files.exists(target)) return false
        val resolvedIds = ReviewMarkdown.resolvedIds(Files.readString(target))
        val newlyResolved = activeComments().filter { !it.resolved && it.exported != null && it.id in resolvedIds }
        newlyResolved.forEach {
            it.resolved = true
            it.removing = false
        }
        return newlyResolved.isNotEmpty()
    }

    private fun syncFromMarkers(path: String, document: Document) {
        for (comment in commentsFor(path).filter { it.target == CommentTarget.LINES }) {
            val marker = markers[comment]?.takeIf { it.isValid } ?: continue
            val startLine = document.getLineNumber(marker.startOffset)
            val endLine = document.getLineNumber(marker.endOffset)
            val snippet = linesText(document, startLine, endLine)
            if (startLine == comment.startLine && endLine == comment.endLine && snippet == comment.snippet) continue
            comment.startLine = startLine
            comment.endLine = endLine
            comment.snippet = snippet
        }
    }

    private fun activeReviewFile(): Path? {
        val reviewId = state.activeReviewId
        if (reviewId == 0) return null
        return reviewRoot()?.resolve(reviewFileName(reviewId))
    }

    private fun commentsFor(path: String): List<ReviewComment> =
        state.comments.filter { it.filePath == path }.sortedBy { it.startLine }

    private fun visibleCommentsFor(path: String): List<ReviewComment> = commentsFor(path).filterNot { it.dismissed }

    private fun markerFor(comment: ReviewComment, document: Document): RangeMarker {
        val existing = markers[comment]
        if (existing != null && existing.isValid && existing.document == document) return existing
        existing?.dispose()
        return createMarker(document, comment).also { markers[comment] = it }
    }

    private fun createMarker(document: Document, comment: ReviewComment): RangeMarker {
        val lastLine = maxOf(document.lineCount - 1, 0)
        val start = comment.startLine.coerceIn(0, lastLine)
        val end = comment.endLine.coerceIn(start, lastLine)
        return document.createRangeMarker(document.getLineStartOffset(start), document.getLineEndOffset(end))
    }

    private fun refreshEditors(document: Document) {
        EditorFactory.getInstance().getEditors(document, project).forEach(::render)
    }

    private fun refreshEditorsOf(comments: Collection<ReviewComment>) {
        comments.map { it.filePath }.distinct().forEach { path -> documentOf(path)?.let(::refreshEditors) }
    }

    private fun notifyChanged() {
        openCounts = countOpen()
        project.messageBus.syncPublisher(ReviewCommentsListener.TOPIC).commentsChanged()
    }

    private fun countOpen(): Map<String, Int> =
        activeComments().filterNot { it.resolved }.groupingBy { it.filePath }.eachCount()

    private fun pathOf(document: Document): String? =
        FileDocumentManager.getInstance().getFile(document)?.let(::relativePath)

    private fun documentOf(path: String): Document? {
        val file = findFile(path) ?: return null
        return FileDocumentManager.getInstance().getCachedDocument(file)
    }

    private fun reviewRoot(): Path? = project.basePath?.let { Path.of(it, REVIEW_DIR) }

    private fun linesText(document: Document, startLine: Int, endLine: Int): String =
        document.getText(TextRange(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine)))

    private fun indentOf(editor: Editor, lineStart: Int): Int {
        val document = editor.document
        val lineEnd = document.getLineEndOffset(document.getLineNumber(lineStart))
        val text = document.charsSequence
        var offset = lineStart
        while (offset < lineEnd && text[offset].isWhitespace()) offset++
        return editor.offsetToXY(offset).x
    }

    companion object {
        const val REVIEW_DIR = "iron-dome"
        private const val REVIEW_PREFIX = "ordem-interceptacao-"

        fun reviewName(reviewId: Int): String = "$REVIEW_PREFIX$reviewId"

        fun reviewFileName(reviewId: Int): String = "${reviewName(reviewId)}.md"
    }
}
