package com.mio.irondome.interceptor

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.history.LocalHistoryException
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.messages.Topic
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.rootDir
import com.mio.irondome.base.rootPath
import com.mio.irondome.radar.ReviewCommentService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * O que a IA fez: [answer] é a resposta final; [files] os arquivos que ela escreveu ou editou;
 * [transcript], o registro da execução em `iron-dome/ia/`.
 */
class AgentResult(
    val task: AgentTask,
    val provider: AgentProvider,
    val answer: String,
    val error: String?,
    val cancelled: Boolean,
    val files: Set<String>,
    val tools: List<String>,
    val model: String?,
    val duration: Duration,
    val transcript: Path?,
) {
    val ok: Boolean get() = error == null && !cancelled
}

/**
 * O andamento da execução, lido pelas barras de progresso das abas. [inTerminal]: a IA roda no terminal da IDE
 * e o plugin só espera a resposta; cancelar para de esperar, sem fechar o terminal.
 */
class AgentProgress(val task: AgentTask, val provider: AgentProvider, val inTerminal: Boolean = false) {
    val startedAt: Long = System.currentTimeMillis()

    /** O passo de agora, como "Lendo src/Frete.kt". */
    @Volatile
    var step: String = "Iniciando ${provider.title}…"
        internal set

    /** A última linha que a IA escreveu. */
    @Volatile
    var detail: String = ""
        internal set

    /** Quantas ações (leituras, buscas, edições, comandos) a IA já fez. */
    @Volatile
    var actions: Int = 0
        internal set

    /** Quantos arquivos a IA escreveu ou editou até agora. */
    @Volatile
    var filesTouched: Int = 0
        internal set

    /** Os últimos passos antes do atual, do mais recente para o mais antigo, para a trilha na barra. */
    @Volatile
    var trail: List<String> = emptyList()
        private set

    @Volatile
    var cancelled = false
        private set

    /** Registra uma ação: o passo atual vai para a trilha e [step] passa a ser [what]. */
    internal fun advance(what: String) {
        trail = (listOf(step) + trail).filter { it != what }.take(TRAIL_SIZE)
        step = what
        actions++
    }

    fun cancel() {
        cancelled = true
    }

    private companion object {
        const val TRAIL_SIZE = 3
    }
}

/**
 * Roda o CLI de IA configurado sobre o projeto, fora da EDT, com o andamento em [progress] para as abas
 * mostrarem e cancelarem. Ao terminar, grava o registro, atualiza os arquivos que a IA mexeu e avisa.
 */
@Service(Service.Level.PROJECT)
class AgentRunner(private val project: Project) {

    /** Um por vez por projeto: dois agentes escrevendo nos mesmos arquivos se atropelam. */
    @Volatile
    var progress: AgentProgress? = null
        private set

    val running: AgentTask? get() = progress?.task

    /** Configurada e testada: só assim os botões de IA se habilitam. Sem isso, quem chama cai no fluxo manual. */
    fun available(): Boolean = AgentSettings.ready

