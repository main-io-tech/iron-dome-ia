package com.mio.irondome.radar

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.refreshAction
import com.mio.irondome.comando.ReviewMarkdown
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.Icon
import javax.swing.JTree
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
        cellRenderer = Renderer()
        emptyText.text = "Céu limpo: nenhum alvo no radar"
        emptyText.appendSecondaryText("Clique no + da margem do editor para marcar um alvo", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        emptyText.appendLine("ou clique com o botão direito num arquivo ou pacote", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }
    private val knownKeys = mutableSetOf<String>()
    private var refreshQueued = false

    init {
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
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
        ),
        true,
    ).apply { targetComponent = tree }.component

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
        val activeId = service.activeReviewId
        service.savedReviews()
            .filter { (id, _) -> id != activeId }
            .forEach { (id, path) ->
                val comments = runCatching { ReviewMarkdown.parse(Files.readString(path)) }.getOrDefault(emptyList())
                if (comments.isNotEmpty()) root.add(reviewNode(id, active = false, comments.map(::savedEntry)))
            }
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

    private fun keyOf(node: Any): String? = ((node as? DefaultMutableTreeNode)?.userObject as? Item)?.key

    private fun activeReviewNode(): DefaultMutableTreeNode? {
        val comments = service.activeComments()
        if (comments.isEmpty()) return null
        return reviewNode(service.activeReviewId, active = true, comments.map(::liveEntry))
    }

    private fun reviewNode(reviewId: Int, active: Boolean, entries: List<CommentItem>): DefaultMutableTreeNode {
        val name = ReviewCommentService.reviewName(reviewId)
        val pending = entries.count { it.status != CommentStatus.RESOLVED }
        val node = DefaultMutableTreeNode(ReviewItem(name, active, pending, entries.size))
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

    private data class ReviewItem(val name: String, val active: Boolean, val pending: Int, val total: Int) : Item {
        override val key get() = name
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

    private class Renderer : ColoredTreeCellRenderer() {

        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            toolTipText = null
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is ReviewItem -> {
                    icon = IronDomeIcons.radar
                    append(item.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    when {
                        item.pending == 0 ->
                            append("  céu limpo · todos interceptados", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, CommentStatus.RESOLVED.accent))
                        item.active -> {
                            append("  em operação", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, CommentStatus.UNSAVED.accent))
                            append("  ${item.pending} de ${item.total} na mira", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        else -> append("  ${item.pending} de ${item.total} na mira", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is FileItem -> {
                    val name = item.filePath.substringAfterLast('/')
                    icon = if (item.isPackage) AllIcons.Nodes.Package else FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    append(if (item.isPackage) "$name/" else name)
                    val dir = item.filePath.substringBeforeLast('/', "")
                    if (dir.isNotEmpty()) append("  $dir", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is CommentItem -> {
                    val removing = item.status == CommentStatus.REMOVING
                    append(item.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${ReviewMarkdown.describe(item.target, item.startLine, item.endLine)}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(item.status.label, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, item.status.accent))
                    if (item.discuss && item.status != CommentStatus.RESOLVED) {
                        append("  Discutir antes", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ReviewColors.discussBadgeForeground))
                    }
                    append(
                        "  ${summaryOf(item.text)}",
                        if (removing) SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null)
                        else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    )
                    toolTipText = item.text
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
}
