package com.mio.irondome.escudo

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestSearchScope
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.ui.ColorUtil
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.FireWithAiAction
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Aba Escudo: executa todos os testes JUnit do projeto pela configuração "All in project" da IDE e
 * mostra o resultado com as classes de teste agrupadas pela chave do `@DisplayName`
 * (`[Chave] Descrição`), avisando quais classes estão fora dessa convenção. A execução também aparece
 * na janela Run, com o console completo.
 */
class TestsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0
        cellRenderer = Renderer { grouping != TestGrouping.TAG }
        emptyText.text = IDLE
    }
    private val header = SummaryHeader(IronDomeIcons.escudo)
    private val details = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        border = JBUI.Borders.empty(10, 14)
    }
    private val displayNames = JUnitDisplayNames(project)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var environment: ExecutionEnvironment? = null
    @Volatile
    private var testsRoot: SMTestProxy.SMRootTestProxy? = null
    @Volatile
    private var process: ProcessHandler? = null
    @Volatile
    private var notStarted = false
    private var results: List<TestNode> = emptyList()
    private var onlyFailures = false
    /** Nós com falha já abertos sozinhos uma vez: se o usuário os recolher, ficam recolhidos. */
    private val autoExpanded = mutableSetOf<String>()

    private val running: Boolean
        get() = environment != null && !notStarted && process?.isProcessTerminated != true

    init {
        val expander = DefaultTreeExpander(tree)
        val openAction = object : DumbAwareAction("Ir para o Código", "Abre a classe ou o método de teste selecionado", AllIcons.Actions.EditSource) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedItem()?.locationUrl != null
            }
            override fun actionPerformed(e: AnActionEvent) = open(selectedItem())
        }
        val explainAction = object : DumbAwareAction(
            "Explicar Falha com IA",
            "A IA configurada explica por que os testes selecionados falharam e propõe a correção, sem alterar arquivos",
            IronDomeIcons.ia,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val runner = AgentRunner.getInstance(project)
                e.presentation.isEnabled = selectedItem()?.outcome == TestOutcome.FAILED && runner.running == null && runner.available()
                e.presentation.description = if (runner.available()) {
                    "A IA configurada explica por que os testes selecionados falharam e propõe a correção, sem alterar arquivos"
                } else {
                    FireWithAiAction.NOT_READY
                }
            }
            override fun actionPerformed(e: AnActionEvent) {
                selectedItem()?.let { FailureExplanation.explain(project, it) }
            }
        }
        val conventionAction = object : DumbAwareAction(
            "Deixar no Padrão",
            "Marca no Radar, como alvos do Escudo, as classes de teste fora da convenção do @DisplayName, para a IA corrigir ao disparar",
            AllIcons.Actions.QuickfixBulb,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val outside = outsideConvention()
                e.presentation.isEnabled = outside.isNotEmpty() && !running
                e.presentation.text = if (outside.size > 1) "Deixar no Padrão (${outside.size})" else "Deixar no Padrão"
            }
            override fun actionPerformed(e: AnActionEvent) {
                ConventionTargets.mark(project, outsideConvention(), results.mapNotNull { it.classKey }.distinct().sorted())
            }
        }
        toolbar = ActionManager.getInstance().createActionToolbar(
            "IronDome.TestsToolbar",
            DefaultActionGroup(
                object : DumbAwareAction(
                    "Executar Todos os Testes",
                    "Compila o projeto e executa todos os testes JUnit, agrupados pela chave do @DisplayName das classes",
                    AllIcons.Actions.Execute,
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = !running
                    }
                    override fun actionPerformed(e: AnActionEvent) = runAll()
                },
                object : DumbAwareAction("Parar", "Interrompe a execução dos testes", AllIcons.Actions.Suspend) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = running && process != null
                    }
                    override fun actionPerformed(e: AnActionEvent) {
                        process?.destroyProcess()
                    }
                },
                explainAction,
                conventionAction,
                Separator.getInstance(),
                object : DumbAwareToggleAction("Mostrar Só as Falhas", "Esconde as classes e os testes que passaram", AllIcons.General.Filter) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = onlyFailures
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        onlyFailures = state
                        rebuildTree()
                    }
                },
                groupingActions(),
                CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
                CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
                Separator.getInstance(),
                openAction,
            ),
            true,
        ).apply { targetComponent = tree }.component

        val splitter = OnePixelSplitter(true, "IronDome.TestsSplitter", 0.65f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = ScrollPaneFactory.createScrollPane(details, true)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        installNavigation(openAction, explainAction)
        tree.addTreeSelectionListener { updateDetails() }

        val connection = project.messageBus.connect(this)
        connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
                if (this@TestsPanel.testsRoot == null && running) this@TestsPanel.testsRoot = testsRoot
                onEvent(testsRoot)
            }
            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) = onEvent(testsRoot)
            override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) = onEvent(testProxy)
            override fun onSuiteStarted(suite: SMTestProxy) = onEvent(suite)
            override fun onSuiteFinished(suite: SMTestProxy) = onEvent(suite)
            override fun onTestStarted(test: SMTestProxy) = onEvent(test)
            override fun onTestFinished(test: SMTestProxy) = onEvent(test)
            override fun onTestFailed(test: SMTestProxy) = onEvent(test)
            override fun onTestIgnored(test: SMTestProxy) = onEvent(test)
        })
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (env !== environment) return
                notStarted = true
                scheduleRefresh()
            }
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (env !== environment) return
                process = handler
                scheduleRefresh()
            }
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                if (env === environment) scheduleRefresh()
            }
        })
        refresh()
    }

    override fun dispose() = Unit

    private fun runAll() {
        val configuration = JUnitConfiguration(RUN_NAME, project).apply {
            persistentData.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
            persistentData.PACKAGE_NAME = ""
            persistentData.scope = TestSearchScope.WHOLE_PROJECT
        }
        val settings = RunManager.getInstance(project)
            .createConfiguration(configuration, JUnitConfigurationType.getInstance().factory)
            .apply { isActivateToolWindowBeforeRun = false }
        val builder = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)
        if (builder == null) {
            header.update("Não foi possível executar", Tone.RED.accent, "A IDE não encontrou como executar testes JUnit neste projeto.")
            return
        }
        val env = builder.build(ProgramRunner.Callback { attach(it) })
        environment = env
        testsRoot = null
        process = null
        notStarted = false
        displayNames.clear()
        autoExpanded.clear()
        refresh()
        ProgramRunnerUtil.executeConfiguration(env, false, true)
    }

    private fun attach(descriptor: RunContentDescriptor?) {
        (descriptor?.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode?.let { testsRoot = it }
        descriptor?.processHandler?.let { process = it }
        scheduleRefresh()
    }

    private fun onEvent(proxy: SMTestProxy) {
        val root = testsRoot ?: return
        if ((proxy as? SMTestProxy.SMRootTestProxy ?: proxy.root) === root) scheduleRefresh()
    }

    /** Os eventos chegam a cada teste: a árvore é redesenhada no máximo a cada [REFRESH_DELAY_MS]. */
    private fun scheduleRefresh() {
        if (refreshAlarm.activeRequestCount > 0) return
        refreshAlarm.addRequest({ refresh() }, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        results = testsRoot?.let { TestResults.build(it, displayNames::of, displayNames::tagsOf) }.orEmpty()
        updateHeader()
        rebuildTree()
    }

    /** O escolhido; sem escolha, por tag quando os testes têm `@Tag`. */
    private val grouping: TestGrouping
        get() = TestGrouping.entries.firstOrNull { it.name == PropertiesComponent.getInstance().getValue(GROUPING_KEY) }
            ?: if (results.any { group -> group.tests.any { it.tags.isNotEmpty() } }) TestGrouping.TAG else TestGrouping.KEY

    /** A árvore no agrupamento escolhido; [results] continua por chave, para o cabeçalho e as contas. */
    private fun view(): List<TestNode> = if (grouping == TestGrouping.TAG) TestResults.byTag(results) else results

    private fun groupingActions() = DefaultActionGroup("Agrupar Por", true).apply {
        templatePresentation.icon = AllIcons.Actions.GroupBy
        TestGrouping.entries.forEach { option ->
            add(object : DumbAwareToggleAction(option.label) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = grouping == option
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    PropertiesComponent.getInstance().setValue(GROUPING_KEY, option.name)
                    rebuildTree()
                }
            })
        }
    }

    private fun updateHeader() {
        val tests = results.flatMap { it.tests }
        val classes = results.flatMap { it.classes }
        val failed = tests.count { it.outcome == TestOutcome.FAILED }
        val keys = results.count { it.classKey != null }
        val context = listOfNotNull(
            plural(classes.size, "classe", "classes"),
            plural(keys, "chave", "chaves").takeIf { keys > 0 },
            testsRoot?.duration?.let(TestResults::formatDuration),
        ).joinToString(" · ")
        val outside = classes.count { it.convention != Convention.OK }
        val warning = if (outside == 0) null else
            "${plural(outside, "classe de teste fora", "classes de teste fora")} da convenção: coloque " +
                "${TestResults.CONVENTION_EXAMPLE} na classe. A chave agrupa os testes. " +
                "Deixar no Padrão marca as classes no Radar para a IA corrigir."
        val stats = statsOf(tests)
        val bar = barOf(tests)
        when {
            environment == null -> header.update("Escudo pronto", subtitle = IDLE)
            notStarted -> header.update(
                "Os testes não chegaram a rodar",
                Tone.RED.accent,
                "Confira os erros de compilação ou de configuração nas janelas Build e Run.",
            )
            running && tests.isEmpty() -> header.update("Preparando os testes…", subtitle = "Compilando o projeto e procurando os testes JUnit")
            running -> {
                val done = tests.count { it.outcome != TestOutcome.RUNNING && it.outcome != TestOutcome.NOT_RUN }
                header.update("Executando os testes…", Tone.BLUE.accent, "$done de ${tests.size} testes · $context", stats, bar, warning)
            }
            tests.isEmpty() -> header.update("Nenhum teste JUnit executado", Tone.YELLOW.accent, "Veja o console na janela Run.")
            failed > 0 -> header.update(
                "Escudo rompido",
                Tone.RED.accent,
                "${plural(failed, "teste falhou", "testes falharam")} de ${tests.size} · $context",
                stats, bar, warning,
            )
            tests.any { it.outcome == TestOutcome.INTERRUPTED } ->
                header.update("Execução interrompida", Tone.ORANGE.accent, "${tests.size} testes · $context", stats, bar, warning)
            else -> header.update(
                "Escudo íntegro",
                Tone.GREEN.accent,
                "${if (tests.size == 1) "O teste passou" else "Todos os ${tests.size} testes passaram"} · $context",
                stats, bar, warning,
            )
        }
    }

    private fun rebuildTree() {
        val expanded = tree.getExpandedDescendants(TreePath(model.root))?.toList().orEmpty()
            .mapNotNull { itemOf(it.lastPathComponent)?.key }
            .toSet()
        val selected = selectedItem()?.key

        val root = DefaultMutableTreeNode()
        visible(view()).forEach { root.add(treeNode(it)) }
        model.setRoot(root)
        tree.emptyText.text = when {
            environment == null -> IDLE
            onlyFailures && results.isNotEmpty() -> "Nenhuma falha"
            running -> "Aguardando os testes…"
            else -> "Nenhum teste executado"
        }

        var selection: TreePath? = null
        for (node in root.preorderEnumeration().toList().drop(1).map { it as DefaultMutableTreeNode }) {
            val item = node.userObject as TestNode
            val path = TreePath(node.path)
            if (item.key == selected) selection = path
            if (item.kind == TestKind.TEST) continue
            val opensAlone = item.kind == TestKind.TAG || item.kind == TestKind.KEY || item.outcome == TestOutcome.FAILED
            if (item.key in expanded || (opensAlone && autoExpanded.add(item.key))) tree.expandPath(path)
        }
        selection?.let { tree.selectionPath = it }
        updateDetails()
    }

    /** As classes fora da convenção da seleção; sem nenhuma nela, as de toda a execução. */
    private fun outsideConvention(): List<TestNode> {
        // Por tag, a mesma classe aparece em mais de um grupo.
        fun outside(nodes: List<TestNode>) =
            nodes.flatMap { it.classes }.filter { it.convention != Convention.OK }.distinctBy { it.locationUrl ?: it.key }
        return outside(listOfNotNull(selectedItem())).ifEmpty { outside(results) }
    }

    private fun visible(nodes: List<TestNode>): List<TestNode> =
        if (!onlyFailures) nodes
        else nodes.filter { it.outcome == TestOutcome.FAILED || it.outcome == TestOutcome.INTERRUPTED }

    private fun treeNode(item: TestNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(item).apply { visible(item.children).forEach { add(treeNode(it)) } }

    private fun updateDetails() {
        val item = selectedItem()
        details.text = html(if (item == null) gray(DETAILS_HINT) else detailsOf(item))
        details.caretPosition = 0
    }

    private fun detailsOf(item: TestNode): String = buildString {
        val group = item.kind == TestKind.KEY || item.kind == TestKind.TAG
        val name = if (group) null else DisplayNameFormat.parse(item.label)
        val title = name?.let(::nameHtml) ?: when (item.kind) {
            TestKind.TAG -> escape(item.tags.firstOrNull()?.let { "@$it" } ?: TestResults.NO_TAG)
            else -> escape(item.classKey?.let { "[$it]" } ?: TestResults.NO_KEY)
        }
        append("<h2 style='margin: 0 0 6px 0'>$title</h2>")
        append("<p>${pill("${symbolOf(item.outcome)} ${item.outcome.label}", toneOf(item.outcome))}")
        TestResults.formatDuration(item.durationMillis)?.let { append("&nbsp;&nbsp;${gray(it)}") }
        item.convention?.problem?.let { append("&nbsp;&nbsp;${pill("⚠ $it", Tone.YELLOW)}") }
        if (item.kind != TestKind.TAG) item.tags.forEach { append("&nbsp;&nbsp;${pill("@$it", tagTone(it))}") }
        append("</p>")
        item.codeReference?.let { append("<p>${gray("<code>${escape(it)}</code>")}</p>") }
        name?.takeIf { it.structured }?.let { appendScenario(it) }
        if (item.kind == TestKind.TEST) {
            item.errorMessage?.let { append(section("Motivo")).append("<p>${escape(it).replace("\n", "<br>")}</p>") }
            item.stacktrace?.let { trace ->
                val lines = trace.lines()
                val shown = lines.take(STACKTRACE_LINES).joinToString("\n") + if (lines.size > STACKTRACE_LINES) "\n…" else ""
                append(section("Pilha")).append(code(shown))
            }
            return@buildString
        }
        append("<p>${countsOf(item)}</p>")
        appendConvention(item)
        val failures = item.tests.filter { it.outcome == TestOutcome.FAILED }
        if (failures.isNotEmpty()) {
            append(section("Falhas")).append("<ul>")
            failures.forEach { failure ->
                append("<li>${color(Tone.RED.accent, "<b>${escape(failure.label)}</b>")}")
                failure.errorMessage?.lineSequence()?.firstOrNull()?.let { append("<br>${gray(escape(it))}") }
                append("</li>")
            }
            append("</ul>")
        }
    }

    /** As etapas dado/quando/então numa tabela e os parâmetros da execução, cada um com o seu valor. */
    private fun StringBuilder.appendScenario(name: DisplayNameParts) {
        if (name.steps.any { it.role != null }) {
            append(section("Cenário")).append("<table cellpadding='3' cellspacing='0'>")
            for (step in name.steps) {
                val label = step.role?.let { pill(it.label.uppercase(), toneOf(it)) }.orEmpty()
                append("<tr><td valign='top' nowrap>$label</td><td>${fragmentsHtml(step.fragments)}</td></tr>")
            }
            append("</table>")
        }
        if (name.parameters.isNotEmpty()) {
            append(section("Parâmetros desta execução")).append("<table cellpadding='3' cellspacing='0'>")
            name.parameters.forEachIndexed { i, parameter ->
                val label = parameter.name ?: "argumento ${i + 1}"
                append("<tr><td valign='top' nowrap>${gray("<b>${escape(label)}</b>")}</td><td>${codeSpan(parameter.value, Tone.PURPLE)}</td></tr>")
            }
            append("</table>")
        }
    }

    private fun countsOf(item: TestNode): String {
        val parts = statsOf(item.tests).filter { it.count > 0 }.map { pill("${it.count} ${it.label}", it.tone) }.toMutableList()
        if (item.kind == TestKind.KEY || item.kind == TestKind.TAG) parts += gray(plural(item.classes.size, "classe", "classes"))
        return parts.joinToString("&nbsp;&nbsp;")
    }

    /** Classes fora da convenção, com o que falta e o `@DisplayName` sugerido para cada uma. */
    private fun StringBuilder.appendConvention(item: TestNode) {
        val outside = item.classes.filter { it.convention != Convention.OK }
        if (outside.isEmpty()) return
        append(section("⚠ Fora da convenção"))
        append(
            "<p>Coloque na classe de teste um <code>@DisplayName</code> com a chave entre colchetes no início. " +
                "O Escudo agrupa as classes pela chave, e as do mesmo assunto ficam juntas:</p>",
        )
        append(code("@DisplayName(\"[Frete] Cálculo do frete\")\nclass FreteServiceTest { … }"))
        append("<ul>")
        for (testClass in outside) {
            val name = testClass.codeReference?.substringAfterLast('.')?.substringAfterLast('$') ?: testClass.label
            val description = if (testClass.convention == Convention.MISSING_KEY) testClass.label else "Descrição"
            append("<li><b>${escape(name)}</b>&nbsp;&nbsp;${pill(testClass.convention?.problem.orEmpty(), Tone.YELLOW)}<br>")
            append("<code>${escape("@DisplayName(\"[Chave] $description\")")}</code></li>")
        }
        append("</ul>")
        append(
            "<p>${gray("<b>Deixar no Padrão</b>, na barra, marca estas classes como alvos no Radar, assinalados como do Escudo. " +
                "Ao disparar a ordem de interceptação, a IA ajusta só o <code>@DisplayName</code>.")}</p>",
        )
    }

    private fun open(item: TestNode?) {
        val url = item?.locationUrl ?: return
        val target = ReadAction.compute<Navigatable?, RuntimeException> {
            JUnitDisplayNames.resolve(project, url)?.navigationElement as? Navigatable
        }
        target?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun installNavigation(openAction: DumbAwareAction, explainAction: DumbAwareAction) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val item = itemOf(tree.getPathForLocation(e.x, e.y)?.lastPathComponent) ?: return
                if (item.kind == TestKind.TEST) open(item)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val item = selectedItem() ?: return
                if (item.kind == TestKind.TEST) {
                    open(item)
                } else {
                    val path = tree.selectionPath ?: return
                    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                }
            }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(openAction, explainAction), "IronDome.TestsPopup")
    }

    private fun itemOf(value: Any?) = (value as? DefaultMutableTreeNode)?.userObject as? TestNode

    private fun selectedItem() = itemOf(tree.lastSelectedPathComponent)

    /** [showTags]: as tags de cada teste aparecem fora do agrupamento por tag, onde já estão no grupo. */
    private class Renderer(private val showTags: () -> Boolean) : RichTreeCellRenderer() {

        override fun describe(row: Row, value: Any?) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? TestNode ?: return
            val failed = item.outcome == TestOutcome.FAILED
            when (item.kind) {
                TestKind.TAG -> {
                    val tag = item.tags.firstOrNull()
                    if (tag == null) {
                        row.icon(AllIcons.General.Information)
                        row.pill(TestResults.NO_TAG, Tone.GRAY, bold = true)
                    } else {
                        row.icon(iconOf(item.outcome))
                        row.pill("@$tag", tagTone(tag), bold = true)
                    }
                    row.text(
                        "${plural(item.classes.size, "classe", "classes")} · ${plural(item.tests.size, "teste", "testes")}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                    progress(row, item)
                }
                TestKind.KEY -> {
                    val key = item.classKey
                    if (key == null) {
                        row.icon(AllIcons.General.Warning)
                        row.pill(TestResults.NO_KEY, Tone.YELLOW, bold = true)
                    } else {
                        row.icon(iconOf(item.outcome))
                        row.pill(key, Tone.forKey(key), bold = true)
                    }
                    row.text(plural(item.classes.size, "classe", "classes"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    progress(row, item)
                }
                TestKind.CLASS -> {
                    row.icon(iconOf(item.outcome))
                    name(row, item.label, if (failed) FAILED_BOLD else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    item.codeName?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    item.convention?.problem?.let { row.pill("⚠ $it", Tone.YELLOW) }
                    progress(row, item)
                }
                TestKind.GROUP -> {
                    row.icon(iconOf(item.outcome))
                    name(row, item.label, if (failed) FAILED_PLAIN else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    progress(row, item)
                }
                TestKind.TEST -> {
                    row.icon(iconOf(item.outcome))
                    name(row, item.label, if (failed) FAILED_PLAIN else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    item.codeName?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    if (showTags()) item.tags.forEach { row.pill("@$it", tagTone(it)) }
                    if (item.outcome == TestOutcome.IGNORED) row.pill("ignorado", Tone.YELLOW)
                    TestResults.formatDuration(item.durationMillis)?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    item.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { row.detail(it.trim().take(ERROR_PREVIEW)) }
                }
            }
            row.tooltip(item.errorMessage?.lineSequence()?.firstOrNull() ?: item.codeReference)
        }

        /**
         * O nome com as partes destacadas: número da execução, chave, etiquetas das etapas dado/quando/então e,
         * no texto, verbo de expectativa, código, números, exceções e o resultado depois da seta.
         */
        private fun name(row: Row, label: String, base: SimpleTextAttributes) {
            val name = DisplayNameFormat.parse(label)
            name.index?.let { row.pill("#$it", Tone.GRAY, bold = true) }
            name.key?.let { row.pill(it, Tone.forKey(it), bold = true) }
            for (step in name.steps) {
                step.role?.let { row.pill(step.keyword.orEmpty().uppercase(), toneOf(it), bold = true) }
                row.styled(step.fragments.map { fragmentText(it) to attributesOf(it.style, base) })
            }
            row.styled(
                name.parameters.flatMapIndexed { i, parameter ->
                    listOfNotNull(
                        ("  ·  " to SimpleTextAttributes.GRAYED_ATTRIBUTES).takeIf { i > 0 },
                        parameter.name?.let { "$it = " to SimpleTextAttributes.GRAYED_ATTRIBUTES },
                        padded(parameter.value) to attributesOf(FragmentStyle.CODE, base),
                    )
                },
            )
        }

        /** Barra com a proporção de cada resultado, quantos passaram e os destaques de falhas e ignorados. */
        private fun progress(row: Row, item: TestNode) {
            val tests = item.tests
            if (tests.isEmpty()) return
            val passed = item.count(TestOutcome.PASSED)
            val failed = item.count(TestOutcome.FAILED)
            val ignored = item.count(TestOutcome.IGNORED)
            row.bar(barOf(tests))
            row.text("$passed/${tests.size}", if (failed > 0) FAILED_PLAIN else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (failed > 0) row.pill("✖ $failed", Tone.RED, bold = true)
            if (ignored > 0) row.pill("⊘ $ignored", Tone.YELLOW)
            TestResults.formatDuration(item.durationMillis)?.let { row.text(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }

        private companion object {
            const val ERROR_PREVIEW = 140
            val FAILED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Tone.RED.accent)
            val FAILED_PLAIN = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Tone.RED.accent)
        }
    }

    private companion object {
        const val RUN_NAME = "Iron Dome IA: todos os testes"
        const val REFRESH_DELAY_MS = 200
        const val STACKTRACE_LINES = 40
        const val IDLE = "Clique em Executar Todos os Testes para pôr o escudo à prova."
        const val GROUPING_KEY = "IronDome.Escudo.Grouping"
        const val DETAILS_HINT = "Selecione uma chave, uma classe ou um teste para ver os detalhes. Duplo clique num teste abre o código."

        fun statsOf(tests: List<TestNode>): List<Stat> {
            fun count(outcome: TestOutcome) = tests.count { it.outcome == outcome }
            val pending = count(TestOutcome.RUNNING) + count(TestOutcome.NOT_RUN)
            return listOf(
                Stat(count(TestOutcome.PASSED), if (count(TestOutcome.PASSED) == 1) "passou" else "passaram", Tone.GREEN),
                Stat(count(TestOutcome.FAILED), if (count(TestOutcome.FAILED) == 1) "falhou" else "falharam", Tone.RED),
                Stat(count(TestOutcome.IGNORED), if (count(TestOutcome.IGNORED) == 1) "ignorado" else "ignorados", Tone.YELLOW),
                Stat(count(TestOutcome.INTERRUPTED), if (count(TestOutcome.INTERRUPTED) == 1) "interrompido" else "interrompidos", Tone.ORANGE),
                Stat(pending, "na fila", Tone.GRAY),
            )
        }

        fun barOf(tests: List<TestNode>): List<Segment> {
            fun count(outcome: TestOutcome) = tests.count { it.outcome == outcome }
            return listOf(
                Segment(count(TestOutcome.PASSED), Tone.GREEN.accent),
                Segment(count(TestOutcome.FAILED), Tone.RED.accent),
                Segment(count(TestOutcome.IGNORED), Tone.YELLOW.accent),
                Segment(count(TestOutcome.INTERRUPTED), Tone.ORANGE.accent),
                Segment(count(TestOutcome.RUNNING) + count(TestOutcome.NOT_RUN), Tone.GRAY.background),
            )
        }

        fun iconOf(outcome: TestOutcome): Icon = when (outcome) {
            TestOutcome.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            TestOutcome.FAILED -> AllIcons.RunConfigurations.TestFailed
            TestOutcome.INTERRUPTED -> AllIcons.RunConfigurations.TestTerminated
            TestOutcome.IGNORED -> AllIcons.RunConfigurations.TestIgnored
            TestOutcome.PASSED -> AllIcons.RunConfigurations.TestPassed
            TestOutcome.NOT_RUN -> AllIcons.RunConfigurations.TestNotRan
        }

        /** A mesma cor para a mesma tag, separada das cores das chaves. */
        fun tagTone(tag: String): Tone = Tone.forKey("@$tag")

        fun toneOf(role: StepRole): Tone = when (role) {
            StepRole.GIVEN -> Tone.BLUE
            StepRole.WHEN -> Tone.PURPLE
            StepRole.THEN -> Tone.TEAL
        }

        fun attributesOf(style: FragmentStyle, base: SimpleTextAttributes): SimpleTextAttributes {
            val bold = base.style or SimpleTextAttributes.STYLE_BOLD
            return when (style) {
                FragmentStyle.TEXT -> base
                FragmentStyle.VERB -> base.derive(bold, null, null, null)
                FragmentStyle.NEGATION -> SimpleTextAttributes(bold, Tone.ORANGE.foreground)
                FragmentStyle.CODE -> SimpleTextAttributes(Tone.PURPLE.background, Tone.PURPLE.foreground, null, base.style or SimpleTextAttributes.STYLE_OPAQUE)
                FragmentStyle.ERROR -> SimpleTextAttributes(Tone.RED.background, Tone.RED.foreground, null, base.style or SimpleTextAttributes.STYLE_OPAQUE)
                FragmentStyle.NUMBER -> SimpleTextAttributes(bold, Tone.BLUE.foreground)
                FragmentStyle.ARROW -> SimpleTextAttributes(bold, Tone.GRAY.accent)
                FragmentStyle.RESULT -> SimpleTextAttributes(bold, Tone.GREEN.foreground)
            }
        }

        /** Código e exceções ganham um respiro dentro do fundo colorido. */
        fun fragmentText(fragment: Fragment) =
            if (fragment.style == FragmentStyle.CODE || fragment.style == FragmentStyle.ERROR) padded(fragment.text) else fragment.text

        fun padded(text: String) = "\u2009$text\u2009"

        fun nameHtml(name: DisplayNameParts): String = buildString {
            name.index?.let { append(pill("#$it", Tone.GRAY)).append("&nbsp;") }
            name.key?.let { append(pill(it, Tone.forKey(it))).append("&nbsp;") }
            name.steps.forEachIndexed { i, step ->
                if (i > 0) append(" ")
                step.role?.let { append(pill(step.keyword.orEmpty().uppercase(), toneOf(it))).append("&nbsp;") }
                append(fragmentsHtml(step.fragments))
            }
            name.parameters.forEachIndexed { i, parameter ->
                if (i > 0) append(gray(" · "))
                parameter.name?.let { append(gray("${escape(it)} = ")) }
                append(codeSpan(parameter.value, Tone.PURPLE))
            }
        }

        fun fragmentsHtml(fragments: List<Fragment>): String = fragments.joinToString("") { fragment ->
            val text = escape(fragment.text)
            when (fragment.style) {
                FragmentStyle.TEXT -> text
                FragmentStyle.VERB -> "<b>$text</b>"
                FragmentStyle.NEGATION -> color(Tone.ORANGE.foreground, "<b>$text</b>")
                FragmentStyle.CODE -> codeSpan(fragment.text, Tone.PURPLE)
                FragmentStyle.ERROR -> codeSpan(fragment.text, Tone.RED)
                FragmentStyle.NUMBER -> color(Tone.BLUE.foreground, "<b>$text</b>")
                FragmentStyle.ARROW -> color(Tone.GRAY.accent, "<b>&nbsp;→&nbsp;</b>")
                FragmentStyle.RESULT -> color(Tone.GREEN.foreground, "<b>$text</b>")
            }
        }

        fun codeSpan(text: String, tone: Tone) =
            "<span style='background-color:${hex(tone.background)}; color:${hex(tone.foreground)}; font-family: monospace'>&nbsp;${escape(text)}&nbsp;</span>"

        fun toneOf(outcome: TestOutcome): Tone = when (outcome) {
            TestOutcome.PASSED -> Tone.GREEN
            TestOutcome.FAILED -> Tone.RED
            TestOutcome.IGNORED -> Tone.YELLOW
            TestOutcome.INTERRUPTED -> Tone.ORANGE
            TestOutcome.RUNNING -> Tone.BLUE
            TestOutcome.NOT_RUN -> Tone.GRAY
        }

        fun symbolOf(outcome: TestOutcome) = when (outcome) {
            TestOutcome.PASSED -> "✔"
            TestOutcome.FAILED -> "✖"
            TestOutcome.IGNORED -> "⊘"
            TestOutcome.INTERRUPTED -> "■"
            else -> "…"
        }

        fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

        fun hex(color: Color) = "#${ColorUtil.toHex(color)}"

        fun color(color: Color, html: String) = "<span style='color:${hex(color)}'>$html</span>"

        fun gray(html: String) = color(UIUtil.getContextHelpForeground(), html)

        fun pill(text: String, tone: Tone) =
            "<span style='background-color:${hex(tone.background)}; color:${hex(tone.foreground)}'>&nbsp;<b>${escape(text)}</b>&nbsp;</span>"

        fun section(title: String) = "<p style='margin-top: 10px'><b>${escape(title)}</b></p>"

        fun code(text: String) =
            "<pre style='background-color:${hex(Tone.GRAY.background)}; padding: 6px; margin: 4px 0'>${escape(text)}</pre>"

        fun html(body: String) = "<html><body>$body</body></html>"
    }
}
