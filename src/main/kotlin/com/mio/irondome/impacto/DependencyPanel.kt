package com.mio.irondome.impacto

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.rootDir
import com.mio.irondome.interceptor.AgentProgressPanel
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentSettings
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.interceptor.FireWithAiAction
import com.mio.irondome.radar.ReviewCommentsListener
import com.mio.irondome.sentinela.ChangeDiff
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Aba "Dependências": diagrama da classe analisada e seus vizinhos, com quantas classes dependem de
 * cada uma, de quantas ela depende, o risco de alterá-la e os testes que a usam.
 */
class DependencyPanel(private val project: Project, private val toolWindow: ToolWindow) : JPanel(BorderLayout()), Disposable {

    private val diagram = DiagramBrowser(this, ::onDiagramMessage)
    private val depth = ComboBox(arrayOf(1, 2, 3)).apply {
        renderer = SimpleListCellRenderer.create("") { if (it == 1) "1 nível" else "$it níveis" }
        toolTipText = "Quantos passos de dependência mostrar a partir da classe analisada"
    }
    private val direction = ComboBox(Direction.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        toolTipText = "Mostrar quem depende da classe, de quem ela depende, ou os dois"
    }
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyLeft(8)
    }
    private val classModel = ClassTableModel()
    private val classTable = table(classModel)
    private val tabs = JBTabbedPane()
    private val bottomPanel = JPanel(BorderLayout())
    private val minimizedBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), JBUI.scale(3))).apply {
        border = JBUI.Borders.customLineTop(JBColor.border())
        add(JBLabel(AllIcons.General.ArrowUp).apply { toolTipText = "Painel minimizado: clique numa aba para mostrar" })
    }
    private val projectClasses = ProjectClassesPanel(project, ::analyze).also { Disposer.register(this, it) }

    private val diff = project.service<ChangeDiff>()

    private var focus: String? = null
    private var graph: DependencyGraph? = null
    private var changes: Map<String, UncommittedChange> = emptyMap()
    private var comments: Map<String, List<ImpactComment>> = emptyMap()
    /** A última análise de impacto pela IA; vale só enquanto o diagrama for o da mesma classe. */
    private var impact: ImpactReport? = null
    private var refreshQueued = false
    private var commentsQueued = false
    @Volatile
    private var running: ProgressIndicator? = null
    @Volatile
    private var generations = 0
    private var syncingSelection = false

    init {
        val actions = DefaultActionGroup(
            object : DumbAwareAction("Mirar Classe Atual", "Calcula o raio de impacto da classe aberta no editor", IronDomeIcons.impacto) {
                override fun actionPerformed(e: AnActionEvent) = analyzeCurrentEditor()
            },
            object : DumbAwareAction("Todas as Classes", "Lista todas as classes do projeto para analisar sem abrir o arquivo", AllIcons.Nodes.Class) {
                override fun actionPerformed(e: AnActionEvent) {
                    showTab(ALL_CLASSES_TAB)
                    projectClasses.loadIfNeeded()
                }
            },
            object : DumbAwareToggleAction(
                "Mostrar Painel Inferior",
                "Mostra ou esconde as tabelas, a lista de classes e as explicações, deixando o diagrama com todo o espaço",
                AllIcons.Actions.SplitHorizontally,
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = bottomPanel.isVisible
                override fun setSelected(e: AnActionEvent, state: Boolean) = setBottomVisible(state)
            },
            object : DumbAwareAction("Analisar Impacto com IA", null, IronDomeIcons.ia) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val busy = AgentRunner.getInstance(project).running != null
                    val sources = comments.isNotEmpty() || changes.isNotEmpty()
                    e.presentation.isEnabled = graph != null && sources && !busy && AgentSettings.ready
                    e.presentation.description = when {
                        graph == null -> "Calcule o raio de impacto de uma classe primeiro"
                        !sources -> "Nenhuma classe do diagrama tem comentários do Radar ou da Patrulha nem alterações sem commit"
                        !AgentSettings.ready -> FireWithAiAction.NOT_READY
                        else -> "A IA (${AgentSettings.provider.title}) lê os comentários e as alterações das classes do diagrama, " +
                            "sem alterar nada, e aponta quais classes eles afetam"
                    }
                }
                override fun actionPerformed(e: AnActionEvent) = analyzeImpact()
            },
            object : DumbAwareAction("Atualizar", "Refaz a análise da classe atual do diagrama", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = focus != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    focus?.let(::analyze)
                }
            },
            object : DumbAwareAction("Manual de Campo", "Explica o que cada número, cor e coluna significa", AllIcons.Actions.Help) {
                override fun actionPerformed(e: AnActionEvent) = showTab(HELP_TAB)
            },
            object : DumbAwareToggleAction("Expandir", "Ocupa toda a janela da IDE", AllIcons.General.ExpandComponent) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = ToolWindowManager.getInstance(project).isMaximized(toolWindow)
                override fun setSelected(e: AnActionEvent, state: Boolean) =
                    ToolWindowManager.getInstance(project).setMaximized(toolWindow, state)
            },
            object : DumbAwareToggleAction("Abrir em Janela", "Destaca o painel numa janela separada", AllIcons.Actions.MoveToWindow) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = toolWindow.type == ToolWindowType.WINDOWED
                override fun setSelected(e: AnActionEvent, state: Boolean) =
                    toolWindow.setType(if (state) ToolWindowType.WINDOWED else ToolWindowType.DOCKED, null)
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("IronDome.DependenciesToolbar", actions, true)
        toolbar.targetComponent = this

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(toolbar.component)
            add(JBLabel("Profundidade"))
            add(depth)
            add(JBLabel("Direção"))
            add(direction)
            add(status)
        }
        listOf(depth, direction).forEach { combo -> combo.addActionListener { focus?.let(::analyze) } }

        tabs.addTab(TAB_TITLES[DIAGRAM_CLASSES_TAB], JBScrollPane(classTable))
        tabs.addTab(TAB_TITLES[ALL_CLASSES_TAB], projectClasses)
        tabs.addTab(TAB_TITLES[HELP_TAB], JBScrollPane(helpPane()))
        tabs.selectedIndex = ALL_CLASSES_TAB
        bottomPanel.add(tabs, BorderLayout.CENTER)
        bottomPanel.add(minimizeToolbar(), BorderLayout.EAST)
        TAB_TITLES.forEachIndexed { index, title ->
            minimizedBar.add(ActionLink(title) { showTab(index) }.apply { toolTipText = "Mostrar o painel em $title" })
        }
        val splitter = OnePixelSplitter(true, "IronDome.DependenciesSplitter", 0.68f).apply {
            firstComponent = diagram.component
            secondComponent = bottomPanel
        }
        add(JPanel(VerticalLayout(0)).apply {
            add(controls)
            add(AgentProgressPanel(project, DependencyTab.TITLE, this@DependencyPanel))
        }, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(minimizedBar, BorderLayout.SOUTH)
        setBottomVisible(!PropertiesComponent.getInstance().getBoolean(BOTTOM_HIDDEN_KEY))

        installTableNavigation()
        val connection = project.messageBus.connect(this)
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleChangesRefresh()
        })
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { scheduleCommentsRefresh() })
        diagram.showMessage(
            "Escolha uma classe na aba Todas as classes, abaixo, clique em Mirar Classe Atual com ela aberta no editor, " +
                "ou clique com o botão direito numa classe ou arquivo e use Iron Dome IA: Calcular Raio de Impacto.",
        )
    }

    override fun dispose() {
        running?.cancel()
    }

    /** Minimizado, o painel inferior vira uma barra com o nome das abas e o diagrama fica com o espaço. */
    private fun setBottomVisible(visible: Boolean) {
        bottomPanel.isVisible = visible
        minimizedBar.isVisible = !visible
        PropertiesComponent.getInstance().setValue(BOTTOM_HIDDEN_KEY, !visible)
        revalidate()
        repaint()
    }

    private fun showTab(index: Int) {
        if (!bottomPanel.isVisible) setBottomVisible(true)
        tabs.selectedIndex = index
    }

    private fun minimizeToolbar(): JComponent {
        val minimize = object : DumbAwareAction("Minimizar Painel", "Recolhe as abas para uma barra e deixa o diagrama com todo o espaço", AllIcons.General.HideToolWindow) {
            override fun actionPerformed(e: AnActionEvent) = setBottomVisible(false)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("IronDome.DependenciesBottom", DefaultActionGroup(minimize), false)
        toolbar.targetComponent = bottomPanel
        return toolbar.component
    }

    fun analyzeCurrentEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
        if (editor == null || file == null) {
            status.text = "Abra uma classe Java ou Kotlin no editor."
            return
        }
        analyzeAt(file, editor.caretModel.offset)
    }

    fun analyzeAt(file: PsiFile, offset: Int?) {
        run("Calculando o raio de impacto") {
            ReadAction.nonBlocking(Callable { DependencyAnalyzer.classAt(file, offset) }).inSmartMode(project).executeSynchronously()
        }
    }

    fun analyze(qualifiedName: String) {
        run("Calculando o raio de impacto de ${DependencyAnalyzer.simpleName(qualifiedName)}") { qualifiedName }
    }

    /** Só o resultado da análise mais recente aparece; as anteriores são canceladas. */
    private fun run(title: String, resolveFocus: () -> String?) {
        running?.cancel()
        val generation = ++generations
        val selectedDepth = depth.item ?: 1
        val selectedDirection = direction.item ?: Direction.BOTH
        status.text = "Varrendo as dependências…"
        object : Task.Backgroundable(project, title, true) {
            private var graph: DependencyGraph? = null
            private var target: String? = null

            override fun run(indicator: ProgressIndicator) {
                if (generation != generations) return
                running = indicator
                val name = resolveFocus() ?: return
                target = name
                graph = DependencyAnalyzer(project, indicator).analyze(name, selectedDepth, selectedDirection)
            }

            override fun onSuccess() {
                if (generation != generations) return
                val result = graph
                if (result == null) {
                    status.text = if (target == null) "Nenhuma classe Java ou Kotlin na posição do cursor." else "Classe não encontrada no projeto."
                    return
                }
                show(result)
            }

            override fun onCancel() {
                if (generation == generations) status.text = "Análise cancelada."
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException || generation != generations) return
                status.text = "Falha na análise: ${error.message ?: error.javaClass.simpleName}"
            }
        }.queue()
    }

    private fun show(graph: DependencyGraph) {
        focus = graph.focus
        projectClasses.focus = graph.focus
        this.graph = graph
        changes = uncommitted(graph)
        comments = ImpactComments.byClass(graph.nodes, ImpactComments.collect(project))
        impact = impact?.takeIf { it.focus == graph.focus }
            ?.let { report -> report.copy(impacts = report.impacts.filter { item -> graph.nodes.any { it.qualifiedName == item.qualifiedName } }) }
        diagram.show(graph, changeKinds(), hasVcs(), comments, impact)
        classModel.update(graph.nodes, changeKinds())
        updateStatus()
    }

    /** Os comentários do Radar e da Patrulha mudaram: atualiza as marcas sem refazer a análise. */
    private fun scheduleCommentsRefresh() {
        if (commentsQueued) return
        commentsQueued = true
        ApplicationManager.getApplication().invokeLater({
            commentsQueued = false
            val graph = graph ?: return@invokeLater
            val next = ImpactComments.byClass(graph.nodes, ImpactComments.collect(project))
            if (next == comments) return@invokeLater
            comments = next
            diagram.updateComments(comments)
            updateStatus()
        }, project.disposed)
    }

    /**
     * Pede à IA, só para ler, as classes do diagrama afetadas pelos comentários pendentes e pelas alterações sem
     * commit. O `git diff` das alterações vai junto, lido fora da EDT.
     */
    private fun analyzeImpact() {
        val graph = graph ?: return
        val snapshot = comments
        val kinds = changeKinds()
        val paths = changes.values.map { it.node.filePath }.filter { it.isNotEmpty() }.distinct()
        val root = project.rootDir()?.path ?: project.basePath ?: return
        diagram.updateAnalyzing(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val diff = gitDiff(root, paths)
            ApplicationManager.getApplication().invokeLater({ startImpact(graph, snapshot, kinds, diff) }, project.disposed)
        }
    }

    private fun startImpact(graph: DependencyGraph, snapshot: Map<String, List<ImpactComment>>, kinds: Map<String, FileChange>, diff: String) {
        val runner = AgentRunner.getInstance(project)
        val name = DependencyAnalyzer.simpleName(graph.focus)
        val instructions = runner.writeInstructions(ImpactAnalysis.fileName(graph.focus), ImpactAnalysis.instructions(graph, snapshot, kinds, diff))
        if (instructions == null) {
            diagram.updateAnalyzing(false)
            return
        }
        val task = AgentTask(
            "Impacto em $name",
            "Analise o impacto dos comentários e das alterações descritos em $instructions, seguindo as instruções do arquivo, e responda só com o JSON pedido.",
            write = false,
            origin = DependencyTab.TITLE,
        )
        runner.run(task) { result ->
            if (this.graph?.focus == graph.focus) diagram.updateAnalyzing(false)
            if (!result.ok) return@run
            val report = ImpactAnalysis.parse(result.answer, graph)
            if (report == null) {
                status.text = "A IA não devolveu o JSON pedido: veja a resposta na notificação."
                return@run
            }
            impact = report
            if (this.graph?.focus == graph.focus) {
                diagram.updateImpact(report)
                updateStatus()
            }
        }
        if (runner.running?.title != task.title) diagram.updateAnalyzing(false)
    }

    private fun gitDiff(root: String, paths: List<String>): String {
        if (paths.isEmpty() || !hasVcs()) return ""
        return runCatching {
            val command = GeneralCommandLine(listOf("git", "diff", "HEAD", "--no-color", "--") + paths)
                .withWorkDirectory(root)
                .withCharset(StandardCharsets.UTF_8)
            ExecUtil.execAndGetOutput(command, GIT_TIMEOUT_MS).takeIf { it.exitCode == 0 && !it.isTimeout }?.stdout.orEmpty()
        }.getOrDefault("")
    }

    private fun updateStatus() {
        val graph = graph ?: return
        status.text = buildString {
            append(DependencyAnalyzer.simpleName(graph.focus))
            graph.nodes.firstOrNull { it.qualifiedName == graph.focus }?.let { node ->
                append(" · risco ao alterar ${node.risk.label.lowercase()} · testes: ${node.tests.coverage.label.lowercase()}")
            }
            append(" · ${graph.nodes.size} classes · ${graph.edges.size} ligações")
            if (changes.isNotEmpty()) append(" · ${changes.size} alterada(s) sem commit")
            if (comments.isNotEmpty()) append(" · ${comments.size} com comentário(s)")
            impact?.takeIf { it.focus == graph.focus }?.let { append(" · IA: ${it.impacts.size} impactada(s)") }
            if (graph.cycleCount > 0) append(" · ${graph.cycleCount} ciclo(s)")
            if (graph.truncated) append(" · limite de ${DependencyAnalyzer.MAX_NODES} classes atingido")
        }
    }

    private fun hasVcs() = ProjectLevelVcsManager.getInstance(project).hasActiveVcss()

    private fun uncommitted(graph: DependencyGraph): Map<String, UncommittedChange> =
        if (!hasVcs()) emptyMap()
        else graph.nodes.mapNotNull { node -> UncommittedChange.of(project, node)?.let { node.qualifiedName to it } }.toMap()

    private fun changeKinds() = changes.mapValues { it.value.kind }

    /** Acompanha a aba Alterações: commits, edições e reverts atualizam as marcas sem refazer a análise. */
    private fun scheduleChangesRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        ApplicationManager.getApplication().invokeLater({
            refreshQueued = false
            val graph = graph ?: return@invokeLater
            val previous = changeKinds()
            changes = uncommitted(graph)
            if (changeKinds() == previous) return@invokeLater
            diagram.updateChanges(changeKinds(), hasVcs())
            classModel.updateChanges(changeKinds())
            updateStatus()
        }, project.disposed)
    }

    /** O diff passa pelas classes alteradas do diagrama, a partir da clicada. */
    private fun showDiff(qualifiedName: String) {
        val items = changes.values.distinctBy { it.node.file }.sortedBy { it.node.filePath }
        val index = items.indexOfFirst { it.node.file == changes[qualifiedName]?.node?.file }.takeIf { it >= 0 } ?: return
        diff.show(items.map { it.diffItem() }, index)
    }

    private fun onDiagramMessage(message: String) {
        val action = message.substringBefore(':')
        val name = message.substringAfter(':')
        when (action) {
            "open" -> open(name)
            "focus" -> analyze(name)
            "select" -> selectRow(name)
            "diff" -> showDiff(name)
            "clearImpact" -> {
                impact = null
                updateStatus()
            }
        }
    }

    private fun selectRow(qualifiedName: String) {
        val row = classModel.indexOf(qualifiedName).takeIf { it >= 0 } ?: return
        val viewRow = classTable.convertRowIndexToView(row)
        syncingSelection = true
        try {
            classTable.selectionModel.setSelectionInterval(viewRow, viewRow)
            classTable.scrollRectToVisible(classTable.getCellRect(viewRow, 0, true))
        } finally {
            syncingSelection = false
        }
    }

    private fun open(qualifiedName: String) {
        val target = ReadAction.compute<Navigatable?, RuntimeException> {
            JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.projectScope(project))
                ?.navigationElement as? Navigatable
        }
        target?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun installTableNavigation() {
        classTable.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting || syncingSelection) return@addListSelectionListener
            val row = classTable.selectedRow.takeIf { it >= 0 } ?: return@addListSelectionListener
            diagram.select(classModel.nameAt(classTable.convertRowIndexToModel(row)))
        }
        classTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val row = classTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                open(classModel.nameAt(classTable.convertRowIndexToModel(row)))
            }
        })
    }

    private fun table(model: ClassTableModel) = JBTable(model).apply {
        autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        setDefaultRenderer(ChangeRisk::class.java, RiskRenderer())
        setDefaultRenderer(TestUsage::class.java, TestsRenderer())
        setDefaultRenderer(FileChange::class.java, ChangeRenderer())
        tableHeader.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val column = tableHeader.columnAtPoint(e.point)
                tableHeader.toolTipText = if (column < 0) null else model.tooltip(convertColumnIndexToModel(column))
            }
        })
    }

    private fun helpPane() = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        border = JBUI.Borders.empty(8, 12)
        text = MetricGuide.topics.joinToString("", "<html><body>", "</body></html>") { topic ->
            "<h3>${Json.escapeHtml(topic.title)}</h3><p>${Json.escapeHtml(topic.text)}</p>"
        }
        caretPosition = 0
    }

    private class RiskRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val risk = value as? ChangeRisk
            if (!isSelected && risk != null) foreground = colorOf(risk.ordinal)
            icon = risk?.let { DotIcon(colorOf(it.ordinal)) }
            iconTextGap = JBUI.scale(6)
            return this
        }
    }

    private class TestsRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val tests = value as? TestUsage
            val color = tests?.coverage?.takeIf { it != TestCoverage.TEST_CLASS }?.let { colorOf(TestCoverage.GOOD.ordinal - it.ordinal) }
            if (!isSelected && color != null) foreground = color
            icon = if (tests == null) null else DotIcon(color ?: UIUtil.getContextHelpForeground())
            iconTextGap = JBUI.scale(6)
            toolTipText = tests?.testClasses?.takeIf { it.isNotEmpty() }
                ?.joinToString("<br>", "<html>Classes de teste que a usam:<br>", "</html>") { DependencyAnalyzer.simpleName(it) }
            return this
        }
    }

    private class DotIcon(private val color: Color) : Icon {
        override fun getIconWidth() = JBUI.scale(9)
        override fun getIconHeight() = JBUI.scale(9)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x, y, iconWidth, iconHeight)
            } finally {
                g2.dispose()
            }
        }
    }

    private class ChangeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected && value != null) foreground = FileStatus.MODIFIED.color ?: table.foreground
            return this
        }
    }

    private class ClassTableModel : AbstractTableModel() {
        private val columns = listOf(
            "Classe" to "Nome da classe",
            "Pacote" to "Pacote onde a classe está",
            "Aferentes (dependem dela)" to MetricGuide.afferent.text,
            "Eferentes (ela depende de)" to MetricGuide.efferent.text,
            "Risco ao alterar" to MetricGuide.risk.text,
            "Testes" to MetricGuide.tests.text,
            "Alteração" to MetricGuide.changes.text,
        )
        private var rows: List<ClassNode> = emptyList()
        private var changes: Map<String, FileChange> = emptyMap()

        fun tooltip(column: Int) = "<html><body style='width: 320px'>${Json.escapeHtml(columns[column].second)}</body></html>"

        fun update(nodes: List<ClassNode>, changes: Map<String, FileChange>) {
            rows = nodes
            this.changes = changes
            fireTableDataChanged()
        }

        /** Mantém a seleção, ao contrário de [update]. */
        fun updateChanges(changes: Map<String, FileChange>) {
            this.changes = changes
            if (rows.isNotEmpty()) fireTableRowsUpdated(0, rows.lastIndex)
        }

        fun indexOf(qualifiedName: String) = rows.indexOfFirst { it.qualifiedName == qualifiedName }

        fun nameAt(row: Int) = rows[row].qualifiedName

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column].first
        override fun getColumnClass(column: Int): Class<*> = when (column) {
            2, 3 -> Int::class.javaObjectType
            4 -> ChangeRisk::class.java
            5 -> TestUsage::class.java
            6 -> FileChange::class.java
            else -> String::class.java
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val node = rows[row]
            return when (column) {
                0 -> node.name
                1 -> node.packageName.ifEmpty { "(padrão)" }
                2 -> node.afferent
                3 -> node.efferent
                4 -> node.risk
                5 -> node.tests
                else -> changes[node.qualifiedName]
            }
        }
    }

    private companion object {
        const val DIAGRAM_CLASSES_TAB = 0
        const val ALL_CLASSES_TAB = 1
        const val HELP_TAB = 2
        val TAB_TITLES = listOf("Classes no raio", "Todas as classes", "Manual de campo")
        const val BOTTOM_HIDDEN_KEY = "irondome.dependencies.bottomHidden"
        const val GIT_TIMEOUT_MS = 15_000

        /** 0 é verde (tranquilo), 1 amarelo e 2 vermelho (pede cuidado). */
        fun colorOf(level: Int): Color = when (level) {
            0 -> JBColor(0x1a7f37, 0x3fb950)
            1 -> JBColor(0x9a6700, 0xd29922)
            else -> JBColor(0xcf222e, 0xf85149)
        }
    }
}