    /**
     * [onDone] roda na EDT com o resultado, inclusive em erro ou cancelamento; [actions] entram no aviso de
     * sucesso, além de Ver Resposta. Com [unattended], de quem roda sozinho (como as respostas automáticas da
     * Patrulha), a IA vai sempre em segundo plano, sem perguntas nem aviso de sucesso, e quando ela está ocupada ou
     * não configurada só devolve false, para tentar de novo depois. Devolve se a execução começou. Na EDT.
     */
    fun run(
        request: AgentTask,
        vararg actions: Pair<String, (AgentResult) -> Unit>,
        unattended: Boolean = false,
        onDone: (AgentResult) -> Unit = {},
    ): Boolean {
        if (request.write && (unattended || refusesWrite())) return false
        val task = request.copy(prompt = AgentCommand.guarded(request))
        val provider = AgentSettings.provider
        val launch = AgentSettings.launch()
        if (launch == null || !AgentSettings.ready) {
            if (!unattended) notifyNotReady(provider, launch == null)
            return false
        }
        if (running != null) {
            if (!unattended) notify("A IA já está trabalhando", "Espere ${running?.title} terminar ou cancele na barra de progresso da aba.", NotificationType.WARNING)
            return false
        }
        val root = project.rootPath() ?: return false
        val terminal = when (if (unattended) AgentRunMode.BACKGROUND else chooseMode(task)) {
            null -> return false
            AgentRunMode.TERMINAL -> AgentTerminal.available() ?: run {
                notify("Terminal indisponível", "O plugin de terminal da IDE está desligado: a IA roda na aba.", NotificationType.WARNING)
                null
            }
            else -> null
        }
        if (task.write && terminal == null && !confirmWrite(task, provider)) return false
        FileDocumentManager.getInstance().saveAllDocuments()
        val checkpoint = if (task.write) checkpoint(task) else null
        val current = AgentProgress(task, provider, inTerminal = terminal != null)
        progress = current
        // Um problema só no ícone não pode deixar a IA presa como trabalhando.
        runCatching { AgentActivity.started(project, task) }.onFailure { LOG.warn("O ícone da IA trabalhando não pôde ser trocado", it) }
        project.messageBus.syncPublisher(RUNS).changed()
        val work: () -> AgentResult = if (terminal == null) {
            { execute(task, provider, launch, root, current) }
        } else {
            val answer = answerFile(task) ?: run {
                finish(failure(task, provider, "O projeto não tem pasta no disco."), actions, unattended, onDone, checkpoint)
                return true
            }
            val relative = "${ReviewCommentService.REVIEW_DIR}/$DIR/$ANSWERS_DIR/${answer.fileName}"
            val prompt = "${task.prompt} ${AgentCommand.answerInstruction(relative)}"
            current.step = "No terminal: abrindo o terminal"
            work@{
                val shell = ShellKind.of(terminal.shellPath(project), SystemInfo.isWindows)
                val command = launch.terminal(provider, prompt, AgentSettings.model, AgentSettings.timeoutMinutes, shell, root)
                var opened: Throwable? = null
                ApplicationManager.getApplication().invokeAndWait({
                    opened = runCatching { terminal.run(project, root, "IA: ${task.title}", command) }.exceptionOrNull()
                }, ModalityState.any())
                opened?.let {
                    LOG.warn("O terminal da IDE não abriu", it)
                    return@work failure(task, provider, "O terminal não abriu: ${it.message ?: it.javaClass.simpleName}")
                }
                current.step = "No terminal: esperando a resposta em $relative"
                waitForAnswer(task, provider, answer, command, current)
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                checkpoint?.let { snapshot(it, task, root, current) }
                work()
            } catch (e: Throwable) {
                LOG.warn("A execução da IA falhou", e)
                failure(task, provider, e.message ?: "A execução não terminou.")
            }
            ApplicationManager.getApplication().invokeLater({ finish(result, actions, unattended, onDone, checkpoint) }, project.disposed)
        }
        return true
    }

    /**
     * Para onde Desfazer Alterações da IA volta o projeto: a foto do git em [GitSnapshot] e, sem git, o ponto do Local
     * History da IDE, que só volta o que a IDE já tinha lido.
     */
    private class Checkpoint(val label: Label?) {
        @Volatile
        var git: String? = null
    }

    private fun checkpoint(task: AgentTask): Checkpoint =
        Checkpoint(
            runCatching { LocalHistory.getInstance().putSystemLabel(project, "Iron Dome IA: antes de ${task.title}") }
                .onFailure { LOG.warn("O ponto do Local History antes da IA não pôde ser marcado", it) }
                .getOrNull(),
        )

