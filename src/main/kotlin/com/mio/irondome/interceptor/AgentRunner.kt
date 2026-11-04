package com.mio.irondome.interceptor

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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.messages.Topic
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.radar.ReviewCommentService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
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
     * sucesso, além de Ver Resposta.
     */
    fun run(request: AgentTask, vararg actions: Pair<String, (AgentResult) -> Unit>, onDone: (AgentResult) -> Unit = {}) {
        if (request.write && refusesWrite()) return
        val task = request.copy(prompt = AgentCommand.guarded(request))
        val provider = AgentSettings.provider
        val launch = AgentSettings.launch()
        if (launch == null || !AgentSettings.ready) {
            notifyNotReady(provider, launch == null)
            return
        }
        if (running != null) {
            notify("A IA já está trabalhando", "Espere ${running?.title} terminar ou cancele na barra de progresso da aba.", NotificationType.WARNING)
            return
        }
        val root = project.basePath ?: return
        val terminal = when (chooseMode(task)) {
            null -> return
            AgentRunMode.TERMINAL -> AgentTerminal.available() ?: run {
                notify("Terminal indisponível", "O plugin de terminal da IDE está desligado: a IA roda na aba.", NotificationType.WARNING)
                null
            }
            else -> null
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        val current = AgentProgress(task, provider, inTerminal = terminal != null)
        progress = current
        // Um problema só no ícone não pode deixar a IA presa como trabalhando.
        runCatching { AgentActivity.started(project, task) }.onFailure { LOG.warn("O ícone da IA trabalhando não pôde ser trocado", it) }
        project.messageBus.syncPublisher(RUNS).changed()
        val work: () -> AgentResult = if (terminal == null) {
            { execute(task, provider, launch, root, current) }
        } else {
            val answer = answerFile(task) ?: return finish(failure(task, provider, "O projeto não tem pasta no disco."), actions, onDone)
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
                work()
            } catch (e: Throwable) {
                LOG.warn("A execução da IA falhou", e)
                failure(task, provider, e.message ?: "A execução não terminou.")
            }
            ApplicationManager.getApplication().invokeLater({ finish(result, actions, onDone) }, project.disposed)
        }
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

    private fun finish(result: AgentResult, actions: Array<out Pair<String, (AgentResult) -> Unit>>, onDone: (AgentResult) -> Unit) {
        progress = null
        runCatching { AgentActivity.finished(project) }.onFailure { LOG.warn("O ícone da IA trabalhando não pôde voltar", it) }
        refresh(result)
        report(result, actions)
        project.messageBus.syncPublisher(RUNS).changed()
        onDone(result)
    }

    private fun failure(task: AgentTask, provider: AgentProvider, error: String) =
        AgentResult(task, provider, "", error, false, emptySet(), emptyList(), null, Duration.ZERO, null)

    /** Nulo se o usuário desistiu na pergunta de onde rodar. */
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
        val command = AgentCommand.build(provider, launch.executable, task, launch.workspace(root), AgentSettings.model, timeout)
        val key = if (provider == AgentProvider.CURSOR) AgentSettings.cursorApiKey else null

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
            val process = launch.process(command, root, key).createProcess()
            process.outputStream.close()
            val errors = Thread {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeout.toLong())
            val reader = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { raw ->
                    when (val event = AgentEvents.parse(raw)) {
                        is AgentEvent.Init -> model = event.model
                        is AgentEvent.Text -> {
                            text.append(event.text).append('\n')
                            state.detail = event.text.lines().lastOrNull { it.isNotBlank() }?.take(160).orEmpty()
                        }
                        is AgentEvent.Tool -> if (event.started) {
                            val what = listOfNotNull(TOOL_LABELS[event.kind] ?: event.kind, event.path).joinToString(" ")
                            tools += what
                            state.step = what
                            if (event.kind in WRITE_TOOLS) event.path?.let(files::add)
                        }
                        is AgentEvent.Result -> {
                            answer = event.text
                            failed = event.error
                        }
                        is AgentEvent.Other -> text.append(event.line).append('\n')
                        null -> Unit
                    }
                }
            }.apply { isDaemon = true; start() }
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (state.cancelled) {
                    cancelled = true
                    process.destroyForcibly()
                    break
                }
                if (System.nanoTime() > deadline) {
                    error = "Passou do limite de $timeout minutos: a execução foi interrompida."
                    process.destroyForcibly()
                    break
                }
            }
            reader.join(2_000)
            errors.join(1_000)
            if (!cancelled && error == null && process.exitValue() != 0) {
                error = synchronized(stderr) { stderr.toString() }.lines().lastOrNull { it.isNotBlank() }
                    ?: "O ${provider.title} terminou com o código ${process.exitValue()}."
            }
            if (!cancelled && error == null && failed) error = answer?.takeIf { it.isNotBlank() } ?: "A IA informou um erro."
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

    /** Os arquivos que a IA mexeu aparecem atualizados no editor, e os `.md` de `iron-dome/` disparam as abas. */
    private fun refresh(result: AgentResult) {
        val base = project.basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
        VfsUtil.markDirtyAndRefresh(true, true, true, base)
    }

    private fun report(result: AgentResult, actions: Array<out Pair<String, (AgentResult) -> Unit>>) {
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
            .setIcon(IronDomeIcons.interceptador)
        result.transcript?.let { path ->
            notification.addAction(NotificationAction.createSimple("Ver Resposta") { open(path) })
        }
        if (result.ok) actions.forEach { (label, action) -> notification.addAction(NotificationAction.createSimpleExpiring(label) { action(result) }) }
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

    fun transcriptsDir(): Path? = project.basePath?.let { Path.of(it, ReviewCommentService.REVIEW_DIR, DIR) }

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
            .setIcon(IronDomeIcons.interceptador)
            .addAction(NotificationAction.createSimpleExpiring("Abrir Interceptador") { InterceptorTab.show(project) })
            .addAction(NotificationAction.createSimpleExpiring("Configurações") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AgentConfigurable::class.java)
            })
            .notify(project)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA")
            .createNotification(title, escape(content), type)
            .setIcon(IronDomeIcons.interceptador)
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
