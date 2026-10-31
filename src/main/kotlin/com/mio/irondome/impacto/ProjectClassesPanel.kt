package com.mio.irondome.impacto

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElement
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ProjectClass(
    val qualifiedName: String,
    val name: String,
    val packageName: String,
    val kind: ClassKind,
    val file: VirtualFile,
    val test: Boolean,
)

/**
 * Aba "Todas as classes": as classes Java e Kotlin do projeto, carregadas sob demanda. Clique ou Enter
 * analisa a classe sem abri-la no editor; duplo clique também abre o arquivo.
 */
class ProjectClassesPanel(private val project: Project, private val onAnalyze: (String) -> Unit) : JPanel(BorderLayout()), Disposable {

    private var all: List<ProjectClass> = emptyList()
    private var loaded = false
    private var loading = false

    /** Classe em análise no diagrama, destacada em negrito. */
    var focus: String? = null
        set(value) {
            field = value
            list.repaint()
        }

    private val model = CollectionListModel<ProjectClass>()
    private val list = JBList(model).apply {
        cellRenderer = Renderer()
        emptyText.text = "Nenhuma classe carregada"
        emptyText.appendSecondaryText("Carregar todas as classes", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { load() }
    }
    private val loadButton = JButton("Carregar todas as classes", AllIcons.Actions.Refresh).apply {
        toolTipText = "Lista as classes Java e Kotlin do projeto para analisar sem abrir o arquivo"
        addActionListener { load() }
    }
    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filtrar por nome ou pacote"
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
    }
    private val showTests = JBCheckBox("Mostrar classes de teste").apply { addActionListener { applyFilter() } }
    private val count = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            add(loadButton)
            add(search)
            add(showTests)
            add(count)
        }
        add(controls, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        installNavigation()
        project.messageBus.connect(this).subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = list.repaint()
        })
    }

    override fun dispose() = Unit

    fun loadIfNeeded() {
        if (!loaded) load()
    }

    fun load() {
        if (loading) return
        loading = true
        loadButton.isEnabled = false
        count.text = "Carregando…"
        object : Task.Backgroundable(project, "Listando as classes do projeto", true) {
            private var result: List<ProjectClass> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                result = collect(project, indicator)
            }

            override fun onSuccess() {
                all = result
                loaded = true
                loadButton.text = "Recarregar classes"
                applyFilter()
            }

            override fun onCancel() {
                count.text = "Carregamento cancelado."
            }

            override fun onThrowable(error: Throwable) {
                if (error !is ProcessCanceledException) count.text = "Falha ao listar: ${error.message ?: error.javaClass.simpleName}"
            }

            override fun onFinished() {
                loading = false
                loadButton.isEnabled = true
            }
        }.queue()
    }

    private fun applyFilter() {
        val text = search.text.trim().lowercase()
        val visible = all.filter { item ->
            (showTests.isSelected || !item.test) &&
                (text.isEmpty() || text in item.name.lowercase() || text in item.packageName.lowercase())
        }
        model.replaceAll(visible)
        if (!loaded) return
        val total = all.count { showTests.isSelected || !it.test }
        count.text = if (visible.size == total) "$total classes" else "${visible.size} de $total classes"
        list.emptyText.text = if (all.isEmpty()) "Nenhuma classe Java ou Kotlin no projeto" else "Nenhuma classe com esse filtro"
    }

    private fun installNavigation() {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val index = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it).contains(e.point) } ?: return
                val item = model.getElementAt(index)
                if (e.clickCount >= 2) OpenFileDescriptor(project, item.file).navigate(true) else onAnalyze(item.qualifiedName)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) list.selectedValue?.let { onAnalyze(it.qualifiedName) }
            }
        })
    }

    private inner class Renderer : ColoredListCellRenderer<ProjectClass>() {
        override fun customizeCellRenderer(list: JList<out ProjectClass>, value: ProjectClass?, index: Int, selected: Boolean, hasFocus: Boolean) {
            val item = value ?: return
            icon = iconOf(item)
            val status = ChangeListManager.getInstance(project).getStatus(item.file)
            val style = if (item.qualifiedName == focus) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
            val color = status.color.takeIf { status != FileStatus.NOT_CHANGED }
            append(item.name, SimpleTextAttributes(style, color))
            if (item.packageName.isNotEmpty()) append("  ${item.packageName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (item.test) append("  teste", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (color != null) append("  alterada, sem commit", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            toolTipText = item.qualifiedName
        }
    }

    companion object {
        private const val BATCH = 50

        private fun iconOf(item: ProjectClass) = when {
            item.test -> AllIcons.Nodes.Test
            else -> when (item.kind) {
                ClassKind.INTERFACE -> AllIcons.Nodes.Interface
                ClassKind.ENUM -> AllIcons.Nodes.Enum
                ClassKind.ANNOTATION -> AllIcons.Nodes.Annotationtype
                ClassKind.ABSTRACT -> AllIcons.Nodes.AbstractClass
                ClassKind.CLASS -> AllIcons.Nodes.Class
            }
        }

        /** Classes de topo dos arquivos .java e .kt do projeto, em lotes para não segurar a IDE. */
        internal fun collect(project: Project, indicator: ProgressIndicator): List<ProjectClass> {
            val scope = GlobalSearchScope.projectScope(project)
            val files = ReadAction.nonBlocking(Callable { listOf("java", "kt").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) } })
                .inSmartMode(project).wrapProgress(indicator).executeSynchronously()
            val fileIndex = ProjectFileIndex.getInstance(project)
            val result = mutableListOf<ProjectClass>()
            files.chunked(BATCH).forEachIndexed { batch, chunk ->
                indicator.checkCanceled()
                indicator.fraction = batch * BATCH.toDouble() / files.size.coerceAtLeast(1)
                result += ReadAction.nonBlocking(Callable {
                    chunk.filter { it.isValid }.flatMap { file ->
                        val uFile = PsiManager.getInstance(project).findFile(file)?.toUElement() as? UFile ?: return@flatMap emptyList()
                        val test = fileIndex.isInTestSourceContent(file)
                        uFile.classes.mapNotNull { uClass ->
                            val name = uClass.qualifiedName ?: return@mapNotNull null
                            ProjectClass(name, uClass.name ?: DependencyAnalyzer.simpleName(name), name.substringBeforeLast('.', ""),
                                DependencyAnalyzer.kindOf(uClass.javaPsi), file, test)
                        }
                    }
                }).inSmartMode(project).wrapProgress(indicator).executeSynchronously()
            }
            return result.distinctBy { it.qualifiedName }.sortedWith(compareBy({ it.packageName }, { it.name }))
        }
    }
}