    /** A foto do git antes de a IA começar; fora da EDT, porque o git lê a pasta inteira. */
    private fun snapshot(checkpoint: Checkpoint, task: AgentTask, root: String, state: AgentProgress) {
        state.step = "Guardando o ponto de restauração"
        checkpoint.git = runCatching { GitSnapshot.create(Path.of(root), task.title) }
            .onFailure { LOG.warn("A foto do git antes da IA não pôde ser tirada", it) }
            .getOrNull()
    }

    /**
     * Volta os arquivos do projeto para [checkpoint], marcado antes de [task]. Os registros da IA em `iron-dome/ia/`
     * continuam, para conferir depois o que ela fez.
     */
    private fun undo(checkpoint: Checkpoint, task: AgentTask) {
        val root = project.rootDir() ?: return
        val git = checkpoint.git
        val label = checkpoint.label
        if (git == null && label == null) return
        val confirmed = MessageDialogBuilder.yesNo(
            "Desfazer Alterações da IA",
            "Volta os arquivos do projeto para como estavam antes de \"${task.title}\": o que a IA alterou ou apagou volta, e o que ela " +
                "criou sai.\n\nO que você alterou no projeto depois disso também volta. Os registros da IA em " +
                "${ReviewCommentService.REVIEW_DIR}/$DIR/ ficam.",
        ).yesText("Desfazer").noText("Cancelar").icon(Messages.getWarningIcon()).ask(project)
        if (!confirmed) return
        FileDocumentManager.getInstance().saveAllDocuments()
        val records = transcriptsDir()?.takeIf(Files::isDirectory)?.let { dir ->
            Files.walk(dir).use { files -> files.filter(Files::isRegularFile).toList() }.associateWith(Files::readAllBytes)
        }.orEmpty()
        val keepRecords = {
            records.forEach { (path, bytes) ->
                if (!Files.isRegularFile(path)) {
                    Files.createDirectories(path.parent)
                    Files.write(path, bytes)
                }
            }
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            notify("Alterações da IA desfeitas", "Os arquivos voltaram para antes de \"${task.title}\".", NotificationType.INFORMATION)
        }
        val failed = { error: Throwable ->
            LOG.warn("As alterações da IA não puderam ser desfeitas", error)
            notify(
                "Não deu para desfazer",
                "${error.message ?: "Os arquivos não voltaram."} O ponto de restauração continua no git, em refs/iron-dome/antes-da-ia, " +
                    "e no Local History do projeto, como \"Iron Dome IA: antes de ${task.title}\".",
                NotificationType.WARNING,
            )
        }
        if (git == null) {
            try {
                label!!.revert(project, root)
                keepRecords()
            } catch (e: LocalHistoryException) {
                failed(e)
            }
            return
        }
        object : Task.Backgroundable(project, "Desfazendo as alterações da IA", false) {
            override fun run(indicator: ProgressIndicator) = GitSnapshot.restore(Path.of(root.path), git)

            override fun onSuccess() = keepRecords()

            override fun onThrowable(error: Throwable) = failed(error)
        }.queue()
    }

    /** O PR em revisão pela Patrulha, ou 0. Com ele, o checkout é o do PR: está sendo revisado, não alterado. */
    fun reviewingPullRequest(): Int = project.service<ReviewCommentService>().activePullRequest

    /** Avisa e devolve true se há um PR em revisão, quando a IA não pode alterar o código. */
    fun refusesWrite(): Boolean {
        val number = reviewingPullRequest()
        if (number == 0) return false
        notify(
            "Revisando o PR #$number: a IA não altera o código",
            "O código local é o do PR, que está sendo revisado, não alterado. Saia da revisão na aba Patrulha para a IA corrigir o código.",
            NotificationType.WARNING,
        )
        return true
    }

    private fun finish(
        result: AgentResult,
        actions: Array<out Pair<String, (AgentResult) -> Unit>>,
        unattended: Boolean,
        onDone: (AgentResult) -> Unit,
        checkpoint: Checkpoint? = null,
    ) {
        progress = null
        runCatching { AgentActivity.finished(project) }.onFailure { LOG.warn("O ícone da IA trabalhando não pôde voltar", it) }
        refresh(result)
        if (!unattended || !result.ok) report(result, actions, checkpoint)
        project.messageBus.syncPublisher(RUNS).changed()
        onDone(result)
    }

