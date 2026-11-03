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
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
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
import com.intellij.util.ui.tree.TreeUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.RichTreeCellRenderer
import com.mio.irondome.base.Segment
import com.mio.irondome.base.Stat
import com.mio.irondome.base.SummaryHeader
import com.mio.irondome.base.Tone
import com.mio.irondome.base.plural
import com.mio.irondome.base.refreshAction
import com.mio.irondome.radar.CommentStatus
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import com.mio.irondome.radar.ReviewCommentService
import com.mio.irondome.radar.ReviewCommentsListener
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
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Aba Patrulha: os PRs abertos no GitHub, os comentários do Radar ainda não enviados a cada um e os
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
    )

    private class Section(val title: String, val icon: Icon, val count: Int)

    private class DraftItem(val number: Int, val comment: ReviewComment)

    private class RequestItem(val number: Int, val request: ChangeRequest)

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
                "Faz o checkout do PR, se precisar (com o que não está commitado no stash), e liga o Radar a ele: os comentários novos vão para o PR",
                IronDomeIcons.patrulha,
                enabled = { selectedPr()?.pr != null }, text = {
                    val item = selectedPr()
                    when {
                        item?.active != true -> "Revisar Este PR"
                        item.checkedOut || context == null -> "Encerrar Revisão"
                        else -> "Atualizar ou Encerrar Revisão"
                    }
                }) { toggleReview() },
            action("Enviar ao GitHub", "Envia os comentários não enviados deste PR como uma revisão", AllIcons.Actions.Upload,
                enabled = { selectedPr()?.let { it.pr != null && it.drafts.isNotEmpty() } == true }) { send() },
            Separator.getInstance(),
            action("Verificar Pedidos", "Confere no head atual do PR e nas conversas se os pedidos foram atendidos", AllIcons.Actions.Checked,
                enabled = { selectedPr()?.record?.requests?.isNotEmpty() == true && context != null }) { verify() },
            action("Pedir Parecer da IA", "Copia um pedido para a IA avaliar cada pedido no .md e abre o arquivo", AllIcons.Actions.Lightning,
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
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { "/${ReviewCommentService.REVIEW_DIR}/${PullRequestRecord.DIR}/" in it.path.replace('\\', '/') }) scheduleRebuild()
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
                rebuild()
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
            )
        }
    }

    private fun rebuild() {
        val selected = selectedPr()?.number
        val expanded = TreeUtil.collectExpandedUserObjects(tree).mapNotNull { (it as? PrItem)?.number }.toSet()
        val items = items()
        val root = DefaultMutableTreeNode()
        val open = items.filter { it.pr != null }
        val closed = items.filter { it.pr == null }
        fun addPr(parent: DefaultMutableTreeNode, item: PrItem) {
            val node = DefaultMutableTreeNode(item)
            item.drafts.forEach { node.add(DefaultMutableTreeNode(DraftItem(item.number, it))) }
            item.record?.requests?.forEach { node.add(DefaultMutableTreeNode(RequestItem(item.number, it))) }
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
        TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).forEach { node ->
            val item = node.userObject as? PrItem ?: return@forEach
            if (item.number in expanded || item.active || item.number == selected) tree.expandPath(TreeUtil.getPathFromRoot(node))
            if (item.number == selected) tree.selectionPath = TreeUtil.getPathFromRoot(node)
        }
        updateEmptyText()
        updateHeader(items)
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
        val requests = items.flatMap { it.record?.requests.orEmpty() }
        val done = requests.count { it.likelyDone }
        val pending = requests.count { it.likelyPending }
        val waiting = requests.size - done - pending
        val drafts = items.sumOf { it.drafts.size }
        val active = items.firstOrNull { it.active }
        val subtitle = listOfNotNull(
            context?.account?.let { "Conta @${it.name}" },
            active?.let { "revisando o PR #${it.number}: os comentários novos do Radar vão para ele" },
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
            radar.startPullRequestReview(pr.number)
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
            background(title, { patrulha.enter(context, pr.number, stash = changes.files.isNotEmpty()) }) { result ->
                radar.startPullRequestReview(pr.number)
                val branch = PatrulhaService.branchName(pr.number)
                val stashNote = when {
                    result.stash.isEmpty() -> null
                    result.restorable -> "Suas alterações estão no stash \"${result.stashMessage}\" e voltam quando você encerrar a revisão."
                    else -> "Suas alterações estão no stash \"${result.stashMessage}\"; restaure pelo Git > Unstash Changes quando quiser."
                }
                notify(
                    "Revisando o PR #${pr.number} na branch $branch",
                    listOfNotNull(pr.title, "Os comentários novos do Radar vão para o PR.", stashNote).joinToString(" "),
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
            radar.stopPullRequestReview()
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
                radar.stopPullRequestReview()
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
                radar.stopPullRequestReview()
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
        background("Verificando os pedidos do PR #${item.number}", { patrulha.verify(context, item.number) }) { record ->
            val done = record.requests.count { it.likelyDone }
            val pending = record.requests.count { it.likelyPending }
            val waiting = record.requests.size - done - pending
            val summary = listOfNotNull(
                done.takeIf { it > 0 }?.let { plural(it, "atendido", "atendidos") },
                pending.takeIf { it > 0 }?.let { "$it sem alteração no código" },
                waiting.takeIf { it > 0 }?.let { "$it sem sinal ainda" },
            ).joinToString(", ")
            notify(
                "PR #${item.number}: ${summary.ifEmpty { "nenhum pedido" }}",
                "Código conferido no head ${record.requests.firstNotNullOfOrNull { it.codeCommit }?.take(7) ?: "atual"} e conversas lidas no GitHub.",
                if (pending > 0) NotificationType.WARNING else NotificationType.INFORMATION,
                NotificationAction.createSimpleExpiring("Pedir Parecer da IA") { askAi(item.number) },
                NotificationAction.createSimple("Abrir .md") { openRecord(item.number) },
            )
            rebuild()
        }
    }

    private fun askAi(number: Int? = selectedPr()?.number) {
        number ?: return
        val prompt = "Verifique os pedidos de alteração do PR #$number em ${ReviewCommentService.REVIEW_DIR}/${PullRequestRecord.DIR}/" +
            "${PullRequestRecord.fileName(number)}, seguindo as instruções do arquivo."
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
                    else -> return
                }
                e.consume()
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
                is RequestItem -> describeRequest(row, item.request)
            }
        }

        private fun describePr(row: Row, item: PrItem) {
            row.icon(IronDomeIcons.patrulha)
            row.text("#${item.number}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            row.text(item.title)
            if (item.pr?.draft == true) row.pill("Rascunho", Tone.GRAY)
            if (item.active) row.pill("Em revisão", Tone.PURPLE, bold = true)
            if (item.checkedOut) row.pill("No checkout", Tone.TEAL)
            if (item.drafts.isNotEmpty()) row.pill(plural(item.drafts.size, "não enviado", "não enviados"), CommentStatus.UNSAVED.tone)
            val requests = item.record?.requests.orEmpty()
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
                    request.likelyDone -> AllIcons.General.InspectionsOK
                    request.likelyPending -> AllIcons.General.Warning
                    else -> AllIcons.General.Information
                },
            )
            row.text(request.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            row.text("${request.path}, ${request.location}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
