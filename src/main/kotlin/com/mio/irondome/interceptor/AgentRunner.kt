package com.mio.irondome.interceptor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
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
 * Roda o CLI de IA configurado sobre o projeto, em segundo plano, com o andamento na barra de progresso e
 * cancelamento. Ao terminar, grava o registro, atualiza os arquivos que a IA mexeu e avisa.
 */
@Service(Service.Level.PROJECT)
class AgentRunner(private val project: Project) {

    /** Um por vez por projeto: dois agentes escrevendo nos mesmos arquivos se atropelam. */
    @Volatile
    var running: AgentTask? = null
        private set

    /** Configurada e testada: só assim os botões de IA se habilitam. Sem isso, quem chama cai no fluxo manual. */
    fun available(): Boolean = AgentSettings.ready

    /**
     * [onDone] roda na EDT com o resultado, inclusive em erro ou cancelamento; [actions] entram no aviso de
     * sucesso, além de Ver Resposta.
     */
    fun run(task: AgentTask, vararg actions: Pair<String, (AgentResult) -> Unit>, onDone: (AgentResult) -> Unit = {}) {
        val provider = AgentSettings.provider
        val executable = AgentSettings.resolveExecutable()
        if (executable == null || !AgentSettings.ready) {
            notifyNotReady(provider, executable == null)
            return
        }
        if (running != null) {
            notify("A IA já está trabalhando", "Espere ${running?.title} terminar ou cancele na barra de progresso.", NotificationType.WARNING)
            return
        }
        val root = project.basePath ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        running = task
        project.messageBus.syncPublisher(RUNS).changed()
        object : Task.Backgroundable(project, "IA (${provider.title}): ${task.title}", true) {
            private var result: AgentResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = execute(task, provider, executable, root, indicator)
            }

            override fun onFinished() {
                running = null
                val done = result ?: AgentResult(task, provider, "", "A execução não terminou.", true, emptySet(), emptyList(), null, Duration.ZERO, null)
                refresh(done)
                report(done, actions)
                project.messageBus.syncPublisher(RUNS).changed()
                onDone(done)
            }
        }.queue()
    }

    private fun execute(task: AgentTask, provider: AgentProvider, executable: Path, root: String, indicator: ProgressIndicator): AgentResult {
        val started = System.nanoTime()
        val timeout = AgentSettings.timeoutMinutes
        val command = AgentCommand.build(provider, executable.toString(), task, root, AgentSettings.model, timeout)
        val line = GeneralCommandLine(command)
            .withWorkDirectory(root)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        if (provider == AgentProvider.CURSOR) AgentSettings.cursorApiKey?.let { line.withEnvironment("CURSOR_API_KEY", it) }

        val text = StringBuilder()
        var answer: String? = null
        var failed = false
        var model: String? = null
        val tools = mutableListOf<String>()
        val files = linkedSetOf<String>()
        val stderr = StringBuilder()
        var cancelled = false
        var error: String? = null
        indicator.isIndeterminate = true
        indicator.text = "Iniciando ${provider.title}…"
        try {
            val process = line.createProcess()
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
                            indicator.text2 = event.text.lines().lastOrNull { it.isNotBlank() }?.take(120).orEmpty()
                        }
                        is AgentEvent.Tool -> if (event.started) {
                            val what = listOfNotNull(TOOL_LABELS[event.kind] ?: event.kind, event.path).joinToString(" ")
                            tools += what
                            indicator.text = what
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
                if (indicator.isCanceled) {
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
        } catch (e: ProcessCanceledException) {
            cancelled = true
        } catch (e: Exception) {
            LOG.warn("Falha ao rodar ${command.firstOrNull()}", e)
            error = "Não foi possível rodar ${executable.fileName}: ${e.message ?: e.javaClass.simpleName}"
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