    private fun failure(task: AgentTask, provider: AgentProvider, error: String) =
        AgentResult(task, provider, "", error, false, emptySet(), emptyList(), null, Duration.ZERO, null)

    /** Nulo se o usuário desistiu na pergunta de onde rodar. */
    /** Na aba ninguém aprova cada passo da IA: antes de deixá-la alterar arquivos, diz o que ela pode fazer sozinha. */
    private fun confirmWrite(task: AgentTask, provider: AgentProvider): Boolean {
        if (!AgentSettings.confirmWrites) return true
        return MessageDialogBuilder.yesNo(
            "A IA Vai Alterar Arquivos",
            "\"${task.title}\" roda na aba, em segundo plano, sem janela para aprovar cada passo: ${AgentCommand.writePermissions(provider)}.\n\n" +
                "No terminal da IDE, a IA pede aprovação a cada passo: escolha onde ela roda no Arsenal.",
        )
            .yesText("Rodar")
            .noText("Cancelar")
            .icon(Messages.getWarningIcon())
            .doNotAsk(object : DoNotAskOption.Adapter() {
                override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                    if (isSelected && exitCode == Messages.YES) AgentSettings.confirmWrites = false
                }

                override fun getDoNotShowMessage() = "Não perguntar de novo (dá para voltar no Arsenal)"
            })
            .ask(project)
    }

    private fun chooseMode(task: AgentTask): AgentRunMode? {
        val mode = AgentSettings.runMode
        if (mode != AgentRunMode.ASK) return mode
        if (AgentTerminal.available() == null) return AgentRunMode.BACKGROUND
        return when (
            Messages.showDialog(
                project,
                "Rodar \"${task.title}\" na aba, em segundo plano, ou no terminal da IDE, onde dá para acompanhar e conversar com a IA?",
                "Onde Rodar a IA",
                arrayOf("Na Aba", "No Terminal", "Cancelar"),
                0,
                Messages.getQuestionIcon(),
            )
        ) {
            0 -> AgentRunMode.BACKGROUND
            1 -> AgentRunMode.TERMINAL
            else -> null
        }
    }

    private fun answerFile(task: AgentTask): Path? {
        val dir = transcriptsDir()?.resolve(ANSWERS_DIR) ?: return null
        Files.createDirectories(dir)
        return dir.resolve("${LocalDateTime.now().format(FILE_TIME)}-${slug(task.title)}.md").also(Files::deleteIfExists)
    }

    /** A IA roda no terminal: a resposta chega quando o arquivo existe e para de crescer. */
    private fun waitForAnswer(task: AgentTask, provider: AgentProvider, file: Path, command: String, state: AgentProgress): AgentResult {
        val started = System.nanoTime()
        val timeout = AgentSettings.timeoutMinutes
        val deadline = started + TimeUnit.MINUTES.toNanos(timeout.toLong())
        var lastSize = -1L
        var error: String? = null
        var answer = ""
        while (true) {
            if (state.cancelled) {
                answer = "Parei de esperar a resposta. A IA continua no terminal: feche a aba do terminal para pará-la."
                break
            }
            if (System.nanoTime() > deadline) {
                error = "A resposta não chegou em $timeout minutos. A IA pode continuar no terminal."
                break
            }
            if (Files.isRegularFile(file)) {
                val size = Files.size(file)
                val quiet = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > ANSWER_QUIET_MS
                if (size > 0 && size == lastSize && quiet) {
                    answer = Files.readString(file).trim()
                    break
                }
                lastSize = size
                state.step = "No terminal: a IA está gravando a resposta"
            }
            Thread.sleep(POLL_MS)
        }
        val duration = Duration.ofNanos(System.nanoTime() - started)
        val partial = AgentResult(task, provider, answer, error, state.cancelled, emptySet(), emptyList(), null, duration, null)
        val transcript = runCatching { writeTranscript(partial, listOf(command), "") }.getOrNull()
        return AgentResult(task, provider, answer, error, state.cancelled, emptySet(), emptyList(), null, duration, transcript)
    }

    private fun execute(task: AgentTask, provider: AgentProvider, launch: AgentLaunch, root: String, state: AgentProgress): AgentResult {
        val started = System.nanoTime()
        val timeout = AgentSettings.timeoutMinutes
        val command = AgentCommand.build(
            provider, launch.executable, task, launch.workspace(root), AgentSettings.model, timeout, AgentCommand.splitArgs(AgentSettings.extraArgs),
            sandbox = launch.sandboxAvailable,
        )
        val key = if (provider == AgentProvider.CURSOR) AgentSettings.cursorApiKey else null
        val roots = listOf(root, launch.workspace(root)).distinct()
        val blocked = AtomicReference<String?>()

        val text = StringBuilder()
        var answer: String? = null
        var failed = false
        var model: String? = null
        val tools = mutableListOf<String>()
        val files = linkedSetOf<String>()
        val stderr = StringBuilder()
        var cancelled = false
        var error: String? = null
        try {
            val process = launch.process(command, root, key, provider).createProcess()
            process.outputStream.close()
            val errors = Thread {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeout.toLong())
            val reader = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { raw ->
                    for (event in AgentEvents.parseAll(raw)) when (event) {
                        is AgentEvent.Init -> model = event.model
                        is AgentEvent.Text -> {
                            text.append(event.text).append('\n')
                            state.detail = event.text.lines().lastOrNull { it.isNotBlank() }?.take(160).orEmpty()
                        }
                        is AgentEvent.Tool -> if (event.started) {
                            AgentGuard.violation(event, roots)?.let { reason ->
                                if (blocked.compareAndSet(null, reason)) kill(process)
                            }
                            val what = listOfNotNull(TOOL_LABELS[event.kind] ?: event.kind, event.path ?: event.command?.take(80)).joinToString(" ")
                            tools += what
                            state.advance(what)
                            if (event.kind in WRITE_TOOLS) event.path?.let(files::add)
                            state.filesTouched = files.size
                        }
                        is AgentEvent.Result -> {
                            answer = event.text
                            failed = event.error
                        }
                        is AgentEvent.Other -> text.append(event.line).append('\n')
                    }
                }
            }.apply { isDaemon = true; start() }
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (state.cancelled) {
                    cancelled = true
                    kill(process)
                    break
                }
                if (System.nanoTime() > deadline) {
                    error = "Passou do limite de $timeout minutos: a execução foi interrompida."
                    kill(process)
                    break
                }
            }
            reader.join(2_000)
            errors.join(1_000)
            blocked.get()?.let {
                error = "Contenção: a IA foi parada ao tentar $it. Confira o que ela já tinha feito e, se precisar, use Desfazer Alterações da IA."
            }
            if (!cancelled && error == null && process.exitValue() != 0) {
                error = AgentVerifier.explain(
                    provider,
                    answer?.takeIf { failed && it.isNotBlank() }
                        ?: synchronized(stderr) { stderr.toString() }.lines().lastOrNull { it.isNotBlank() }
                        ?: "O ${provider.title} terminou com o código ${process.exitValue()}.",
                )
            }
            if (!cancelled && error == null && failed) error = AgentVerifier.explain(provider, answer?.takeIf { it.isNotBlank() } ?: "A IA informou um erro.")
        } catch (e: Exception) {
            LOG.warn("Falha ao rodar ${command.firstOrNull()}", e)
            error = "Não foi possível rodar ${launch.description}: ${e.message ?: e.javaClass.simpleName}"
        }
        val finalAnswer = answer?.takeIf { it.isNotBlank() } ?: text.toString().trim()
        val duration = Duration.ofNanos(System.nanoTime() - started)
        val partial = AgentResult(task, provider, finalAnswer, error, cancelled, files, tools, model, duration, null)
        val transcript = runCatching { writeTranscript(partial, command, synchronized(stderr) { stderr.toString() }) }.getOrNull()
        return AgentResult(task, provider, finalAnswer, error, cancelled, files, tools, model, duration, transcript)
    }

    /** O CLI e tudo o que ele abriu: no Windows, o `agent.ps1` do Cursor roda o Node num processo filho. */
    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Os arquivos que a IA mexeu aparecem atualizados no editor, e os `.md` de `iron-dome/` disparam as abas. */
    private fun refresh(result: AgentResult) {
        val base = project.rootPath()?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
        VfsUtil.markDirtyAndRefresh(true, true, true, base)
    }

    private fun report(result: AgentResult, actions: Array<out Pair<String, (AgentResult) -> Unit>>, checkpoint: Checkpoint?) {
        val title = when {
            result.cancelled -> "IA: ${result.task.title} cancelada"
            !result.ok -> "IA: ${result.task.title} falhou"
            else -> "IA: ${result.task.title} concluída"
        }
        val summary = buildString {
            if (result.error != null) append(result.error).append(' ')
            if (result.files.isNotEmpty()) append("${result.files.size} arquivo(s) alterado(s). ")
            append(result.answer.lineSequence().firstOrNull { it.isNotBlank() }?.take(240).orEmpty())
        }.trim().ifEmpty { "Sem resposta." }
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, escape(summary), if (result.ok) NotificationType.INFORMATION else NotificationType.WARNING)
            .setIcon(IronDomeIcons.arsenal)
        result.transcript?.let { path ->
            notification.addAction(NotificationAction.createSimple("Ver Resposta") { open(path) })
        }
        if (result.ok) actions.forEach { (label, action) -> notification.addAction(NotificationAction.createSimpleExpiring(label) { action(result) }) }
        checkpoint?.takeIf { it.git != null || it.label != null }?.let { point ->
            notification.addAction(NotificationAction.createSimpleExpiring("Desfazer Alterações da IA") { undo(point, result.task) })
        }
        notification.notify(project)
    }

    fun open(path: Path) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun writeTranscript(result: AgentResult, command: List<String>, stderr: String): Path? {
        val dir = transcriptsDir() ?: return null
        Files.createDirectories(dir)
        val name = "${LocalDateTime.now().format(FILE_TIME)}-${slug(result.task.title)}.md"
        val file = dir.resolve(name)
        Files.writeString(file, AgentTranscript.render(result, command, stderr))
        return file
    }

    fun transcriptsDir(): Path? = project.rootPath()?.let { Path.of(it, ReviewCommentService.REVIEW_DIR, DIR) }

    /** Os registros das execuções, do mais recente para o mais antigo. */
    fun transcripts(): List<Path> {
        val dir = transcriptsDir()?.takeIf(Files::isDirectory) ?: return emptyList()
        return Files.list(dir).use { files -> files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.toList() }
            .sortedByDescending { it.fileName.toString() }
    }

    /**
     * Grava instruções longas num arquivo que o prompt manda ler, em `iron-dome/ia/pedidos/`; devolve o
     * caminho relativo ao projeto.
     */
    fun writeInstructions(name: String, content: String): String? {
        val dir = transcriptsDir()?.resolve(REQUESTS_DIR) ?: return null
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), content)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(dir.resolve(name)))
        return "${ReviewCommentService.REVIEW_DIR}/$DIR/$REQUESTS_DIR/$name"
    }

    private fun notifyNotReady(provider: AgentProvider, missing: Boolean) {
        val (title, content) = if (missing) {
            "CLI do ${provider.title} não encontrado" to provider.installHint
        } else {
            "Teste a integração com a IA" to "Os botões de IA só funcionam depois que a integração é testada com a configuração atual."
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, content, NotificationType.WARNING)
            .setIcon(IronDomeIcons.arsenal)
            .addAction(NotificationAction.createSimpleExpiring("Abrir Arsenal") { InterceptorTab.show(project) })
            .addAction(NotificationAction.createSimpleExpiring("Configurações") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AgentConfigurable::class.java)
            })
            .notify(project)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, escape(content), type)
            .setIcon(IronDomeIcons.arsenal)
            .notify(project)
    }

    fun interface Listener {
        fun changed()
    }

    companion object {
        const val DIR = "ia"
        private const val REQUESTS_DIR = "pedidos"
        private const val ANSWERS_DIR = "respostas"
        private const val POLL_MS = 1_000L
        private const val ANSWER_QUIET_MS = 1_500L

        /** Uma execução começou ou terminou. */
        @Topic.ProjectLevel
        val RUNS = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        private val LOG = logger<AgentRunner>()
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val WRITE_TOOLS = setOf("write", "edit", "multiEdit", "delete", "applyPatch")
        private val TOOL_LABELS = mapOf(
            "read" to "Lendo",
            "write" to "Escrevendo",
            "edit" to "Editando",
            "multiEdit" to "Editando",
            "delete" to "Apagando",
            "grep" to "Buscando",
            "glob" to "Procurando arquivos",
            "ls" to "Listando",
            "shell" to "Rodando comando",
        )

        fun getInstance(project: Project): AgentRunner = project.service()

        private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "tarefa" }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** O registro de uma execução: o pedido, o que a IA fez e a resposta, para conferir depois. */
