package com.mio.irondome.sentinela

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.openapi.project.DumbAwareToggleAction
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
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.IronDomeRoot
import com.mio.irondome.base.MechanismTree
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.base.refreshAction
import com.mio.irondome.base.rootPath
import com.mio.irondome.base.wrapping
import com.mio.irondome.radar.CommentStatus
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewCommentsListener
import com.mio.irondome.radar.ReviewIcons
import com.mio.irondome.radar.TargetComments
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Arquivos com alterações ainda não commitadas, como os que a IA mudou ao aplicar a revisão,
 * agrupados por raiz de código e pacote, só por pacote ou por tipo de alteração, com busca e filtros.
 * O clique só seleciona e o duplo clique abre o arquivo; os botões da barra e o menu de contexto abrem
 * o diff ou o arquivo.
 */
class ChangesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Entry(
        val change: Change,
        val path: FilePath,
        val relativePath: String,
        val location: Location,
        val comments: Int,
        val unversioned: Boolean,
    ) {
        val kind: ChangeKind
            get() = when {
                unversioned -> ChangeKind.UNVERSIONED
                change.fileStatus == FileStatus.ADDED -> ChangeKind.ADDED
                change.fileStatus == FileStatus.DELETED -> ChangeKind.DELETED
                change.fileStatus == FileStatus.MODIFIED -> ChangeKind.MODIFIED
                else -> ChangeKind.OTHER
            }
    }

    internal enum class ChangeKind(val one: String, val many: String, val tone: Tone) {
        MODIFIED("modificado", "modificados", Tone.BLUE),
        ADDED("adicionado", "adicionados", Tone.GREEN),
        DELETED("removido", "removidos", Tone.GRAY),
        UNVERSIONED("não versionado", "não versionados", Tone.ORANGE),
        OTHER("outro", "outros", Tone.PURPLE);

        val title get() = many.replaceFirstChar { it.uppercase() }
    }

    internal enum class Grouping(val label: String, val description: String) {
        ROOT_AND_PACKAGE("Raiz e Pacote", "Agrupa pela raiz de código, como src/main e src/test, e dentro dela pelo pacote"),
        PACKAGE("Pacote", "Um grupo por pacote, com a raiz de código ao lado do nome"),
        KIND("Tipo de Alteração", "Agrupa em modificados, adicionados, removidos e não versionados"),
    }

    /**
     * [key] identifica o grupo entre atualizações, para manter o que o usuário abriu ou recolheu. [dir] é
     * a pasta que pode virar alvo inteiro: só nos pacotes, e nula se a pasta não existir mais.
     */
    private class Group(
        val key: String,
        val name: String,
        val icon: Icon?,
        val files: Int,
        val comments: Int,
        val note: String? = null,
        val dir: VirtualFile? = null,
        val kind: ChangeKind? = null,
    )

    private val service = project.service<ReviewCommentService>()
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = MechanismTree(model, IronDomeIcons.sentinela, Tone.YELLOW).apply {
        cellRenderer = Renderer()
        emptyText.text = PERIMETER_CLEAR
    }
    private val header = SummaryHeader(IronDomeIcons.sentinela, Tone.YELLOW)
    private val diff = project.service<ChangeDiff>()
    private val knownGroups = mutableSetOf<String>()
    private var refreshQueued = false

    private var entries: List<Entry> = emptyList()
    private var commentsByPath: Map<String, Int> = emptyMap()
    private var grouping = Grouping.entries.firstOrNull { it.name == PropertiesComponent.getInstance().getValue(GROUPING_KEY) }
        ?: Grouping.ROOT_AND_PACKAGE
    private var filter = Filter()
    /** A busca abre todos os grupos; ao limpá-la, volta o que estava aberto antes. */
    private var expandedBeforeSearch: Set<String> = emptySet()
    private var searching = false

    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filtrar por nome, pasta ou pacote"
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filter = filter.copy(text = text)
                rebuildTree()
            }
        })
    }

    private val showDiffAction = selectionAction(
        "Inspecionar Diff",
        "Compara o arquivo selecionado, ou o primeiro do grupo, com a versão do último commit",
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
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(6, 8, 4, 8)
                    add(search, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        })
        val expander = DefaultTreeExpander(tree)
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronDome.ChangesToolbar",
            DefaultActionGroup(
                showDiffAction,
                openFileAction,
                Separator.getInstance(),
                groupingActions(),
                filterActions(),
                Separator.getInstance(),
                CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
                CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
                Separator.getInstance(),
                refreshAction { VcsDirtyScopeManager.getInstance(project).markEverythingDirty() },
            ),
            true,
        ).apply { targetComponent = tree }.wrapping().component
        installNavigation()

        val connection = project.messageBus.connect(this)
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleRefresh()
        })
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { scheduleRefresh() })
        refresh()
    }

    override fun dispose() = Unit

    private fun groupingActions() = DefaultActionGroup("Agrupar Por", true).apply {
        templatePresentation.icon = AllIcons.Actions.GroupBy
        templatePresentation.description = "Escolhe como os arquivos ficam agrupados"
        Grouping.entries.forEach { option ->
            add(object : DumbAwareToggleAction(option.label, option.description, null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = grouping == option
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (!state || grouping == option) return
                    grouping = option
                    PropertiesComponent.getInstance().setValue(GROUPING_KEY, option.name)
                    rebuildTree()
                }
            })
        }
    }

    private fun filterActions() = DefaultActionGroup("Filtrar", true).apply {
        templatePresentation.icon = AllIcons.General.Filter
        templatePresentation.description = "Mostra só alguns tipos de alteração ou só os arquivos com alvos"
        ChangeKind.entries.forEach { kind ->
            add(object : DumbAwareToggleAction(kind.title, "Mostra os arquivos ${kind.many}", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = kind !in filter.hiddenKinds
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    filter = filter.copy(hiddenKinds = if (state) filter.hiddenKinds - kind else filter.hiddenKinds + kind)
                    rebuildTree()
                }
            })
        }
        addSeparator()
        add(object : DumbAwareToggleAction("Só com Alvos", "Mostra só os arquivos com alvos do Radar", null) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = filter.onlyTargets
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                filter = filter.copy(onlyTargets = state)
                rebuildTree()
            }
        })
        addSeparator()
        add(selectionAction("Limpar Filtros", "Mostra todos os arquivos de novo", AllIcons.Actions.Close, enabled = { filter.active }) {
            clearFilters()
        })
    }

    private fun clearFilters() {
        filter = Filter()
        if (search.text.isNotEmpty()) search.text = "" else rebuildTree()
    }

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
            entries = emptyList()
            model.setRoot(DefaultMutableTreeNode())
            tree.emptyText.text = "Projeto sem controle de versão: a sentinela não tem o que vigiar"
            header.update("Sem controle de versão", Tone.GRAY.accent, "A sentinela não tem o que vigiar")
            return
        }
        val changeListManager = ChangeListManager.getInstance(project)
        val comments = service.activeComments().groupingBy { it.filePath }.eachCount()
        fun entry(change: Change, path: FilePath, unversioned: Boolean): Entry {
            val relative = relativePath(path.path)
            return Entry(change, path, relative, locationOf(path), comments[relative] ?: 0, unversioned)
        }

        // Com mais de um projeto na janela, só as alterações do escolhido no Iron Dome.
        val base = IronDomeRoot.getInstance(project).selectedDir()?.path
        fun inProject(path: FilePath) = base == null || path.path.replace('\\', '/').startsWith("$base/")
        val versioned = changeListManager.allChanges.mapNotNull { change ->
            (change.afterRevision ?: change.beforeRevision)?.file?.takeIf(::inProject)?.let { entry(change, it, unversioned = false) }
        }
        val unversioned = changeListManager.unversionedFilesPaths.filter(::inProject).map {
            entry(ChangeDiff.unversioned(it), it, unversioned = true)
        }
        commentsByPath = comments
        entries = versioned + unversioned
        rebuildTree()
    }

    private fun rebuildTree() {
        val nowSearching = filter.text.isNotBlank()
        if (nowSearching && !searching) expandedBeforeSearch = expandedKeys()
        val expanded = if (!nowSearching && searching) expandedBeforeSearch else expandedKeys()
        searching = nowSearching
        val selectedPath = selectedEntry()?.path
        val selectedGroup = (selectedNode()?.userObject as? Group)?.key

        val visible = entries.filter { filter.matches(it.relativePath, it.location.name, it.kind, it.comments) }
        updateHeader(visible)
        val root = DefaultMutableTreeNode()
        when (grouping) {
            Grouping.ROOT_AND_PACKAGE -> visible.groupBy { it.location.root }.toSortedMap(nullsLast()).forEach { (sourceRoot, inRoot) ->
                val key = "root|$sourceRoot"
                val packages = packageNodes(key, inRoot, showRoot = false)
                val comments = packages.sumOf { (it.userObject as Group).comments }
                root.add(DefaultMutableTreeNode(Group(key, sourceRoot ?: OTHER_FILES, rootIcon(sourceRoot, inRoot), inRoot.size, comments)).apply {
                    packages.forEach(::add)
                })
            }
            Grouping.PACKAGE -> packageNodes("package", visible, showRoot = true).forEach(root::add)
            Grouping.KIND -> visible.groupBy { it.kind }.toSortedMap().forEach { (kind, ofKind) ->
                root.add(DefaultMutableTreeNode(Group("kind|$kind", kind.title, null, ofKind.size, ofKind.sumOf { it.comments }, kind = kind)).apply {
                    ofKind.sortedWith(compareBy({ it.location.root == null }, { it.location.name }, { it.path.name.lowercase() }))
                        .forEach { add(DefaultMutableTreeNode(it)) }
                })
            }
        }
        model.setRoot(root)
        restoreExpansion(root, expanded, visible.size)

        root.preorderEnumeration().toList().mapNotNull(::nodeOf).firstOrNull { node ->
            when (val item = node.userObject) {
                is Entry -> item.path == selectedPath && tree.isVisible(TreePath(node.path))
                is Group -> item.key == selectedGroup
                else -> false
            }
        }?.let { tree.selectionPath = TreePath(it.path) }

        if (entries.isEmpty()) {
            tree.emptyText.text = PERIMETER_CLEAR
        } else {
            tree.emptyText.text = "Nenhum arquivo com esse filtro"
            tree.emptyText.appendSecondaryText("Limpar filtros", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { clearFilters() }
        }
    }

    private fun packageNodes(parentKey: String, entries: List<Entry>, showRoot: Boolean): List<DefaultMutableTreeNode> =
        entries.groupBy { it.location }
            .toSortedMap(compareBy<Location>({ it.root == null }, { it.root }, { it.name }))
            .map { (location, inPackage) ->
                val dir = inPackage.firstNotNullOfOrNull { it.path.parentPath?.virtualFile }
                val packageComments = dir?.let { commentsByPath[relativePath(it.path)] } ?: 0
                val group = Group(
                    key = "$parentKey|${location.key}",
                    name = location.name,
                    icon = if (location.root != null) AllIcons.Nodes.Package else AllIcons.Nodes.Folder,
                    files = inPackage.size,
                    comments = inPackage.sumOf { it.comments } + packageComments,
                    note = location.root.takeIf { showRoot },
                    dir = dir,
                )
                DefaultMutableTreeNode(group).apply {
                    inPackage.sortedBy { it.path.name.lowercase() }.forEach { add(DefaultMutableTreeNode(it)) }
                }
            }

    private fun rootIcon(sourceRoot: String?, entries: List<Entry>): Icon {
        if (sourceRoot == null) return AllIcons.Nodes.Folder
        val file = entries.firstNotNullOfOrNull { it.path.parentPath?.virtualFile }
        val test = file != null && ProjectFileIndex.getInstance(project).isInTestSourceContent(file)
        return if (test) AllIcons.Modules.TestRoot else AllIcons.Modules.SourceRoot
    }

    private fun expandedKeys(): Set<String> =
        tree.getExpandedDescendants(TreePath(model.root))?.toList().orEmpty()
            .mapNotNull { (nodeOf(it.lastPathComponent)?.userObject as? Group)?.key }
            .toSet()

    /**
     * Mantém o que o usuário abriu ou recolheu. Grupos novos que contêm grupos chegam abertos; os de
     * arquivos, só enquanto a lista é curta. Com busca, tudo abre para mostrar o que ela achou.
     */
    private fun restoreExpansion(root: DefaultMutableTreeNode, expanded: Set<String>, files: Int) {
        for (node in root.preorderEnumeration().toList().mapNotNull(::nodeOf)) {
            val group = node.userObject as? Group ?: continue
            val parent = node.parent as DefaultMutableTreeNode
            if (parent !== root && !tree.isExpanded(TreePath(parent.path))) continue
            val holdsGroups = node.childCount > 0 && nodeOf(node.firstChild)?.userObject is Group
            val open = when {
                searching -> true
                group.key in expanded -> true
                group.key in knownGroups -> false
                else -> holdsGroups || files <= EXPAND_UP_TO
            }
            if (open) tree.expandPath(TreePath(node.path))
            if (!searching) knownGroups += group.key
        }
    }

    /** O perímetro em números: quantos arquivos de cada tipo de alteração e quantos alvos marcados. */
    private fun updateHeader(visible: List<Entry>) {
        if (entries.isEmpty()) {
            header.update("Perímetro limpo", Tone.GREEN.accent, "Nenhuma alteração não commitada")
            return
        }
        val kinds = entries.groupingBy { it.kind }.eachCount()
        val packages = entries.map { it.location.key }.distinct().size
        val targets = entries.sumOf { it.comments }
        header.update(
            title = plural(entries.size, "arquivo alterado", "arquivos alterados"),
            subtitle = listOfNotNull(
                "em ${plural(packages, "pacote", "pacotes")}",
                plural(targets, "alvo na mira", "alvos na mira").takeIf { targets > 0 },
                "mostrando ${visible.size} com o filtro".takeIf { filter.active },
            ).joinToString(" · "),
            stats = ChangeKind.entries.map { kind ->
                val count = kinds[kind] ?: 0
                Stat(count, if (count == 1) kind.one else kind.many, kind.tone)
            },
            bar = ChangeKind.entries.map { Segment(kinds[it] ?: 0, it.tone.accent) },
        )
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
            "IronDome.ChangesPopup",
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

    /** O diff navega pelos arquivos exibidos, na ordem da árvore, a partir do selecionado. */
    private fun showDiff() {
        val node = selectedNode() ?: return
        val entries = (model.root as DefaultMutableTreeNode).preorderEnumeration().toList()
            .mapNotNull { nodeOf(it)?.userObject as? Entry }
        val start = node.userObject as? Entry
            ?: node.preorderEnumeration().toList().firstNotNullOfOrNull { nodeOf(it)?.userObject as? Entry }
            ?: return
        diff.show(entries.map { ChangeDiff.Item(it.change, it.path, it.relativePath) }, entries.indexOf(start))
    }

    private fun openFile(entry: Entry?) {
        val file = entry?.path?.virtualFile ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private class Renderer : RichTreeCellRenderer() {

        override fun describe(row: Row, value: Any?) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val item = node.userObject) {
                is Group -> {
                    row.icon(item.icon)
                    row.text(item.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    item.note?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    row.pill(plural(item.files, "arquivo", "arquivos"), item.kind?.tone ?: Tone.GRAY)
                    if (item.comments > 0) row.pill("◎ ${plural(item.comments, "alvo", "alvos")}", CommentStatus.PENDING.tone, bold = true)
                }
                is Entry -> {
                    val status = if (item.unversioned) FileStatus.UNKNOWN else item.change.fileStatus
                    row.icon(FileTypeManager.getInstance().getFileTypeByFileName(item.path.name).icon)
                    row.text(item.path.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                    val groupedByKind = ((node.parent as? DefaultMutableTreeNode)?.userObject as? Group)?.kind != null
                    if (groupedByKind) row.text(item.location.name, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    else row.pill(item.kind.one, item.kind.tone)
                    if (item.comments > 0) row.pill("◎ ${plural(item.comments, "alvo", "alvos")}", CommentStatus.PENDING.tone, bold = true)
                    row.tooltip(item.relativePath)
                }
            }
        }
    }

    /** Pacote do arquivo, com a raiz de código em [root]; fora das raízes, a pasta e [root] nulo. */
    internal data class Location(val name: String, val root: String?) {
        val key get() = "$root|$name"
    }

    /** Esconde os tipos em [hiddenKinds]; [text] procura no caminho e no pacote, sem diferenciar maiúsculas. */
    internal data class Filter(
        val text: String = "",
        val hiddenKinds: Set<ChangeKind> = emptySet(),
        val onlyTargets: Boolean = false,
    ) {
        val active get() = text.isNotBlank() || hiddenKinds.isNotEmpty() || onlyTargets

        fun matches(relativePath: String, packageName: String, kind: ChangeKind, targets: Int): Boolean {
            if (kind in hiddenKinds || (onlyTargets && targets == 0)) return false
            val query = text.trim()
            return query.isEmpty() || relativePath.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)
        }
    }

    internal companion object {
        private const val PERIMETER_CLEAR = "Perímetro limpo: nenhuma alteração não commitada"
        private const val OTHER_FILES = "Outros arquivos"
        private const val GROUPING_KEY = "IronDome.Changes.Grouping"
        /** Acima disso, os grupos de arquivos novos chegam recolhidos. */
        private const val EXPAND_UP_TO = 20

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
            val atRoot = relative == null || relative == project.rootPath()
            return Location(if (atRoot) "(raiz do projeto)" else relative, null)
        }

        fun relativePath(project: Project, path: String): String {
            val normalized = path.replace('\\', '/')
            val base = project.rootPath() ?: return normalized
            return normalized.removePrefix("$base/")
        }
    }
}
