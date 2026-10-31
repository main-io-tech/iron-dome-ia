package com.mio.review.sentinela

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.mio.review.base.refreshAction
import com.mio.review.radar.CommentStatus
import com.mio.review.radar.ReviewCommentService
import com.mio.review.radar.ReviewCommentsListener
import com.mio.review.radar.ReviewIcons
import com.mio.review.radar.TargetComments
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Arquivos com alterações ainda não commitadas, como os que a IA mudou ao aplicar a revisão,
 * agrupados por pacote. O clique só seleciona e o duplo clique abre o arquivo; os botões da barra e o
 * menu de contexto abrem o diff ou o arquivo.
 */
class ChangesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Entry(
        val change: Change,
        val path: FilePath,
        val relativePath: String,
        val comments: Int,
        val unversioned: Boolean,
    )

    /** [dir] é nula quando a pasta não existe mais, como a de arquivos apagados. */
    private class Group(val location: Location, val dir: VirtualFile?, val files: Int, val comments: Int)

    private val service = project.service<ReviewCommentService>()
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        emptyText.text = PERIMETER_CLEAR
    }
    private val diff = project.service<ChangeDiff>()
    private val knownGroups = mutableSetOf<String>()
    private var refreshQueued = false

    private val showDiffAction = selectionAction(
        "Inspecionar Diff",
        "Compara o arquivo selecionado, ou o primeiro do pacote, com a versão do último commit",
        AllIcons.Actions.Diff,
        enabled = { selectedNode() != null },
    ) { showDiff() }
    private val openFileAction = selectionAction(
        "Abrir Arquivo",
        "Abre o arquivo selecionado no editor",
        AllIcons.Actions.EditSource,
        enabled = { selectedEntry() != null },
    ) { openFile(selectedEntry()) }
    private val markTargetAction = selectionAction(
        "Marcar como Alvo",
        "Comenta o arquivo ou o pacote selecionado inteiro para a IA",
        ReviewIcons.addComment,
        enabled = { selectedTarget() != null },
    ) { selectedTarget()?.let { TargetComments.mark(project, it) } }

    init {
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        val expander = DefaultTreeExpander(tree)
        toolbar = ActionManager.getInstance().createActionToolbar(
            "MioReview.ChangesToolbar",
            DefaultActionGroup(
                showDiffAction,
                openFileAction,
                Separator.getInstance(),
                CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
                CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
                Separator.getInstance(),
                refreshAction { VcsDirtyScopeManager.getInstance(project).markEverythingDirty() },
            ),
            true,
        ).apply { targetComponent = tree }.component
        installNavigation()

        val connection = project.messageBus.connect(this)
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRefresh()
        })
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { scheduleRefresh() })
        refresh()
    }

    override fun dispose() = Unit

    private fun scheduleRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        ApplicationManager.getApplication().invokeLater({
            refreshQueued = false
            refresh()
        }, project.disposed)
    }

    /** Mantém os pacotes recolhidos pelo usuário; pacotes novos chegam expandidos. */
    private fun refresh() {
        if (!ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
            model.setRoot(DefaultMutableTreeNode())
            tree.emptyText.text = "Projeto sem controle de versão: a sentinela não tem o que vigiar"
            return
        }
        tree.emptyText.text = PERIMETER_CLEAR
        val changeListManager = ChangeListManager.getInstance(project)
        val commentsByPath = service.activeComments().groupingBy { it.filePath }.eachCount()
        fun entry(change: Change, path: FilePath, unversioned: Boolean): Entry {
            val relative = relativePath(path.path)
            return Entry(change, path, relative, commentsByPath[relative] ?: 0, unversioned)
        }

        val versioned = changeListManager.allChanges.mapNotNull { change ->
            (change.afterRevision ?: change.beforeRevision)?.file?.let { entry(change, it, unversioned = false) }
        }
        val unversioned = changeListManager.unversionedFilesPaths.map {
            entry(ChangeDiff.unversioned(it), it, unversioned = true)
        }

        val expanded = tree.getExpandedDescendants(TreePath(model.root))?.toList().orEmpty()
            .mapNotNull { (nodeOf(it.lastPathComponent)?.userObject as? Group)?.location?.key }
            .toSet()
        val selectedPath = selectedEntry()?.path
        val selectedGroup = (selectedNode()?.userObject as? Group)?.location?.key

        val root = DefaultMutableTreeNode()
        (versioned + unversioned)
            .groupBy { locationOf(it.path) }
            .toSortedMap(compareBy<Location>({ it.root == null }, { it.root }, { it.name }))
            .forEach { (location, entries) ->
                val dir = entries.firstNotNullOfOrNull { it.path.parentPath?.virtualFile }
                val packageComments = dir?.let { commentsByPath[relativePath(it.path)] } ?: 0
                val node = DefaultMutableTreeNode(Group(location, dir, entries.size, entries.sumOf { it.comments } + packageComments))
                entries.sortedBy { it.path.name.lowercase() }.forEach { node.add(DefaultMutableTreeNode(it)) }
                root.add(node)
            }
        model.setRoot(root)

        for (node in root.children().toList().mapNotNull(::nodeOf)) {
            val key = (node.userObject as Group).location.key
            if (key in expanded || key !in knownGroups) tree.expandPath(TreePath(node.path))
            knownGroups += key
        }
        root.preorderEnumeration().toList().mapNotNull(::nodeOf).firstOrNull { node ->
            when (val item = node.userObject) {
                is Entry -> item.path == selectedPath && tree.isExpanded(TreePath((node.parent as DefaultMutableTreeNode).path))
                is Group -> item.location.key == selectedGroup
                else -> false
            }
        }?.let { tree.selectionPath = TreePath(it.path) }
    }

    private fun locationOf(path: FilePath): Location = path.parentPath.let { locationOf(project, it?.path, it?.virtualFile) }

    private fun relativePath(path: String) = relativePath(project, path)

    private fun nodeOf(value: Any?) = value as? DefaultMutableTreeNode

    private fun selectedNode() = nodeOf(tree.lastSelectedPathComponent)

    private fun selectedEntry() = selectedNode()?.userObject as? Entry

    private fun selectedTarget(): VirtualFile? = when (val item = selectedNode()?.userObject) {
        is Entry -> item.path.virtualFile
        is Group -> item.dir
        else -> null
    }?.takeIf { service.targetPath(it) != null }

    private fun selectionAction(text: String, description: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun installNavigation() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                openFile(nodeOf(tree.getPathForLocation(e.x, e.y)?.lastPathComponent)?.userObject as? Entry)
            }
        })
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(showDiffAction, openFileAction, Separator.getInstance(), markTargetAction),
            "MioReview.ChangesPopup",
        )
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val node = selectedNode() ?: return
                if (node.userObject is Entry) {
                    showDiff()
                } else {
                    val path = TreePath(node.path)
                    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                }
            }
        })
    }

    /** O diff navega por todos os arquivos, na ordem da árvore, a partir do selecionado. */
    private fun showDiff() {
        val node = selectedNode() ?: return
        val start = node.userObject as? Entry
            ?: nodeOf(node.takeIf { it.childCount > 0 }?.getChildAt(0))?.userObject as? Entry
            ?: return
        val entries = (model.root as DefaultMutableTreeNode).preorderEnumeration().toList()
            .mapNotNull { nodeOf(it)?.userObject as? Entry }
        diff.show(entries.map { ChangeDiff.Item(it.change, it.path, it.relativePath) }, entries.indexOf(start))
    }

    private fun openFile(entry: Entry?) {
        val file = entry?.path?.virtualFile ?: return
        OpenFileDescriptor(project, file).navigate(true)
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
                is Group -> {
                    icon = if (item.location.root != null) AllIcons.Nodes.Package else AllIcons.Nodes.Folder
                    append(item.location.name)
                    append("  ${if (item.files == 1) "1 arquivo" else "${item.files} arquivos"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (item.comments > 0) append("  ${targets(item.comments)}", TARGETS_ATTRIBUTES)
                    item.location.root?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                }
                is Entry -> {
                    val status = if (item.unversioned) FileStatus.UNKNOWN else item.change.fileStatus
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(item.path.name).icon
                    append(item.path.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                    append("  ${status.text}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (item.comments > 0) append("  ${targets(item.comments)}", TARGETS_ATTRIBUTES)
                    toolTipText = item.relativePath
                }
            }
        }

        private fun targets(count: Int) = if (count == 1) "◎ 1 alvo" else "◎ $count alvos"

        private companion object {
            val TARGETS_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, CommentStatus.PENDING.accent)
        }
    }

    /** Pacote do arquivo, com a raiz de código em [root]; fora das raízes, a pasta e [root] nulo. */
    internal data class Location(val name: String, val root: String?) {
        val key get() = "$root|$name"
    }

    internal companion object {
        private const val PERIMETER_CLEAR = "Perímetro limpo: nenhuma alteração não commitada"

        /** [dir] é a pasta em [dirPath], nula se ela não existir mais. */
        fun locationOf(project: Project, dirPath: String?, dir: VirtualFile?): Location {
            if (dir != null) {
                val sourceRoot = ProjectFileIndex.getInstance(project).getSourceRootForFile(dir)
                val packageName = PackageIndex.getInstance(project).getPackageNameByDirectory(dir)
                if (sourceRoot != null && packageName != null) {
                    return Location(packageName.ifEmpty { "(pacote padrão)" }, relativePath(project, sourceRoot.path))
                }
            }
            val relative = dirPath?.let { relativePath(project, it) }
            val atRoot = relative == null || relative == project.basePath?.replace('\\', '/')
            return Location(if (atRoot) "(raiz do projeto)" else relative, null)
        }

        fun relativePath(project: Project, path: String): String {
            val normalized = path.replace('\\', '/')
            val base = project.basePath?.replace('\\', '/') ?: return normalized
            return normalized.removePrefix("$base/")
        }
    }
}
