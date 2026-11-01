package com.mio.irondome.radar

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.base.refreshAction
import com.mio.irondome.comando.ReviewArchive
import com.mio.irondome.comando.ReviewMarkdown
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Revisões em árvore (revisão > arquivo > comentário): a aberta, com o estado atual do editor, e as
 * gravadas em `iron-dome/`. Clique leva ao comentário no código.
 */
class CommentsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = project.service<ReviewCommentService>()
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0
        cellRenderer = Renderer()
        emptyText.text = "Céu limpo: nenhum alvo no radar"
        emptyText.appendSecondaryText("Clique no + da margem do editor para marcar um alvo", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        emptyText.appendLine("ou clique com o botão direito num arquivo ou pacote", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }
    private val header = SummaryHeader(IronDomeIcons.radar)
    private val knownKeys = mutableSetOf<String>()
    private var refreshQueued = false
    /** Do último [refresh], para a barra de ferramentas não ler o disco a cada atualização. */
    private var historyCount = 0
    private var hasArchive = false

    init {
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        })
        toolbar = createToolbar()
        installNavigation()

        val connection = project.messageBus.connect(this)
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { scheduleRefresh() })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { "/${ReviewCommentService.REVIEW_DIR}/" in it.path }) scheduleRefresh()
            }
        })
        refresh()
    }

    override fun dispose() = Unit

    private fun createToolbar() = ActionManager.getInstance().createActionToolbar(
        "IronDome.CommentsToolbar",
        DefaultActionGroup(
            refreshAction { refresh() },
            CommonActionsManager.getInstance().createExpandAllAction(DefaultTreeExpander(tree), tree),
            CommonActionsManager.getInstance().createCollapseAllAction(DefaultTreeExpander(tree), tree),
            Separator.getInstance(),
            historyAction(
                "Consolidar Histórico",
                "Junta as revisões encerradas em ${archivePath()} e apaga os .md avulsos; a revisão aberta continua",
                AllIcons.Vcs.Merge,
                enabled = { historyCount > 0 },
            ) { consolidate(confirm = true) },
            historyAction(
                "Limpar Histórico",
                "Apaga os .md das revisões encerradas e o histórico consolidado; a revisão aberta continua",
                AllIcons.Actions.GC,
                enabled = { historyCount > 0 || hasArchive },
            ) { clear() },
        ),
        true,
    ).apply { targetComponent = tree }.component

    private fun historyAction(text: String, description: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun archivePath() = "${ReviewCommentService.REVIEW_DIR}/${ReviewArchive.FILE_NAME}"

    private fun consolidate(confirm: Boolean) {
        val history = service.historyReviews()
        if (history.isEmpty()) return
        if (confirm) {
            val comments = history.sumOf { (_, path) -> commentsIn(path).size }
            val answer = Messages.showOkCancelDialog(
                project,
                "Juntar ${plural(history.size, "revisão encerrada", "revisões encerradas")} " +
                    "(${plural(comments, "comentário", "comentários")}) em ${archivePath()} e apagar os .md avulsos?\n\n" +
                    "Nada se perde: cada revisão vira uma seção do histórico consolidado, que aparece no fim desta aba. " +
                    "A revisão aberta não entra.",
                "Consolidar Histórico",
                "Consolidar",
                "Cancelar",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.OK) return
        }
        val written = service.consolidateHistory() ?: return
        refresh()
        notifyHistory(
            "${plural(history.size, "revisão consolidada", "revisões consolidadas")} em ${ReviewArchive.FILE_NAME}",
            "Os .md avulsos foram apagados.",
            written,
        )
    }

    private fun clear() {
        val history = service.historyReviews()
        val archived = service.archivedReviews()
        if (history.isEmpty() && archived.isEmpty()) return
        val comments = history.flatMap { (_, path) -> commentsIn(path) } + archived.flatMap { it.comments }
        val pending = comments.count { !it.resolved }
        val targets = listOfNotNull(
            plural(history.size, "arquivo .md de revisão encerrada", "arquivos .md de revisões encerradas").takeIf { history.isNotEmpty() },
            "o histórico consolidado (${plural(archived.size, "revisão", "revisões")})".takeIf { archived.isNotEmpty() },
        ).joinToString(" e ")
        val message = buildString {
            append("Apagar $targets? São ${plural(comments.size, "comentário", "comentários")}")
            if (pending > 0) append(", ${plural(pending, "ainda pendente", "ainda pendentes")} (sem [x])")
            append(".\n\nA revisão aberta continua. Se precisar, os arquivos podem ser recuperados pelo Local History da IDE.")
            if (history.isNotEmpty()) append("\n\nPara manter o conteúdo e só reduzir a lista, use Consolidar.")
        }
        val buttons = if (history.isNotEmpty()) arrayOf("Apagar", "Consolidar em Vez Disso", "Cancelar") else arrayOf("Apagar", "Cancelar")
        when (Messages.showDialog(project, message, "Limpar Histórico", buttons, buttons.lastIndex, Messages.getWarningIcon())) {
            0 -> {
                val removed = service.clearHistory()
                refresh()
                notifyHistory("Histórico do Radar limpo", "${plural(removed, "arquivo apagado", "arquivos apagados")} de ${ReviewCommentService.REVIEW_DIR}/.", null)
            }
            1 -> if (history.isNotEmpty()) consolidate(confirm = false)
        }
    }

    private fun commentsIn(path: Path): List<SavedComment> =
        runCatching { ReviewMarkdown.parse(Files.readString(path)) }.getOrDefault(emptyList())

    private fun notifyHistory(title: String, content: String, file: Path?) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, NotificationType.INFORMATION)
        file?.let { path ->
            notification.addAction(NotificationAction.createSimpleExpiring("Abrir ${path.fileName}") { openPath(path) })
        }
        notification.notify(project)
    }

    private fun openPath(path: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun scheduleRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        ApplicationManager.getApplication().invokeLater({
            refreshQueued = false
            refresh()
        }, project.disposed)
    }

    /** Restaura o que estava expandido; nós novos da revisão aberta já chegam expandidos. */
    private fun refresh() {
        val expanded = tree.getExpandedDescendants(TreePath(model.root))?.toList().orEmpty()
            .mapNotNull { keyOf(it.lastPathComponent) }
            .toSet()
        val root = DefaultMutableTreeNode()
        val active = activeReviewNode()?.also(root::add)
        val history = service.historyReviews()
        val archived = service.archivedReviews()
        historyCount = history.size
        hasArchive = service.archiveFile()?.let(Files::exists) == true
        updateHeader(history.size, archived.size)
        history
            .forEach { (id, path) ->
                val comments = commentsIn(path)
                if (comments.isNotEmpty()) root.add(reviewNode(id, active = false, comments.map(::savedEntry)))
            }
        if (archived.isNotEmpty()) root.add(archiveNode(archived))
        model.setRoot(root)

        val activeNodes = active?.breadthFirstEnumeration()?.toList().orEmpty().toSet()
        for (node in root.breadthFirstEnumeration().toList()) {
            val key = keyOf(node) ?: continue
            if (key in expanded || (node in activeNodes && key !in knownKeys)) {
                tree.expandPath(TreePath((node as DefaultMutableTreeNode).path))
            }
            knownKeys += key
        }
    }

    /** A revisão aberta em números: quantos alvos em cada status e quantos pedem discussão. */
    private fun updateHeader(historySize: Int, archivedSize: Int) {
        val history = listOfNotNull(
            plural(historySize, "revisão no histórico", "revisões no histórico").takeIf { historySize > 0 },
            plural(archivedSize, "consolidada", "consolidadas").takeIf { archivedSize > 0 },
        ).joinToString(" · ").ifEmpty { null }
        val comments = service.activeComments()
        if (comments.isEmpty()) {
            header.update(
                "Céu limpo",
                CommentStatus.RESOLVED.accent,
                listOfNotNull("Nenhum alvo na mira", history).joinToString(" · "),
            )
            return
        }
        val statuses = comments.map(service::statusOf)
        fun count(status: CommentStatus) = statuses.count { it == status }
        val files = comments.map { it.filePath }.distinct().size
        val open = statuses.count { it != CommentStatus.RESOLVED }
        header.update(
            title = ReviewCommentService.reviewName(service.activeReviewId),
            subtitle = listOfNotNull(
                if (open == 0) "todos interceptados" else "em operação",
                "${plural(comments.size, "alvo", "alvos")} em ${plural(files, "arquivo", "arquivos")}",
                history,
            ).joinToString(" · "),
            stats = listOf(
                Stat(count(CommentStatus.UNSAVED), if (count(CommentStatus.UNSAVED) == 1) "não disparado" else "não disparados", CommentStatus.UNSAVED.tone),
                Stat(count(CommentStatus.PENDING), "na mira", CommentStatus.PENDING.tone),
                Stat(count(CommentStatus.RESOLVED), if (count(CommentStatus.RESOLVED) == 1) "interceptado" else "interceptados", CommentStatus.RESOLVED.tone),
                Stat(count(CommentStatus.REMOVING), if (count(CommentStatus.REMOVING) == 1) "cancelado" else "cancelados", CommentStatus.REMOVING.tone),
                Stat(comments.count { it.discuss && !it.resolved }, "para discutir", ReviewColors.discussTone),
            ),
            bar = listOf(CommentStatus.RESOLVED, CommentStatus.PENDING, CommentStatus.UNSAVED, CommentStatus.REMOVING)
                .map { Segment(count(it), it.accent) },
        )
    }

    private fun keyOf(node: Any): String? = ((node as? DefaultMutableTreeNode)?.userObject as? Item)?.key

    private fun activeReviewNode(): DefaultMutableTreeNode? {
        val comments = service.activeComments()
        if (comments.isEmpty()) return null
        return reviewNode(service.activeReviewId, active = true, comments.map(::liveEntry))
    }

    /** O histórico consolidado: um nó com as revisões que ele guarda, recolhido até o usuário abrir. */
    private fun archiveNode(archived: List<ReviewArchive.Entry>): DefaultMutableTreeNode {
        val comments = archived.flatMap { it.comments }
        val node = DefaultMutableTreeNode(ArchiveItem(archived.size, comments.size, comments.count { !it.resolved }))
        for (entry in archived) {
            val id = ReviewCommentService.reviewIdOf(entry.name) ?: continue
            if (entry.comments.isNotEmpty()) node.add(reviewNode(id, active = false, entry.comments.map(::savedEntry), archived = true))
        }
        return node
    }

    private fun reviewNode(reviewId: Int, active: Boolean, entries: List<CommentItem>, archived: Boolean = false): DefaultMutableTreeNode {
        val name = ReviewCommentService.reviewName(reviewId)
        val pending = entries.count { it.status != CommentStatus.RESOLVED }
        val node = DefaultMutableTreeNode(ReviewItem(name, active, pending, entries.size, archived))
        for ((path, fileEntries) in entries.groupBy { it.filePath }.toSortedMap()) {
            val isPackage = fileEntries.any { it.target == CommentTarget.PACKAGE }
            val fileNode = DefaultMutableTreeNode(FileItem(name, path, fileEntries.size, isPackage))
            fileEntries.sortedWith(compareBy({ it.target == CommentTarget.LINES }, { it.startLine }))
                .forEach { fileNode.add(DefaultMutableTreeNode(it.copy(reviewName = name))) }
            node.add(fileNode)
        }
        return node
    }

    private fun liveEntry(comment: ReviewComment) = CommentItem(
        reviewName = "",
        id = comment.id,
        filePath = comment.filePath,
        startLine = comment.startLine,
        endLine = comment.endLine,
        text = comment.text,
        discuss = comment.discuss,
        status = service.statusOf(comment),
        target = comment.target,
        live = comment,
    )

    private fun savedEntry(comment: SavedComment) = CommentItem(
        reviewName = "",
        id = comment.id,
        filePath = comment.filePath,
        startLine = comment.startLine,
        endLine = comment.endLine,
        text = comment.text,
        discuss = comment.discuss,
        status = if (comment.resolved) CommentStatus.RESOLVED else CommentStatus.PENDING,
        target = comment.target,
        live = null,
    )

    private fun installNavigation() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                when (val item = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
                    is CommentItem -> navigate(item, focus = e.clickCount >= 2)
                    is FileItem -> if (e.clickCount >= 2) openFile(item)
                    is ArchiveItem -> if (e.clickCount >= 2) service.archiveFile()?.let(::openPath)
                }
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                when (val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is CommentItem -> navigate(item, focus = true)
                    is FileItem -> openFile(item)
                }
            }
        })
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(
                liveAction("Editar", AllIcons.Actions.Edit, service::isEditable) { TargetComments.edit(project, it) },
                liveAction("Cancelar Alvo", AllIcons.General.Remove, { !it.resolved && !it.removing }) { service.markForRemoval(listOf(it)) },
                liveAction("Desfazer Cancelamento", AllIcons.Actions.Undo, { it.removing }) { service.unmarkForRemoval(listOf(it)) },
            ),
            "IronDome.CommentsPopup",
        )
    }

    /** Só para comentários da revisão aberta, que ainda estão no estado do plugin. */
    private fun liveAction(
        text: String,
        icon: Icon,
        enabled: (ReviewComment) -> Boolean,
        perform: (ReviewComment) -> Unit,
    ) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val comment = selectedLive()
            e.presentation.isEnabledAndVisible = comment != null && enabled(comment)
        }
        override fun actionPerformed(e: AnActionEvent) {
            selectedLive()?.let(perform)
        }
    }

    private fun selectedLive(): ReviewComment? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? CommentItem)?.live

    private fun navigate(item: CommentItem, focus: Boolean) {
        if (item.target == CommentTarget.PACKAGE) {
            if (focus) TargetComments.reveal(project, item.filePath)
            return
        }
        val file = service.findFile(item.filePath) ?: return
        val line = item.live
            ?.takeIf { it.target == CommentTarget.LINES }
            ?.let { comment -> FileDocumentManager.getInstance().getDocument(file)?.let { service.currentLines(comment, it).first } }
            ?: item.startLine
        OpenFileDescriptor(project, file, line, 0).navigate(focus)
    }

    private fun openFile(item: FileItem) {
        if (item.isPackage) return TargetComments.reveal(project, item.filePath)
        val file = service.findFile(item.filePath) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private sealed interface Item {
        val key: String
    }

    private data class ReviewItem(val name: String, val active: Boolean, val pending: Int, val total: Int, val archived: Boolean = false) : Item {
        override val key get() = if (archived) "$ARCHIVE_KEY/$name" else name
    }

    private data class ArchiveItem(val reviews: Int, val comments: Int, val pending: Int) : Item {
        override val key get() = ARCHIVE_KEY
    }

    private data class FileItem(val reviewName: String, val filePath: String, val count: Int, val isPackage: Boolean) : Item {
        override val key get() = "$reviewName/$filePath"
    }

    private data class CommentItem(
        val reviewName: String,
        val id: String,
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val text: String,
        val discuss: Boolean,
        val status: CommentStatus,
        val target: CommentTarget,
        val live: ReviewComment?,
    ) : Item {
        override val key get() = "$reviewName/$id"
    }

    private class Renderer : RichTreeCellRenderer() {

        override fun describe(row: Row, value: Any?) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is ReviewItem -> {
                    val resolved = item.total - item.pending
                    row.icon(IronDomeIcons.radar)
                    row.text(item.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    when {
                        item.pending == 0 -> row.pill("Concluída", CommentStatus.RESOLVED.tone, bold = true)
                        item.active -> row.pill("Em operação", CommentStatus.UNSAVED.tone, bold = true)
                        item.archived -> row.pill("Consolidada", Tone.GRAY)
                        else -> row.pill("Histórico", Tone.GRAY)
                    }
                    row.bar(listOf(Segment(resolved, CommentStatus.RESOLVED.accent), Segment(item.pending, CommentStatus.PENDING.accent)))
                    row.text("$resolved/${item.total} interceptados", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ArchiveItem -> {
                    row.icon(AllIcons.FileTypes.Archive)
                    row.text("Histórico consolidado", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    row.pill(plural(item.reviews, "revisão", "revisões"), Tone.BLUE, bold = true)
                    row.bar(listOf(Segment(item.comments - item.pending, CommentStatus.RESOLVED.accent), Segment(item.pending, CommentStatus.PENDING.accent)))
                    row.text(plural(item.comments, "comentário", "comentários"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (item.pending > 0) row.pill(plural(item.pending, "pendente", "pendentes"), CommentStatus.PENDING.tone)
                    row.text(ReviewArchive.FILE_NAME, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    row.tooltip("${ReviewCommentService.REVIEW_DIR}/${ReviewArchive.FILE_NAME} · duplo clique abre o arquivo")
                }
                is FileItem -> {
                    val name = item.filePath.substringAfterLast('/')
                    row.icon(if (item.isPackage) AllIcons.Nodes.Package else FileTypeManager.getInstance().getFileTypeByFileName(name).icon)
                    row.text(if (item.isPackage) "$name/" else name)
                    val dir = item.filePath.substringBeforeLast('/', "")
                    if (dir.isNotEmpty()) row.text(dir, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    row.pill(plural(item.count, "alvo", "alvos"), Tone.GRAY)
                    row.tooltip(item.filePath)
                }
                is CommentItem -> {
                    val resolved = item.status == CommentStatus.RESOLVED
                    row.icon(ReviewIcons.comment)
                    row.text(item.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    row.text(ReviewMarkdown.describe(item.target, item.startLine, item.endLine), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    row.pill(item.status.label, item.status.tone, bold = true)
                    if (item.discuss && !resolved) row.pill("Discutir antes", ReviewColors.discussTone)
                    row.detail(
                        summaryOf(item.text),
                        when {
                            item.status == CommentStatus.REMOVING ->
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, ReviewColors.mutedForeground)
                            resolved -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                        },
                    )
                    row.tooltip(item.text)
                }
            }
        }

        private fun summaryOf(text: String): String {
            val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (first.length > SUMMARY_MAX) first.take(SUMMARY_MAX) + "…" else first
        }

        private companion object {
            const val SUMMARY_MAX = 100
        }
    }

    private companion object {
        const val ARCHIVE_KEY = "archive"
    }
}