object AgentTranscript {

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /** O título e a situação do registro, das primeiras linhas, para a lista de execuções. */
    fun summary(lines: List<String>): Pair<String?, String?> {
        val title = lines.firstOrNull { it.startsWith("# IA: ") }?.removePrefix("# IA: ")?.trim()
        val status = lines.firstOrNull { it.startsWith("- **Situação:** ") }?.removePrefix("- **Situação:** ")?.trim()
        return title to status
    }

    fun render(result: AgentResult, command: List<String>, stderr: String, now: LocalDateTime = LocalDateTime.now()): String = buildString {
        appendLine("# IA: ${result.task.title}")
        appendLine()
        appendLine("- **Quando:** ${now.format(DATE)}, em ${result.duration.toSeconds()} s")
        appendLine("- **CLI:** ${result.provider.title}${result.model?.let { " · modelo $it" }.orEmpty()}")
        appendLine("- **Pode alterar arquivos:** ${if (result.task.write) "sim" else "não"}")
        appendLine(
            "- **Situação:** " + when {
                result.cancelled -> "cancelada"
                result.error != null -> "falhou: ${result.error}"
                else -> "concluída"
            },
        )
        appendLine()
        appendLine("## Pedido")
        appendLine()
        appendLine(result.task.prompt)
        if (result.files.isNotEmpty()) {
            appendLine()
            appendLine("## Arquivos alterados")
            appendLine()
            result.files.forEach { appendLine("- `$it`") }
        }
        if (result.tools.isNotEmpty()) {
            appendLine()
            appendLine("## Passos")
            appendLine()
            result.tools.forEach { appendLine("1. $it") }
        }
        appendLine()
        appendLine("## Resposta")
        appendLine()
        appendLine(result.answer.ifBlank { "_Sem resposta._" })
        if (stderr.isNotBlank() && !result.ok) {
            appendLine()
            appendLine("## Saída de erro do CLI")
            appendLine()
            appendLine("```text")
            appendLine(stderr.trimEnd().lines().takeLast(40).joinToString("\n"))
            appendLine("```")
        }
        appendLine()
        appendLine("<!-- comando: ${command.joinToString(" ").replace("--", "- -")} -->")
    }
}
