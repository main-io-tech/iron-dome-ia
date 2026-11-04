package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
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
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.base.refreshAction
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import com.mio.irondome.radar.CommentStatus
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewCommentsListener
import com.mio.irondome.radar.TargetComments
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.AuthorizationType
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.GHLoginSource
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Aba Patrulha: os PRs abertos no GitHub, os comentários do modo revisão ainda não enviados a cada um e os
 * pedidos de alteração já enviados, com o que as verificações dizem sobre eles.
 */
class PatrulhaPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class PrItem(
        val number: Int,
        val title: String,
        val pr: PullRequest?,
        val record: PullRequestRecord?,
        val drafts: List<ReviewComment>,
        val active: Boolean,
        val checkedOut: Boolean,
        val historyShown: Boolean,
    )

    private class Section(val title: String, val icon: Icon, val count: Int)

    private class DraftItem(val number: Int, val comment: ReviewComment)

    private class RequestItem(val number: Int, val request: ChangeRequest, val inEditor: Boolean)

    /** A pasta dos arquivos alterados do PR: [files] nulo enquanto carrega ou se deu [error]. */
    private class FilesItem(val number: Int, val files: PullRequestFiles?, val error: String?)

    /** [comments] conta rascunhos e pedidos dos arquivos da pasta. */
    private class DirItem(val number: Int, val folder: FileFolder, val comments: Int)

    /** [comments] conta rascunhos e pedidos do arquivo. */
    private class FileItem(val number: Int, val file: ChangedFile, val comments: Int)

    private class LoadingItem(val text: String)

    private val patrulha = PatrulhaService.getInstance(project)
    private val radar = project.service<ReviewCommentService>()
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0
        cellRenderer = Renderer()
        emptyText.text = "Procurando o repositório no GitHub…"
    }
    private val header = SummaryHeader(IronDomeIcons.patrulha)

    private var context: RepoContext? = null
    private var openPrs: List<PullRequest> = emptyList()
    private var problem: String? = null
    private var needsToken = false
    private var loading = false
    private var rebuildQueued = false

    private val filesLoading = mutableSetOf<Int>()
    private val filesErrors = mutableMapOf<Int, String>()
    /** PRs com a pasta de arquivos aberta, para ela continuar aberta quando a árvore é refeita. */
    private val expandedFiles = mutableSetOf<Int>()
    /** Pastas dos arquivos alterados que o usuário fechou, como `número:caminho`; as outras abrem sozinhas. */
    private val collapsedDirs = mutableSetOf<String>()

    private companion object {
        /** Quantos arquivos o aviso do stash lista antes do "e mais". */
        const val STASH_PREVIEW = 8
    }

    init {
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        })
        val actions = DefaultActionGroup(
            action(
                "Revisar Este PR",
                "Faz o checkout do PR, se precisar (com o que não está commitado no stash), e entra no modo revisão do PR: os comentários feitos no editor vão para o PR, não para a IA",
                IronDomeIcons.patrulha,
                enabled = { selectedPr()?.pr != null }, text = {
                    val item = selectedPr()
                    when {
                        item?.active != true -> "Revisar Este PR"
                        item.checkedOut || context == null -> "Encerrar Revisão"
                        else -> "Atualizar ou Encerrar Revisão"
                    }
                }) { toggleReview() },
            action("Ver Alterações", "Abre o diff dos arquivos do PR, no arquivo selecionado ou no primeiro; as setas passam pelos demais", AllIcons.Actions.Diff,
                enabled = { selectedPr()?.pr != null && context != null }) {
                val pr = selectedPr()?.pr
                if (pr != null) {
                    val path = when (val item = selectedNode()?.userObject) {
                        is FileItem -> item.file.path
                        is DirItem -> item.folder.allFiles.firstOrNull()?.path
                        else -> null
                    }
                    showDiff(pr, path)
                }
            },
            action(
                "Revisar com IA",
                "A IA configurada lê os arquivos alterados do PR e propõe comentários, que entram como rascunhos para você revisar antes de enviar",
                IronDomeIcons.ia,
                enabled = {
                    val runner = AgentRunner.getInstance(project)
                    selectedPr()?.let { it.active && it.checkedOut } == true && runner.running == null && runner.available()
                },
            ) { reviewWithAi() },
            action("Enviar ao GitHub", "Envia os comentários não enviados deste PR como uma revisão", AllIcons.Actions.Upload,
                enabled = { selectedPr()?.let { it.pr != null && it.drafts.isNotEmpty() } == true }) { send() },
            action("Editar", "Edita o comentário selecionado: o rascunho no editor, ou o pedido já enviado, também no GitHub", AllIcons.Actions.Edit,
                enabled = { selectedNode()?.userObject.let { it is DraftItem || (it is RequestItem && !it.request.deleted) } }) { editSelected() },
            action("Excluir Pedido", "Tira o pedido do .md e, se você quiser, apaga o comentário no GitHub", AllIcons.General.Remove,
                enabled = { selectedNode()?.userObject is RequestItem }) { deleteSelected() },
            action(
                "Mostrar no Editor",
                "Mostra ou oculta no código os pedidos enviados: o histórico do PR inteiro, ou só o pedido selecionado",
                AllIcons.Actions.Show,
                enabled = { historyTarget() != null },
                text = { if (historyTarget()?.second == true) "Ocultar no Editor" else "Mostrar no Editor" },
            ) { toggleHistory() },
            Separator.getInstance(),
            action("Verificar Pedidos", "Confere no head atual do PR e nas conversas se os pedidos foram atendidos", AllIcons.Actions.Checked,
                enabled = { selectedPr()?.record?.requests?.isNotEmpty() == true && context != null }) { verify() },
            action("Pedir Parecer da IA", "A IA configurada avalia cada pedido e escreve o parecer no .md; sem o CLI, copia o pedido para colar na sua IA", IronDomeIcons.ia,
                enabled = { selectedPr()?.record != null }) { askAi() },
            action("Abrir .md dos Pedidos", "Abre iron-dome/pull-requests/pr-<número>.md", AllIcons.Actions.EditSource,
                enabled = { selectedPr()?.record != null }) { selectedPr()?.let { openRecord(it.number) } },
            action("Abrir no GitHub", "Abre o PR ou o comentário selecionado no navegador", AllIcons.General.Web,
                enabled = { selectedUrl() != null }) { selectedUrl()?.let(BrowserUtil::browse) },
            Separator.getInstance(),
            refreshAction { refresh() },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("IronDome.PatrulhaToolbar", actions, true)
            .apply { targetComponent = tree }.component
        PopupHandler.installPopupMenu(tree, actions, "IronDome.PatrulhaPopup")
        installNavigation()

        val connection = project.messageBus.connect(this)
        connection.subscribe(ReviewCommentsListener.TOPIC, ReviewCommentsListener { scheduleRebuild() })
        connection.subscribe(PatrulhaService.TOPIC, PatrulhaService.Listener { scheduleRebuild() })
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { scheduleRebuild() })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val changed = events.mapNotNull { event ->
                    event.path.replace('\\', '/').takeIf { "/${ReviewCommentService.REVIEW_DIR}/${PullRequestRecord.DIR}/" in it }
                        ?.substringAfterLast('/')?.let(PullRequestRecord::numberOf)
                }
                if (changed.isEmpty()) return
                ApplicationManager.getApplication().invokeLater({
                    changed.distinct().filter(patrulha::isHistoryShown).forEach(patrulha::historyChanged)
                }, project.disposed)
                scheduleRebuild()
            }
        })
        refresh()
    }

    override fun dispose() = Unit

    /** Relê o repositório e os PRs abertos no GitHub. */
    private fun refresh() {
        if (loading) return
        loading = true
        object : Task.Backgroundable(project, "Patrulha: lendo os pull requests", true) {
            private var found: RepoContext? = null
            private var prs: List<PullRequest> = emptyList()
            private var error: String? = null
            private var restricted = false

            override fun run(indicator: ProgressIndicator) {
                val context = patrulha.context() ?: return
                found = context
                if (context.account == null) return
                try {
                    prs = patrulha.client(context).openPullRequests(context.repo)
                } catch (e: GitHubException) {
                    error = e.message
                    restricted = e.oauthRestricted
                } catch (e: PatrulhaException) {
                    error = e.message
                } catch (e: java.io.IOException) {
                    error = "Sem conexão com o GitHub: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            override fun onFinished() {
                loading = false
            }

            override fun onSuccess() {
                context = found
                openPrs = prs
                problem = error
                needsToken = restricted
                filesErrors.clear()
                rebuild()
                if (error == null) syncQuietly(prs.map { it.number })
            }

            override fun onThrowable(error: Throwable) {
                problem = error.message ?: error.javaClass.simpleName
                needsToken = false
                rebuild()
            }
        }.queue()
    }

    private fun scheduleRebuild() {
        if (rebuildQueued) return
        rebuildQueued = true
        ApplicationManager.getApplication().invokeLater({
            rebuildQueued = false
            rebuild()
        }, project.disposed)
    }

    private fun items(): List<PrItem> {
        val records = patrulha.records().associateBy { it.number }
        val head = context?.repository?.currentRevision
        val numbers = (openPrs.map { it.number } + records.keys + listOfNotNull(radar.activePullRequest.takeIf { it != 0 })).distinct()
        return numbers.map { number ->
            val pr = openPrs.firstOrNull { it.number == number }
            val record = records[number]
            PrItem(
                number = number,
                title = pr?.title ?: record?.title ?: "PR #$number",
                pr = pr,
                record = record,
                drafts = radar.pullRequestComments(number),
                active = radar.activePullRequest == number,
                checkedOut = pr != null && pr.headSha == head,
                historyShown = patrulha.isHistoryShown(number),
            )
        }
    }

    private fun rebuild() {
        val selected = selectedPr()?.number
        val selectedKey = selectedNode()?.userObject?.let(::keyOf)
        val expanded = TreeUtil.collectExpandedUserObjects(tree).mapNotNull { (it as? PrItem)?.number }.toSet()
        val items = items()
        val root = DefaultMutableTreeNode()
        val open = items.filter { it.pr != null }
        val closed = items.filter { it.pr == null }
        fun addPr(parent: DefaultMutableTreeNode, item: PrItem) {
            val node = DefaultMutableTreeNode(item)
            item.pr?.let { pr -> node.add(filesNode(item, pr)) }
            item.drafts.forEach { node.add(DefaultMutableTreeNode(DraftItem(item.number, it))) }
            item.record?.requests?.forEach { node.add(DefaultMutableTreeNode(RequestItem(item.number, it, patrulha.isInEditor(item.number, it.id)))) }
            parent.add(node)
        }
        if (open.isNotEmpty()) {
            val section = DefaultMutableTreeNode(Section("Pull requests abertos", AllIcons.Vcs.Branch, open.size))
            open.forEach { addPr(section, it) }
            root.add(section)
        }
        if (closed.isNotEmpty()) {
            val section = DefaultMutableTreeNode(Section("Pedidos de PRs fechados ou fora da lista", AllIcons.Nodes.Folder, closed.size))
            closed.forEach { addPr(section, it) }
            root.add(section)
        }
        model.setRoot(root)
        TreeUtil.expand(tree, 1)
        val nodes = TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).toList()
        nodes.forEach { node ->
            val item = node.userObject as? PrItem ?: return@forEach
            if (item.number in expanded || item.active || item.number == selected) tree.expandPath(TreeUtil.getPathFromRoot(node))
        }
        nodes.forEach { node ->
            val item = node.userObject as? FilesItem ?: return@forEach
            if (item.number in expandedFiles) {
                tree.expandPath(TreeUtil.getPathFromRoot(node))
                expandFolders(node)
            }
        }
        val reselect = nodes.firstOrNull { selectedKey != null && keyOf(it.userObject) == selectedKey }
            ?: nodes.firstOrNull { (it.userObject as? PrItem)?.number == selected }
        reselect?.let { tree.selectionPath = TreeUtil.getPathFromRoot(it) }
        updateEmptyText()
        updateHeader(items)
        items.firstOrNull { it.active }?.pr?.let(::ensureFiles)
    }

    /** Para a seleção sobreviver à árvore refeita. */
    private fun keyOf(item: Any?): String? = when (item) {
        is PrItem -> "pr:${item.number}"
        is FilesItem -> "files:${item.number}"
        is DirItem -> "dir:${dirKey(item)}"
        is FileItem -> "file:${item.number}:${item.file.path}"
        is DraftItem -> "draft:${item.number}:${item.comment.id}"
        is RequestItem -> "request:${item.number}:${item.request.id}"
        else -> null
    }

    private fun filesNode(item: PrItem, pr: PullRequest): DefaultMutableTreeNode {
        val files = patrulha.cachedFiles(pr.number, pr.headSha)
        val node = DefaultMutableTreeNode(FilesItem(pr.number, files, filesErrors[pr.number]))
        if (files == null) {
            node.add(DefaultMutableTreeNode(LoadingItem(filesErrors[pr.number] ?: "Carregando os arquivos do GitHub…")))
            return node
        }
        val requests = item.record?.active.orEmpty()
        val comments = files.files.associate { file ->
            file.path to item.drafts.count { it.filePath == file.path } + requests.count { it.path == file.path }
        }
        fun add(parent: DefaultMutableTreeNode, folder: FileFolder) {
            folder.folders.forEach { sub ->
                val child = DefaultMutableTreeNode(DirItem(pr.number, sub, sub.allFiles.sumOf { comments[it.path] ?: 0 }))
                add(child, sub)
                parent.add(child)
            }
            folder.files.forEach { parent.add(DefaultMutableTreeNode(FileItem(pr.number, it, comments[it.path] ?: 0))) }
        }
        add(node, ChangedFileTree.build(files.files))
        return node
    }

    private fun dirKey(item: DirItem) = "${item.number}:${item.folder.path}"

    /** Abre as pastas abaixo de [node], menos as que o usuário fechou e o que está dentro delas. */
    private fun expandFolders(node: DefaultMutableTreeNode) {
        node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().forEach { child ->
            val item = child.userObject as? DirItem ?: return@forEach
            if (dirKey(item) in collapsedDirs) return@forEach
            tree.expandPath(TreeUtil.getPathFromRoot(child))
            expandFolders(child)
        }
    }

    /** Lê os arquivos do PR no GitHub em segundo plano, uma vez por head do PR. */
    private fun ensureFiles(pr: PullRequest) {
        val context = context?.takeIf { it.account != null } ?: return
        if (patrulha.cachedFiles(pr.number, pr.headSha) != null || pr.number in filesErrors || !filesLoading.add(pr.number)) return
        object : Task.Backgroundable(project, "Patrulha: lendo os arquivos do PR #${pr.number}", true) {
            override fun run(indicator: ProgressIndicator) {
                patrulha.files(context, pr)
            }

            override fun onSuccess() {
                filesErrors.remove(pr.number)
            }

            override fun onThrowable(error: Throwable) {
                filesErrors[pr.number] = "Não foi possível ler os arquivos: ${error.message ?: error.javaClass.simpleName}"
            }

            override fun onFinished() {
                filesLoading.remove(pr.number)
                rebuild()
            }
        }.queue()
    }

    /** Abre o diff do PR no arquivo [path], ou no primeiro, lendo os arquivos antes se ainda não vieram. */
    private fun showDiff(pr: PullRequest, path: String? = null) {
        val context = context ?: return
        val cached = patrulha.cachedFiles(pr.number, pr.headSha)
        if (cached != null) {
            project.service<PullRequestDiff>().show(context, pr, cached, cached.files.indexOfFirst { it.path == path }.coerceAtLeast(0))
            return
        }
        background("Lendo os arquivos do PR #${pr.number}", { patrulha.files(context, pr) }) { files ->
            if (files.files.isEmpty()) {
                notify("PR #${pr.number} sem arquivos alterados", pr.title, NotificationType.INFORMATION)
            } else {
                project.service<PullRequestDiff>().show(context, pr, files, files.files.indexOfFirst { it.path == path }.coerceAtLeast(0))
            }
            rebuild()
        }
    }

    private fun updateEmptyText() {
        val context = context
        tree.emptyText.clear()
        when {
            loading && context == null -> tree.emptyText.text = "Procurando o repositório no GitHub…"
            context == null -> tree.emptyText.text = "Nenhum remote do GitHub neste projeto"
            context.account == null -> {
                tree.emptyText.text = "Nenhuma conta do GitHub em ${context.repo.host} no IntelliJ"
                tree.emptyText.appendSecondaryText("Entrar no GitHub", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { login() }
                tree.emptyText.appendSecondaryText("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                tree.emptyText.appendSecondaryText("Entrar com Token", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { loginWithToken() }
            }
            problem != null && needsToken -> {
                tree.emptyText.text = "A organização bloqueia o login do IntelliJ pelo navegador"
                tree.emptyText.appendSecondaryText("Entrar com Token", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { loginWithToken() }
            }
            problem != null -> tree.emptyText.text = "Não foi possível ler os pull requests"
            else -> tree.emptyText.text = "Nenhum pull request aberto em ${context.repo.fullName}"
        }
    }

    private fun updateHeader(items: List<PrItem>) {
        val context = context
        val requests = items.flatMap { it.record?.active.orEmpty() }
        val done = requests.count { it.likelyDone }
        val pending = requests.count { it.likelyPending }
        val waiting = requests.size - done - pending
        val drafts = items.sumOf { it.drafts.size }
        val active = items.firstOrNull { it.active }
        val subtitle = listOfNotNull(
            context?.account?.let { "Conta @${it.name}" },
            active?.let { "modo revisão do PR #${it.number}: os comentários feitos no editor vão para ele" },
        ).joinToString(" · ").ifEmpty { null }
        val warning = when {
            context == null && !loading -> "O projeto não tem um remote do GitHub. A Patrulha lê os PRs do remote origin ou do primeiro remote do GitHub."
            context != null && context.account == null -> "Entre com uma conta do GitHub em Settings > Version Control > GitHub para a Patrulha ler os PRs."
            problem != null -> problem
            active != null && active.pr != null && !active.checkedOut -> "O código local não é o head do PR #${active.number}: use Atualizar ou Encerrar Revisão para trazer o código novo, e os comentários caem nas linhas certas."
            else -> null
        }
        header.update(
            title = context?.let { "Patrulha · ${it.repo.fullName}" } ?: "Patrulha",
            subtitle = subtitle,
            stats = listOf(
                Stat(items.count { it.pr != null }, if (items.count { it.pr != null } == 1) "PR aberto" else "PRs abertos", Tone.PURPLE),
                Stat(drafts, if (drafts == 1) "não enviado" else "não enviados", CommentStatus.UNSAVED.tone),
                Stat(done, if (done == 1) "atendido" else "atendidos", Tone.GREEN),
                Stat(pending, "sem alteração", Tone.YELLOW),
                Stat(waiting, "a verificar", Tone.GRAY),
            ),
            bar = listOf(Segment(done, Tone.GREEN.accent), Segment(pending, Tone.YELLOW.accent), Segment(waiting, Tone.GRAY.accent)),
            warning = warning,
        )
    }

    /**
     * Já no head do PR, liga a revisão; senão, faz o checkout antes, guardando no stash o que não está commitado.
     * Com a revisão ligada e o código fora do head (commits novos no PR ou troca de branch), oferece atualizar.
     */
    private fun toggleReview() {
        val item = selectedPr() ?: return
        val pr = item.pr ?: return
        val context = context
        if (item.active) {
            if (item.checkedOut || context == null) {
                endReview(pr.number)
                return
            }
            when (askOutdated(pr)) {
                Messages.YES -> enterPr(context, pr)
                Messages.NO -> endReview(pr.number)
            }
            return
        }
        if (item.checkedOut || context == null) {
            startReview(pr.number, context?.repository?.let(PatrulhaService::refOf))
            return
        }
        enterPr(context, pr)
    }

    /** [Messages.YES] atualiza o código do PR, [Messages.NO] encerra a revisão. */
    private fun askOutdated(pr: PullRequest): Int = Messages.showYesNoCancelDialog(
        project,
        "O código local não é o head do PR #${pr.number} (${pr.headSha.take(7)}): o PR recebeu commits novos ou a branch foi trocada.",
        "Revisão do PR #${pr.number}",
        "Atualizar o Código do PR",
        "Encerrar Revisão",
        "Cancelar",
        Messages.getQuestionIcon(),
    )

    private fun enterPr(context: RepoContext, pr: PullRequest) {
        FileDocumentManager.getInstance().saveAllDocuments()
        background("Conferindo as alterações locais", { patrulha.localChanges(context) }) { changes ->
            if (changes.files.isNotEmpty() && !confirmStash(pr, changes)) return@background
            val title = "Fazendo o checkout do PR #${pr.number}"
            val branch = PatrulhaService.branchName(pr.number)
            // Antes do checkout: a troca de branch que ele causa não pode encerrar a revisão de outro PR.
            val previousRef = radar.activePullRequest.takeIf { it != 0 }?.let(patrulha::reviewRef)
            patrulha.setReviewRef(branch)
            val work = {
                try {
                    patrulha.enter(context, pr.number, stash = changes.files.isNotEmpty())
                } catch (e: Throwable) {
                    patrulha.setReviewRef(previousRef)
                    throw e
                }
            }
            background(title, work) { result ->
                startReview(pr.number, branch)
                val stashNote = when {
                    result.stash.isEmpty() -> null
                    result.restorable -> "Suas alterações estão no stash \"${result.stashMessage}\" e voltam quando você encerrar a revisão."
                    else -> "Suas alterações estão no stash \"${result.stashMessage}\"; restaure pelo Git > Unstash Changes quando quiser."
                }
                notify(
                    "Revisando o PR #${pr.number} na branch $branch",
                    listOfNotNull(pr.title, "Modo revisão ligado: os comentários feitos no editor vão para o PR, não para a IA.", stashNote)
                        .joinToString(" "),
                    NotificationType.INFORMATION,
                )
                refresh()
            }
        }
    }

    private fun confirmStash(pr: PullRequest, changes: LocalChanges): Boolean {
        val shown = changes.files.take(STASH_PREVIEW).joinToString("\n") { "  • $it" }
        val more = (changes.files.size - STASH_PREVIEW).takeIf { it > 0 }?.let { "\n  … e mais $it" }.orEmpty()
        val where = changes.branch?.let { "na branch $it" } ?: "no checkout atual"
        val back = if (patrulha.returnPoint() == null) {
            "Ao encerrar a revisão, a Patrulha oferece voltar para ${changes.branch ?: "o commit de agora"} e devolver as alterações."
        } else {
            "Você já veio de outra branch para revisar um PR: este stash não volta sozinho, restaure pelo Git > Unstash Changes."
        }
        return Messages.showOkCancelDialog(
            project,
            "Há ${plural(changes.files.size, "alteração não commitada", "alterações não commitadas")} $where:\n\n$shown$more\n\n" +
                "Para trazer o código do PR #${pr.number} para a branch ${PatrulhaService.branchName(pr.number)}, elas vão para o stash " +
                "(as revisões em ${ReviewCommentService.REVIEW_DIR}/ ficam onde estão).\n\n$back",
            "Iniciar a revisão do PR #${pr.number}",
            "Guardar no Stash e Revisar",
            "Cancelar",
            Messages.getWarningIcon(),
        ) == Messages.OK
    }

    /** Desliga a revisão e, se a Patrulha trouxe o usuário para o PR, oferece voltar à branch de antes com o stash. */
    private fun endReview(number: Int) {
        val context = context
        val point = patrulha.returnPoint()
        if (context == null || point == null || point.pullRequest != number) {
            stopReview(number)
            return
        }
        val drafts = radar.pullRequestComments(number).size
        val target = if (point.branch) "a branch ${point.ref}" else "o commit ${point.ref.take(7)}"
        val message = buildString {
            append("Voltar para $target")
            append(if (point.stash.isNotEmpty()) " e devolver as alterações guardadas no stash?" else "?")
            if (drafts > 0) {
                append("\n\n${plural(drafts, "comentário ainda não foi enviado", "comentários ainda não foram enviados")} ao PR: ")
                append("eles continuam na aba Patrulha, mas as linhas podem não bater fora do código do PR. Envie antes de sair.")
            }
        }
        val choice = Messages.showYesNoCancelDialog(
            project,
            message,
            "Encerrar a revisão do PR #$number",
            "Voltar para ${if (point.branch) point.ref else point.ref.take(7)}",
            "Ficar no PR",
            "Cancelar",
            Messages.getQuestionIcon(),
        )
        when (choice) {
            Messages.YES -> {
                stopReview(number)
                FileDocumentManager.getInstance().saveAllDocuments()
                background("Voltando para ${point.ref}", { patrulha.leave(context) }) { warning ->
                    notify(
                        "De volta a ${point.ref}",
                        warning ?: if (point.stash.isNotEmpty()) "As alterações que estavam no stash voltaram." else "Revisão do PR #$number encerrada.",
                        if (warning != null) NotificationType.WARNING else NotificationType.INFORMATION,
                    )
                    refresh()
                }
            }
            Messages.NO -> {
                stopReview(number)
                patrulha.forgetReturnPoint()
                if (point.stash.isNotEmpty()) {
                    notify(
                        "Revisão do PR #$number encerrada",
                        "Suas alterações continuam no stash \"${point.stashMessage}\": restaure pelo Git > Unstash Changes quando voltar para ${point.ref}.",
                        NotificationType.INFORMATION,
                    )
                }
            }
        }
    }

    /**
     * A IA revisa o PR no checkout e devolve comentários em JSON, que viram rascunhos do modo revisão. Só no
     * modo revisão e no head do PR, para as linhas baterem com o GitHub.
     */
    private fun reviewWithAi() {
        val item = selectedPr() ?: return
        val pr = item.pr ?: return
        val context = context ?: return
        val runner = AgentRunner.getInstance(project)
        background("Preparando a revisão do PR #${pr.number} para a IA", { patrulha.files(context, pr) }) { files ->
            val instructions = runner.writeInstructions(AiReview.fileName(pr.number), AiReview.instructions(pr, files)) ?: return@background
            runner.run(
                AgentTask(
                    "Revisão do PR #${pr.number}",
                    "Revise o pull request seguindo as instruções em $instructions e responda só com o JSON pedido.",
                    write = false,
                ),
            ) { result -> if (result.ok) addAiDrafts(pr.number, AiReview.parse(result.answer)) }
        }
    }

    private fun addAiDrafts(number: Int, comments: List<AiComment>) {
        if (radar.activePullRequest != number) {
            notify("Revisão da IA descartada", "O modo revisão do PR #$number foi encerrado antes de a IA terminar.", NotificationType.WARNING)
            return
        }
        val added = comments.mapNotNull { comment ->
            val file = radar.findFile(comment.path)?.takeUnless { it.isDirectory } ?: return@mapNotNull null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
            val last = (comment.endLine - 1).coerceAtMost(document.lineCount - 1)
            val first = (comment.startLine - 1).coerceAtMost(last)
            if (first < 0) return@mapNotNull null
            radar.addComment(file, document, first..last, comment.comment, discuss = false, suggestion = comment.suggestion)
        }
        val skipped = comments.size - added.size
        notify(
            if (added.isEmpty()) "A IA não apontou nada no PR #$number" else "${plural(added.size, "rascunho", "rascunhos")} da IA no PR #$number",
            listOfNotNull(
                "Revise, edite ou remova cada um antes de Enviar ao GitHub.".takeIf { added.isNotEmpty() },
                "$skipped ${if (skipped == 1) "comentário apontava" else "comentários apontavam"} para arquivos fora do projeto.".takeIf { skipped > 0 },
            ).joinToString(" ").ifEmpty { "Nenhum problema encontrado nos arquivos alterados." },
            NotificationType.INFORMATION,
        )
        rebuild()
    }

    /** Com a revisão, o histórico do PR aparece no editor; ao encerrar, sai. Sair de [ref] encerra a revisão. */
    private fun startReview(number: Int, ref: String?) {
        patrulha.setReviewRef(ref)
        radar.startPullRequestReview(number)
        patrulha.setHistoryShown(number, true)
        syncQuietly(listOf(number))
    }

    private fun stopReview(number: Int) {
        radar.stopPullRequestReview()
        patrulha.setHistoryShown(number, false)
    }

    private fun editSelected() {
        when (val item = selectedNode()?.userObject) {
            is DraftItem -> TargetComments.edit(project, item.comment)
            is RequestItem -> SentRequestEditor.edit(project, item.number, item.request)
        }
    }

    /** Apagar no GitHub some com a conversa do comentário, então só acontece com a escolha explícita. */
    private fun deleteSelected() {
        val item = selectedNode()?.userObject as? RequestItem ?: return
        val request = item.request
        val onGitHubPossible = request.commentId != null && !request.deleted
        val choice = if (onGitHubPossible) {
            Messages.showYesNoCancelDialog(
                project,
                "Excluir o pedido ${request.id} (${request.path}, ${request.location}) do PR #${item.number}?\n\n" +
                    "Apagar também no GitHub remove o comentário e a conversa dele do PR.",
                "Excluir ${request.id}",
                "Apagar no GitHub e no .md",
                "Só Tirar do .md",
                "Cancelar",
                Messages.getWarningIcon(),
            )
        } else {
            if (Messages.showOkCancelDialog(
                    project,
                    "Tirar o pedido ${request.id} do .md do PR #${item.number}?" +
                        if (request.deleted) " O comentário já foi apagado no GitHub." else " Ele está no texto geral da revisão, que fica como está no GitHub.",
                    "Excluir ${request.id}",
                    "Tirar do .md",
                    "Cancelar",
                    Messages.getWarningIcon(),
                ) == Messages.OK
            ) Messages.NO else Messages.CANCEL
        }
        if (choice != Messages.YES && choice != Messages.NO) return
        val onGitHub = choice == Messages.YES
        val context = context
        background("Excluindo ${request.id} do PR #${item.number}", { patrulha.deleteRequest(context, item.number, request.id, onGitHub) }) {
            radar.refreshPaths(listOf(request.path))
            notify(
                "${request.id} excluído do PR #${item.number}",
                if (onGitHub) "O comentário foi apagado no GitHub e o pedido saiu do .md." else "O pedido saiu do .md; o GitHub ficou como estava.",
                NotificationType.INFORMATION,
            )
            rebuild()
        }
    }

    /**
     * O que Mostrar/Ocultar no Editor alterna, e se está no editor agora: um pedido selecionado, ou o
     * histórico do PR quando a seleção é o PR.
     */
    private fun historyTarget(): Pair<Any, Boolean>? = when (val item = selectedNode()?.userObject) {
        is RequestItem -> item to patrulha.isInEditor(item.number, item.request.id)
        is PrItem -> if (item.record?.requests.isNullOrEmpty()) null else item to patrulha.isHistoryShown(item.number)
        else -> null
    }

    private fun toggleHistory() {
        val (item, shown) = historyTarget() ?: return
        when (item) {
            is RequestItem -> if (shown) {
                patrulha.setRequestsHidden(item.number, listOf(item.request.id), true)
            } else {
                patrulha.setRequestsHidden(item.number, listOf(item.request.id), false)
                patrulha.setHistoryShown(item.number, true)
                navigate(item.request.path, (item.request.startLine - 1).takeIf { item.request.target == CommentTarget.LINES })
            }
            is PrItem -> patrulha.setHistoryShown(item.number, !shown)
        }
    }

    private fun send() {
        val item = selectedPr() ?: return
        val pr = item.pr ?: return
        val context = context ?: return
        val drafts = radar.pullRequestComments(pr.number)
        if (drafts.isEmpty()) return
        val warning = if (context.repository.currentRevision != pr.headSha) {
            "O código local não é o head do PR (${pr.headSha.take(7)}): as linhas dos comentários podem não bater com o GitHub. Atualize o código do PR pelo botão da revisão antes de enviar."
        } else {
            null
        }
        background("Lendo os arquivos do PR #${pr.number}", { ReviewPlan.plan(drafts, patrulha.client(context).files(context.repo, pr.number)) }) { planned ->
            val dialog = SendReviewDialog(project, pr, planned, warning)
            if (!dialog.showAndGet()) return@background
            val event = dialog.event
            val general = dialog.generalText
            background("Enviando os pedidos ao PR #${pr.number}", { patrulha.send(context, pr, drafts, general, event) }) { result ->
                radar.remove(drafts)
                notify(
                    "${plural(result.requests.size, "pedido enviado", "pedidos enviados")} ao PR #${pr.number}",
                    "Ficaram em iron-dome/pull-requests/${PullRequestRecord.fileName(pr.number)} para verificar depois.",
                    NotificationType.INFORMATION,
                    NotificationAction.createSimpleExpiring("Abrir no GitHub") { BrowserUtil.browse(result.reviewUrl.ifBlank { pr.url }) },
                    NotificationAction.createSimple("Abrir .md") { openRecord(pr.number) },
                )
                rebuild()
            }
        }
    }

    private fun verify() {
        val item = selectedPr() ?: return
        val context = context ?: return
        val before = item.record?.requests.orEmpty().associateBy { it.id }
        background("Verificando os pedidos do PR #${item.number}", { patrulha.verify(context, item.number) }) { record ->
            val active = record.active
            val done = active.count { it.likelyDone }
            val pending = active.count { it.likelyPending }
            val waiting = active.size - done - pending
            val summary = listOfNotNull(
                done.takeIf { it > 0 }?.let { plural(it, "atendido", "atendidos") },
                pending.takeIf { it > 0 }?.let { "$it sem alteração no código" },
                waiting.takeIf { it > 0 }?.let { "$it sem sinal ainda" },
            ).joinToString(", ")
            val remote = remoteChanges(
                record.requests.filter { now ->
                    val old = before[now.id]
                    old == null || now.remote != old.remote || now.text != old.text || now.suggestion != old.suggestion
                },
            )
            notify(
                "PR #${item.number}: ${summary.ifEmpty { "nenhum pedido" }}",
                listOfNotNull(
                    "Código conferido no head ${active.firstNotNullOfOrNull { it.codeCommit }?.take(7) ?: "atual"} e conversas lidas no GitHub.",
                    remote,
                ).joinToString(" "),
                if (pending > 0) NotificationType.WARNING else NotificationType.INFORMATION,
                NotificationAction.createSimpleExpiring("Pedir Parecer da IA") { askAi(item.number) },
                NotificationAction.createSimple("Abrir .md") { openRecord(item.number) },
            )
            rebuild()
        }
    }

    /** "P3 foi editado e P5 apagado no GitHub.", para os pedidos que mudaram lá desde a última leitura. */
    private fun remoteChanges(changed: List<ChangeRequest>): String? {
        val edited = changed.filter { it.remote == RemoteStatus.EDITED }.map { it.id }
        val deleted = changed.filter { it.deleted }.map { it.id }
        val parts = listOfNotNull(
            edited.takeIf { it.isNotEmpty() }?.let { "${it.joinToString()} ${if (it.size == 1) "foi editado" else "foram editados"}" },
            deleted.takeIf { it.isNotEmpty() }?.let { "${it.joinToString()} ${if (it.size == 1) "apagado" else "apagados"}" },
        )
        return if (parts.isEmpty()) null else parts.joinToString(" e ") + " no GitHub."
    }

    /** Em segundo plano e sem aviso, a não ser que o revisor tenha editado ou apagado algo no GitHub. */
    private fun syncQuietly(numbers: Collection<Int>) {
        val context = context?.takeIf { it.account != null } ?: return
        val wanted = numbers.filter { number -> patrulha.record(number)?.requests?.any { it.commentId != null && !it.deleted } == true }
        if (wanted.isEmpty()) return
        object : Task.Backgroundable(project, "Patrulha: sincronizando os pedidos com o GitHub", true) {
            private val results = mutableListOf<PatrulhaService.SyncResult>()

            override fun run(indicator: ProgressIndicator) {
                wanted.forEach { number -> runCatching { patrulha.sync(context, number) }.onSuccess { if (it.changed) results += it } }
            }

            override fun onSuccess() {
                results.forEach { result ->
                    val changed = patrulha.record(result.number)?.requests.orEmpty().filter { it.id in result.edited || it.id in result.deleted }
                    notify(
                        "PR #${result.number} mudou no GitHub",
                        remoteChanges(changed).orEmpty() + " O .md e o histórico no editor já estão atualizados.",
                        NotificationType.INFORMATION,
                        NotificationAction.createSimple("Abrir .md") { openRecord(result.number) },
                    )
                }
                if (results.isNotEmpty()) rebuild()
            }
        }.queue()
    }

    private fun askAi(number: Int? = selectedPr()?.number) {
        number ?: return
        val prompt = "Verifique os pedidos de alteração do PR #$number em ${ReviewCommentService.REVIEW_DIR}/${PullRequestRecord.DIR}/" +
            "${PullRequestRecord.fileName(number)}, seguindo as instruções do arquivo."
        val runner = AgentRunner.getInstance(project)
        if (runner.available()) {
            runner.run(
                AgentTask("Parecer dos pedidos do PR #$number", "$prompt Escreva só no campo de parecer e no checklist de cada pedido.", write = true),
                "Abrir .md" to { _ -> openRecord(number) },
            ) { patrulha.historyChanged(number) }
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(prompt))
        openRecord(number)
        notify(
            "Pedido para a IA copiado",
            "Cole na sua IA: \"$prompt\". Quando ela marcar [x] ou escrever o parecer, a aba Patrulha atualiza sozinha.",
            NotificationType.INFORMATION,
        )
    }

    private fun openRecord(number: Int) {
        val file = patrulha.recordFile(number)?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) } ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun login() {
        val server = context?.repo?.host?.let { runCatching { GithubServerPath.from(it) }.getOrNull() }
        val account = GHAccountsUtil.requestNewAccount(server = server, project = project)
        if (account != null) refresh()
    }

    /** Troca a credencial da conta por um token pessoal, que não passa pela restrição a OAuth Apps da organização. */
    private fun loginWithToken() {
        val account = context?.account
        val result = if (account != null) {
            GHAccountsUtil.requestReLogin(account, project, null, AuthorizationType.TOKEN, GHLoginSource.PR_LIST)
        } else {
            val server = context?.repo?.host?.let { runCatching { GithubServerPath.from(it) }.getOrNull() }
            GHAccountsUtil.requestNewAccount(server, null, project, null, AuthorizationType.TOKEN)
        }
        if (result != null) refresh()
    }

    private fun installNavigation() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                when (val item = (tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is DraftItem -> navigate(item.comment.filePath, item.comment.startLine.takeIf { item.comment.target == CommentTarget.LINES })
                    is RequestItem -> navigate(item.request.path, (item.request.startLine - 1).takeIf { item.request.target == CommentTarget.LINES })
                    is FileItem -> openPrs.firstOrNull { it.number == item.number }?.let { showDiff(it, item.file.path) }
                    else -> return
                }
                e.consume()
            }
        })
        tree.addTreeSelectionListener { selectedPr()?.pr?.let(::ensureFiles) }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val item = node.userObject) {
                    is PrItem -> item.pr?.let(::ensureFiles)
                    is FilesItem -> {
                        expandedFiles += item.number
                        openPrs.firstOrNull { it.number == item.number }?.let(::ensureFiles)
                        expandFolders(node)
                    }
                    is DirItem -> collapsedDirs -= dirKey(item)
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                when (val item = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is FilesItem -> expandedFiles -= item.number
                    is DirItem -> collapsedDirs += dirKey(item)
                }
            }
        })
    }

    private fun navigate(path: String, line: Int?) {
        val file = radar.findFile(path) ?: return
        if (file.isDirectory) return
        OpenFileDescriptor(project, file, line?.coerceAtLeast(0) ?: 0, 0).navigate(true)
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    /** O PR da linha selecionada, mesmo quando a seleção é um pedido ou rascunho dele. */
    private fun selectedPr(): PrItem? {
        var node = selectedNode()
        while (node != null) {
            (node.userObject as? PrItem)?.let { return it }
            node = node.parent as? DefaultMutableTreeNode
        }
        return null
    }

    private fun selectedUrl(): String? = when (val item = selectedNode()?.userObject) {
        is RequestItem -> item.request.url
        is FilesItem, is DirItem, is FileItem -> selectedPr()?.pr?.url?.let { "$it/files" }
        else -> selectedPr()?.let { it.pr?.url ?: it.record?.url }
    }

    /** Roda [work] fora da EDT e entrega o resultado a [onSuccess] na EDT; erros viram notificação. */
    private fun <T> background(title: String, work: () -> T, onSuccess: (T) -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                result = runCatching(work).onFailure { if (it is ProcessCanceledException) throw it }
            }

            override fun onSuccess() {
                result?.onSuccess(onSuccess)?.onFailure { error ->
                    val actions = listOfNotNull(
                        NotificationAction.createSimpleExpiring("Entrar com Token") { loginWithToken() }
                            .takeIf { (error as? GitHubException)?.oauthRestricted == true },
                    )
                    notify(
                        "Patrulha: ${title.replaceFirstChar { it.lowercase() }} falhou",
                        error.message ?: error.javaClass.simpleName,
                        NotificationType.ERROR,
                        *actions.toTypedArray(),
                    )
                }
            }
        }.queue()
    }

    private fun notify(title: String, content: String, type: NotificationType, vararg actions: NotificationAction) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, type)
            .setIcon(IronDomeIcons.patrulha)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }

    private fun action(
        description: String,
        tooltip: String,
        icon: Icon,
        enabled: () -> Boolean,
        text: () -> String = { description },
        perform: () -> Unit,
    ) = object : DumbAwareAction(description, tooltip, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
            e.presentation.text = text()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private class Renderer : RichTreeCellRenderer() {

        override fun describe(row: Row, value: Any?) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Section -> {
                    row.icon(item.icon)
                    row.text(item.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    row.pill(item.count.toString(), Tone.GRAY)
                }
                is PrItem -> describePr(row, item)
                is DraftItem -> {
                    row.icon(AllIcons.General.Balloon)
                    row.text(item.comment.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    row.text("${item.comment.filePath}, ${where(item.comment)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    row.pill("Não enviado", CommentStatus.UNSAVED.tone, bold = true)
                    if (item.comment.suggestion.isNotBlank()) row.pill("Com código sugerido", Tone.PURPLE)
                    row.detail(item.comment.text.trim().lines().first().take(160))
                }
                is RequestItem -> {
                    describeRequest(row, item.request)
                    if (item.request.target != CommentTarget.PACKAGE && !item.inEditor) row.pill("Oculto no editor", Tone.GRAY)
                }
                is FilesItem -> describeFiles(row, item)
                is DirItem -> describeDir(row, item)
                is FileItem -> describeFile(row, item)
                is LoadingItem -> {
                    row.icon(AllIcons.Process.Step_1)
                    row.text(item.text, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }

        private fun describeFiles(row: Row, item: FilesItem) {
            row.icon(AllIcons.Actions.Diff)
            row.text("Arquivos alterados", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            val files = item.files ?: return
            row.pill(plural(files.files.size, "arquivo", "arquivos"), Tone.GRAY)
            changes(row, files.additions, files.deletions)
            diffBar(row, files.additions, files.deletions)
            val added = files.files.count { it.added }
            val removed = files.files.count { it.removed }
            val renamed = files.files.count { it.status == "renamed" }
            if (added > 0) row.pill(plural(added, "novo", "novos"), Tone.GREEN)
            if (removed > 0) row.pill(plural(removed, "removido", "removidos"), Tone.RED)
            if (renamed > 0) row.pill(plural(renamed, "renomeado", "renomeados"), Tone.PURPLE)
            row.tooltip("Agrupados por pasta. Duplo clique num arquivo abre o diff do PR; as setas do diff passam pelos demais.")
        }

        private fun describeDir(row: Row, item: DirItem) {
            val folder = item.folder
            row.icon(AllIcons.Nodes.Folder)
            val parts = folder.name.split('/')
            if (parts.size > 1) row.text(parts.dropLast(1).joinToString("/") + "/", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            row.text(parts.last(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            row.pill(folder.allFiles.size.toString(), Tone.GRAY)
            changes(row, folder.additions, folder.deletions)
            if (item.comments > 0) row.pill(plural(item.comments, "comentário", "comentários"), Tone.PURPLE)
            row.tooltip(folder.path)
        }

        private fun describeFile(row: Row, item: FileItem) {
            val file = item.file
            val name = file.path.substringAfterLast('/')
            row.icon(FileTypeManager.getInstance().getFileTypeByFileName(name).icon)
            val status = when {
                file.added -> FileStatus.ADDED
                file.removed -> FileStatus.DELETED
                else -> FileStatus.MODIFIED
            }
            row.text(name, SimpleTextAttributes(if (file.removed) SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN, status.color))
            if (!file.status.equals("modified", ignoreCase = true)) {
                row.pill(
                    file.statusLabel,
                    when {
                        file.added -> Tone.GREEN
                        file.removed -> Tone.RED
                        else -> Tone.PURPLE
                    },
                )
            }
            changes(row, file.additions, file.deletions)
            if (item.comments > 0) row.pill(plural(item.comments, "comentário", "comentários"), Tone.PURPLE)
            file.previousPath?.let { row.detail("antes: $it") }
            row.tooltip(file.path)
        }

        /** A proporção de linhas adicionadas e removidas, como no diffstat do GitHub. */
        private fun diffBar(row: Row, additions: Int, deletions: Int) {
            if (additions + deletions == 0) return
            row.bar(listOf(Segment(additions, Tone.GREEN.accent), Segment(deletions, Tone.RED.accent)), width = 40)
        }

        private fun changes(row: Row, additions: Int, deletions: Int) {
            if (additions > 0) row.text("+$additions", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Tone.GREEN.foreground))
            if (deletions > 0) row.text("−$deletions", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Tone.RED.foreground))
        }

        private fun describePr(row: Row, item: PrItem) {
            row.icon(IronDomeIcons.patrulha)
            row.text("#${item.number}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            row.text(item.title)
            if (item.pr?.draft == true) row.pill("Rascunho", Tone.GRAY)
            if (item.active) row.pill("Em revisão", Tone.PURPLE, bold = true)
            if (item.checkedOut) row.pill("No checkout", Tone.TEAL)
            if (item.historyShown && item.record?.requests?.isNotEmpty() == true) row.pill("Histórico no editor", Tone.BLUE)
            if (item.drafts.isNotEmpty()) row.pill(plural(item.drafts.size, "não enviado", "não enviados"), CommentStatus.UNSAVED.tone)
            val requests = item.record?.active.orEmpty()
            if (requests.isNotEmpty()) {
                val done = requests.count { it.likelyDone }
                val pending = requests.count { it.likelyPending }
                row.bar(listOf(Segment(done, Tone.GREEN.accent), Segment(pending, Tone.YELLOW.accent), Segment(requests.size - done - pending, Tone.GRAY.accent)))
                row.pill("$done/${requests.size} atendidos", if (done == requests.size) Tone.GREEN else Tone.GRAY)
            }
            item.pr?.let { row.detail("@${it.author} · ${it.headRef} → ${it.baseRef}") }
                ?: item.record?.let { row.detail("${it.headRef} → ${it.baseRef} · fechado ou fora dos PRs abertos") }
        }

        private fun describeRequest(row: Row, request: ChangeRequest) {
            row.icon(
                when {
                    request.deleted -> AllIcons.General.Remove
                    request.likelyDone -> AllIcons.General.InspectionsOK
                    request.likelyPending -> AllIcons.General.Warning
                    else -> AllIcons.General.Information
                },
            )
            row.text(
                request.id,
                if (request.deleted) SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT or SimpleTextAttributes.STYLE_BOLD, null)
                else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
            )
            row.text("${request.path}, ${request.location}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            when (request.remote) {
                RemoteStatus.DELETED -> {
                    row.pill(request.remote.label, Tone.RED, bold = true)
                    row.detail(request.text.lines().first().take(160))
                    row.tooltip("O revisor apagou este comentário no GitHub. Ele fica no .md como registro, fora das contas; use Excluir Pedido para tirá-lo.")
                    return
                }
                RemoteStatus.EDITED -> row.pill(request.remote.label, Tone.BLUE)
                RemoteStatus.SYNCED -> Unit
            }
            row.pill("Código: ${request.code.label}", codeTone(request.code))
            row.pill("Conversa: ${request.thread.label}", threadTone(request.thread))
            row.pill("IA: ${request.ai.label}", aiTone(request.ai))
            row.detail(request.text.lines().first().take(160))
            row.tooltip(listOfNotNull(request.text, request.lastReply?.let { "Última resposta: $it" }, request.aiNote.ifBlank { null }?.let { "Parecer da IA: $it" }).joinToString("\n\n"))
        }

        private fun where(comment: ReviewComment) = when (comment.target) {
            CommentTarget.LINES -> if (comment.startLine == comment.endLine) "linha ${comment.startLine + 1}" else "linhas ${comment.startLine + 1}–${comment.endLine + 1}"
            CommentTarget.FILE -> "arquivo inteiro"
            CommentTarget.PACKAGE -> "pacote inteiro"
        }

        private fun codeTone(status: CodeStatus) = when (status) {
            CodeStatus.CHANGED, CodeStatus.AS_SUGGESTED, CodeStatus.FILE_REMOVED -> Tone.GREEN
            CodeStatus.UNCHANGED, CodeStatus.MOVED -> Tone.YELLOW
            else -> Tone.GRAY
        }

        private fun threadTone(status: ThreadStatus) = when (status) {
            ThreadStatus.RESOLVED -> Tone.GREEN
            ThreadStatus.REPLIED -> Tone.BLUE
            ThreadStatus.OPEN -> Tone.YELLOW
            else -> Tone.GRAY
        }

        private fun aiTone(verdict: AiVerdict) = when (verdict) {
            AiVerdict.DONE -> Tone.GREEN
            AiVerdict.NOT_DONE -> Tone.RED
            AiVerdict.PENDING -> Tone.GRAY
        }
    }
}
