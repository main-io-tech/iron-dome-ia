package com.mio.review.ui.toolwindow

import com.intellij.icons.AllIcons
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
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.mio.review.ReviewCommentService
import com.mio.review.ReviewCommentsListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Arquivos com alterações ainda não commitadas, como os que a IA mudou ao aplicar a revisão.
 * O clique só seleciona; os botões da barra e o menu de contexto abrem o diff ou o arquivo.
 */
class ChangesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Entry(
        val change: Change,
        val path: FilePath,
        val relativePath: String,
        val comments: Int,
        val unversioned: Boolean,
    )

    private val service = project.service<ReviewCommentService>()
    private val model = DefaultListModel<Entry>()
    private val list = JBList(model).apply {
        cellRenderer = Renderer()
        emptyText.text = "Nenhuma alteração não commitada"
    }
    private val diff = ChangeDiff(project)
    private var refreshQueued = false

    private val showDiffAction = selectionAction("Ver Diff", "Compara o arquivo selecionado com a versão do último commit", AllIcons.Actions.Diff) {
        showDiff(list.selectedIndex)
    }
    private val openFileAction = selectionAction("Abrir Arquivo", "Abre o arquivo selecionado no editor", AllIcons.Actions.EditSource) {
        openFile(list.selectedValue)
    }

    init {
        setContent(ScrollPaneFactory.createScrollPane(list, true))
        toolbar = ActionManager.getInstance().createActionToolbar(
            "MioReview.ChangesToolbar",
            DefaultActionGroup(
                showDiffAction,
                openFileAction,
                Separator.getInstance(),
                refreshAction { VcsDirtyScopeManager.getInstance(project).markEverythingDirty() },
            ),
            true,
        ).apply { targetComponent = list }.component
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

    private fun refresh() {
        if (!ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
            model.clear()
            list.emptyText.text = "Projeto sem controle de versão"
            return
        }
        list.emptyText.text = "Nenhuma alteração não commitada"
        val changeListManager = ChangeListManager.getInstance(project)
        val commentsByPath = service.activeComments().groupingBy { it.filePath }.eachCount()
        fun entry(change: Change, path: FilePath, unversioned: Boolean): Entry {
            val relative = relativePath(path)
            return Entry(change, path, relative, commentsByPath[relative] ?: 0, unversioned)
        }

        val versioned = changeListManager.allChanges.mapNotNull { change ->
            (change.afterRevision ?: change.beforeRevision)?.file?.let { entry(change, it, unversioned = false) }
        }
        val unversioned = changeListManager.unversionedFilesPaths.map {
            entry(ChangeDiff.unversioned(it), it, unversioned = true)
        }
        val selected = list.selectedValue?.path
        model.clear()
        (versioned + unversioned).sortedBy { it.relativePath }.forEach(model::addElement)
        selected?.let { path -> (0 until model.size()).firstOrNull { model[it].path == path }?.let(list::setSelectedIndex) }
    }

    private fun relativePath(path: FilePath): String {
        val base = project.basePath?.replace('\\', '/') ?: return path.path
        return path.path.removePrefix("$base/")
    }

    private fun selectionAction(text: String, description: String, icon: Icon, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = list.selectedIndex >= 0
            }
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun installNavigation() {
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val index = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it).contains(e.point) } ?: return
                list.selectedIndex = index
            }
        })
        PopupHandler.installPopupMenu(list, DefaultActionGroup(showDiffAction, openFileAction), "MioReview.ChangesPopup")
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && list.selectedIndex >= 0) showDiff(list.selectedIndex)
            }
        })
    }

    /** O diff navega por todos os arquivos da lista, a partir do clicado. */
    private fun showDiff(index: Int) {
        diff.show((0 until model.size()).map { model[it].let { entry -> ChangeDiff.Item(entry.change, entry.path, entry.relativePath) } }, index)
    }

    private fun openFile(entry: Entry?) {
        val file = entry?.path?.virtualFile ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private class Renderer : ColoredListCellRenderer<Entry>() {

        override fun customizeCellRenderer(
            list: JList<out Entry>,
            value: Entry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val entry = value ?: return
            val status = if (entry.unversioned) FileStatus.UNKNOWN else entry.change.fileStatus
            icon = FileTypeManager.getInstance().getFileTypeByFileName(entry.path.name).icon
            append(entry.path.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
            val dir = entry.relativePath.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) append("  $dir", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ${status.text}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (entry.comments > 0) {
                val label = if (entry.comments == 1) "1 comentário" else "${entry.comments} comentários"
                append("  $label", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
            toolTipText = entry.relativePath
        }
    }
}
