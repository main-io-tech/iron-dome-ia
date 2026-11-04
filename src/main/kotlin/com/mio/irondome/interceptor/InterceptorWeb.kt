package com.mio.irondome.interceptor

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.mio.irondome.base.web.WebTab
import com.mio.irondome.base.web.WebUi
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path

/**
 * O Interceptador no navegador embutido, com o visual do Arsenal do Tomahawk IA: configuração automática, os campos
 * do CLI (onde está, executável, modelo, tempo, argumentos extras e a chave do Cursor), Salvar e Testar (sem o teste
 * os botões de IA ficam desligados), onde rodar a IA com o teste pelo terminal, o que a IA libera e as execuções.
 */
class InterceptorWeb(project: Project) : WebTab(project) {

    override val accent = "teal"

    /** O que está nos campos e ainda não foi salvo. */
    private data class Draft(
        val provider: AgentProvider,
        val host: AgentHost,
        val distro: String?,
        val executable: String,
        val model: String,
        val timeout: Int,
        val extra: String,
    ) {
        companion object {
            fun current() = Draft(
                AgentSettings.provider,
                AgentSettings.host,
                AgentSettings.wslDistro,
                AgentSettings.executable.orEmpty(),
                AgentSettings.model.orEmpty(),
                AgentSettings.timeoutMinutes,
                AgentSettings.extraArgs.orEmpty(),
            )
        }
    }

    private class Status(val text: String, val ok: Boolean?)

    private class Run(val path: Path, val title: String, val status: String, val ok: Boolean)

    private val runner = AgentRunner.getInstance(project)
    private var draft = Draft.current()
    private var saving = false
    private var distros: List<String> = emptyList()
    private val wslFound = mutableMapOf<String, String?>()
    private var runs: List<Run> = emptyList()
    private var testStatus: Status? = null
    private var autoStatus: Status? = null
    private var autoFailure: String? = null
    private var terminalStatus: Status? = null

    init {
        add(AgentProgressPanel(project, InterceptorTab.TITLE, this), BorderLayout.NORTH)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AgentSettings.Listener.TOPIC,
            AgentSettings.Listener {
                ApplicationManager.getApplication().invokeLater({
                    if (!saving && !AgentAutoSetup.running) draft = Draft.current()
                    refresh()
                }, project.disposed)
            },
        )
        project.messageBus.connect(this).subscribe(AgentRunner.RUNS, AgentRunner.Listener {
            ApplicationManager.getApplication().invokeLater({ loadRuns() }, project.disposed)
        })
        if (AgentLaunch.wslSupported) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val names = AgentLaunch.distributions()
                ApplicationManager.getApplication().invokeLater({
                    distros = names
                    if (draft.distro == null && names.isNotEmpty()) draft = draft.copy(distro = names.first())
                    refresh()
                }, project.disposed)
            }
        }
        loadRuns()
        refresh()
        if (!AgentSettings.ready && !AgentSettings.autoSetupTried && AgentSettings.untouched) {
            ApplicationManager.getApplication().invokeLater({ autoSetup() }, project.disposed)
        }
    }

    private fun loadRuns() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = runner.transcripts().take(InterceptorPanel.MAX_RUNS).map { path ->
                val (title, status) = AgentTranscript.summary(runCatching { Files.readAllLines(path).take(12) }.getOrDefault(emptyList()))
                Run(path, title ?: path.fileName.toString(), status.orEmpty(), status == "concluída")
            }
            ApplicationManager.getApplication().invokeLater({
                runs = loaded
                refresh()
            }, project.disposed)
        }
    }

    override fun body(): String = buildString {
        val ready = AgentSettings.ready
        val launch = AgentSettings.launch()
        val busy = AgentAutoSetup.running || AgentVerifier.testing
        val working = runner.running
        val (title, badge) = when {
            AgentAutoSetup.running -> "Configurando a IA…" to "<span class=\"status-big warn\">⏳ configurando</span>"
            AgentVerifier.testing -> "Testando a integração…" to "<span class=\"status-big warn\">⏳ testando</span>"
            launch == null -> "IA não configurada" to "<span class=\"status-big bad\">○ não configurada</span>"
            !ready -> "Teste a integração" to "<span class=\"status-big warn\">⚠ falta testar</span>"
            working != null -> "IA trabalhando: ${esc(working.title)}" to "<span class=\"status-big good\">⚡ trabalhando</span>"
            else -> "IA pronta para interceptar" to "<span class=\"status-big good\">✓ pronta</span>"
        }
        val subtitle = when {
            launch == null -> esc(AgentSettings.provider.installHint)
            else -> esc("${AgentSettings.provider.title} CLI${launch.where} · ${AgentSettings.model?.let { "modelo $it" } ?: "modelo padrão"}") +
                (AgentSettings.verifiedAt?.let { " · testada em ${esc(it)}" } ?: "")
        }
        append(WebUi.header("🤖", "Interceptador · configuração da IA", title, subtitle, side = badge))
        append("<div class=\"grid2\" style=\"margin-top:14px;align-items:start\"><div>")
        append(autoCard(busy))
        append(cliCard(busy))
        append("</div><div>")
        append(runCard())
        append(capsCard(ready))
        append(runsCard())
        append("</div></div>")
    }

    private fun autoCard(busy: Boolean): String = buildString {
        append("<div class=\"card2\"><h3>⚡ Configuração automática</h3>")
        append("<div class=\"desc\">Procura o ").append(esc(AgentProvider.entries.joinToString(", ") { "${it.title} CLI" })).append(" instalados")
        if (AgentLaunch.wslSupported) append(", nesta máquina e no WSL,")
        append(" e testa cada um, com o modelo padrão, até um responder.</div>")
        append(WebUi.button(if (AgentAutoSetup.running) "⏳ Configurando…" else "✨ Configurar automaticamente", "auto", "primary", enabled = !busy))
        status(autoStatus)
        autoFailure?.let { append(WebUi.notice("warn", "⚠️", esc(it))) }
        append("</div>")
    }

    private fun cliCard(busy: Boolean): String = buildString {
        val d = draft
        append("<div class=\"card2\" data-form=\"cli\"><h3>🛰️ CLI de IA</h3>")
        append("<div class=\"desc\">O agente que executa as ordens de interceptação, as revisões e as análises sobre o projeto aberto.</div>")
        append("<div class=\"field\"><label>CLI</label>")
        append(WebUi.seg("provider", AgentProvider.entries.map { it.name to "${esc(it.title)} <span class=\"muted\">(${esc(it.executables.first())})</span>" }, d.provider.name, send = "form"))
        append("</div>")
        if (AgentLaunch.wslSupported) {
            append("<div class=\"field\"><label>Onde está o CLI</label>")
            append(WebUi.seg("host", AgentHost.entries.map { it.name to esc(it.label) }, d.host.name, send = "form")).append("</div>")
            if (d.host == AgentHost.WSL) {
                append("<div class=\"field\"><label>Distribuição do WSL</label><select class=\"in\" name=\"distro\" data-change=\"form\">")
                (distros + listOfNotNull(d.distro)).distinct().forEach { name ->
                    append("<option value=\"").append(WebUi.attr(name)).append("\"").append(if (name == d.distro) " selected" else "").append(">").append(esc(name)).append("</option>")
                }
                if (distros.isEmpty() && d.distro == null) append("<option value=\"\">Procurando distribuições…</option>")
                append("</select></div>")
            }
        } else {
            append("<input type=\"hidden\" name=\"host\" value=\"LOCAL\">")
        }
        append("<div class=\"field\"><label>Executável</label><div class=\"in-row\">")
        append("<input class=\"in\" name=\"executable\" data-change=\"form\" value=\"").append(WebUi.attr(d.executable))
        append("\" placeholder=\"").append(if (d.host == AgentHost.WSL) "Vazio usa ${d.provider.executables.first()} do PATH do WSL" else "Vazio procura sozinho").append("\">")
        if (d.host == AgentHost.LOCAL) append(WebUi.button("📂 Procurar…", "browse", "ghost"))
        append("</div>").append(foundHint(d)).append("</div>")
        append("<div class=\"cols\">")
        append("<div class=\"field\"><label>Modelo</label><input class=\"in\" name=\"model\" value=\"").append(WebUi.attr(d.model)).append("\" placeholder=\"Padrão do CLI\">")
        append("<span class=\"hint\">Como no --model do CLI.</span></div>")
        append("<div class=\"field\"><label>Tempo máximo (min)</label><input class=\"in\" type=\"number\" min=\"1\" max=\"${AgentSettings.MAX_TIMEOUT_MINUTES}\" name=\"timeout\" value=\"${d.timeout}\">")
        append("<span class=\"hint\">Uma tarefa que passar disso é interrompida.</span></div>")
        append("</div>")
        append("<div class=\"field\"><label>Argumentos extras</label><input class=\"in\" name=\"extra\" value=\"").append(WebUi.attr(d.extra)).append("\" placeholder=\"Ex.: --approve-mcps\">")
        append("<span class=\"hint\">Vão para o CLI antes do pedido, separados por espaço; aspas agrupam.</span></div>")
        if (d.provider == AgentProvider.CURSOR) {
            append("<div class=\"field\"><label>Chave de API do Cursor</label><input class=\"in\" type=\"password\" name=\"key\" autocomplete=\"off\" placeholder=\"")
            append(if (AgentSettings.hasCursorApiKey) "•••••••• salva no cofre de senhas (deixe vazio para manter)" else "Opcional: sem ela, vale o agent login").append("\">")
            append("<span class=\"hint\">Crie em cursor.com/dashboard › Integrations. Fica no cofre de senhas da IDE.</span>")
            if (AgentSettings.hasCursorApiKey) append("<div>").append(WebUi.button("Remover chave salva", "clearKey", "ghost")).append("</div>")
            append("</div>")
        }
        if (d.provider == AgentProvider.ANTIGRAVITY) {
            append(WebUi.notice("warn", "⚠️", "O <code>agy -p</code> pode travar quando chamado por outro programa (issue 318 do antigravity-cli); o tempo máximo interrompe."))
        }
        append(
            WebUi.notice(
                "info",
                "🔐",
                "Nas tarefas que alteram arquivos, como Disparar com IA, o Cursor roda com <code>--force</code>, o Claude Code com " +
                    "<code>--permission-mode bypassPermissions</code> e o Antigravity com <code>--dangerously-skip-permissions</code>: sem janela, " +
                    "não há quem aprove. Nas de leitura, como revisões e explicações, nenhum deles recebe permissão para escrever.",
            ),
        )
        append("<div class=\"row-btns\" style=\"margin-top:12px\">")
        append(WebUi.button(if (AgentVerifier.testing) "⏳ Testando…" else "✔ Salvar e testar", "save", "primary big", arg = "test", enabled = !busy))
        append(WebUi.button("Salvar", "save", enabled = !busy))
        append("</div>")
        status(testStatus ?: AgentSettings.verifiedAt?.takeIf { AgentSettings.ready }?.let { Status("Testada em $it.", true) })
        append("</div>")
    }

    private fun foundHint(d: Draft): String {
        if (d.host == AgentHost.WSL) {
            val distro = d.distro ?: return "<span class=\"hint bad\">Escolha a distribuição do WSL.</span>"
            if (d.executable.isNotBlank()) return "<span class=\"hint\">No WSL ${esc(distro)}: ${esc(d.executable)}</span>"
            val key = "${d.provider.name}|$distro"
            if (key !in wslFound) {
                wslFound[key] = null
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = AgentLaunch.findInWsl(d.provider, distro)
                    ApplicationManager.getApplication().invokeLater({
                        wslFound[key] = path ?: ""
                        refresh()
                    }, project.disposed)
                }
            }
            val found = wslFound[key]
            return when {
                found == null -> "<span class=\"hint\">Procurando o ${esc(d.provider.title)} CLI no WSL ${esc(distro)}…</span>"
                found.isEmpty() -> "<span class=\"hint bad\">Não encontrado no WSL ${esc(distro)}. ${esc(d.provider.installHint)}</span>"
                else -> "<span class=\"hint good\">✓ Encontrado: WSL ${esc(distro)}: ${esc(found)}</span>"
            }
        }
        val path = AgentSettings.resolveExecutable(d.provider, d.executable.ifBlank { null })
        return if (path != null) "<span class=\"hint good\">✓ Encontrado: ${esc(path.toString())}</span>"
        else "<span class=\"hint bad\">Não encontrado. ${esc(d.provider.installHint)}</span>"
    }

    private fun runCard(): String = buildString {
        val terminal = AgentTerminal.available() != null
        append("<div class=\"card2\"><h3>🎛️ Execução</h3><div class=\"desc\">Onde a IA roda quando um botão a chama.</div>")
        append("<div class=\"field\"><label>Onde rodar a IA</label>")
        append(WebUi.seg("runMode", AgentRunMode.entries.map { it.name to esc(it.label) }, AgentSettings.runMode.name, send = "runmode")).append("</div>")
        append("<div class=\"desc\">").append(esc(InterceptorPanel.RUN_MODE_HINT)).append("</div>")
        append(
            WebUi.button(
                if (AgentVerifier.testingTerminal) "⏳ Esperando o terminal…" else "💻 Testar no terminal",
                "terminal",
                enabled = terminal && !AgentVerifier.testingTerminal && AgentSettings.launch() != null,
                title = if (terminal) "Abre o terminal da IDE e roda o CLI com a configuração salva" else "O plugin de terminal da IDE está desligado",
            ),
        )
        status(terminalStatus ?: AgentSettings.terminalVerifiedAt?.let { Status("Terminal testado em $it.", true) })
        append("</div>")
    }

    private fun capsCard(ready: Boolean): String = buildString {
        append("<div class=\"card2\"><h3>✨ O que a IA libera</h3><ul class=\"caps\">")
        CAPABILITIES.forEach { (where, what) ->
            append("<li><span class=\"ck ").append(if (ready) "on\">✓" else "off\">○").append("</span><span><b>")
                .append(esc(where)).append("</b>: ").append(esc(what)).append("</span></li>")
        }
        append("</ul>")
        if (!ready) append("<div class=\"status-line\">Desligadas até a integração ser testada com a configuração atual.</div>")
        append("</div>")
    }

    private fun runsCard(): String = buildString {
        append("<div class=\"card2\"><h3>📜 Execuções recentes</h3>")
        append("<div class=\"desc\">O pedido, os passos, os arquivos alterados e a resposta de cada execução, em iron-dome/ia/.</div>")
        if (runs.isEmpty()) {
            append("<div class=\"empty2\">Nenhuma execução da IA neste projeto ainda</div>")
        } else {
            runs.forEachIndexed { index, run ->
                append("<div class=\"run ").append(if (run.ok) "r-ok" else "r-failed").append("\" data-cmd=\"openRun\" data-arg=\"$index\">")
                append("<div class=\"run-icon\">").append(if (run.ok) "✓" else "!").append("</div><div class=\"run-body\">")
                append("<div class=\"run-title\">").append(esc(run.title)).append("</div>")
                append("<div class=\"run-meta\"><span>").append(esc(run.status)).append("</span><span>")
                    .append(esc(run.path.fileName.toString().take(15))).append("</span></div>")
                append("</div></div>")
            }
        }
        append("</div>")
    }

    private fun StringBuilder.status(status: Status?) {
        status ?: return
        val tone = when (status.ok) {
            true -> " good"
            false -> " bad"
            null -> ""
        }
        append("<div class=\"status-line$tone\">").append(esc(status.text)).append("</div>")
    }

    private fun readForm(data: JsonObject) {
        draft = draft.copy(
            provider = AgentProvider.entries.firstOrNull { it.name == data.str("provider") } ?: draft.provider,
            host = AgentHost.entries.firstOrNull { it.name == data.str("host") } ?: draft.host,
            distro = data.str("distro")?.ifBlank { null } ?: draft.distro,
            executable = data.str("executable") ?: draft.executable,
            model = data.str("model") ?: draft.model,
            timeout = data.int("timeout") ?: draft.timeout,
            extra = data.str("extra") ?: draft.extra,
        )
    }

    private fun save(key: String?, test: Boolean) {
        saving = true
        val d = draft
        AgentSettings.provider = d.provider
        AgentSettings.host = if (AgentLaunch.wslSupported) d.host else AgentHost.LOCAL
        AgentSettings.wslDistro = d.distro
        AgentSettings.executable = d.executable
        AgentSettings.model = d.model
        AgentSettings.timeoutMinutes = d.timeout
        AgentSettings.extraArgs = d.extra
        testStatus = Status(if (test) "Testando o ${d.provider.title}…" else "Salvo.", if (test) null else true)
        refresh(force = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!key.isNullOrBlank()) AgentSettings.cursorApiKey = key
            ApplicationManager.getApplication().invokeLater({
                saving = false
                draft = Draft.current()
                if (!test) return@invokeLater refresh(force = true)
                AgentVerifier.test { outcome ->
                    testStatus = Status(outcome.message, outcome.ok)
                    refresh(force = true)
                }
                refresh(force = true)
            }, project.disposed)
        }
    }

    private fun autoSetup() {
        autoFailure = null
        AgentAutoSetup.run(
            onProgress = { message ->
                autoStatus = Status(message, null)
                refresh(force = true)
            },
            onDone = { result ->
                draft = Draft.current()
                autoStatus = Status(if (result.ok) result.message else "Não deu para configurar sozinho: termine nos campos do CLI.", result.ok)
                autoFailure = result.message.takeUnless { result.ok }
                testStatus = null
                refresh(force = true)
            },
        )
        refresh(force = true)
    }

    override fun onMessage(command: String, data: JsonObject) {
        when (command) {
            "form" -> {
                readForm(data)
                refresh(force = true)
            }
            "browse" -> {
                val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA")
                FileChooser.chooseFile(descriptor, project, null)?.let {
                    readForm(data)
                    draft = draft.copy(executable = it.path)
                    refresh(force = true)
                }
            }
            "save" -> {
                readForm(data)
                save(data.str("key"), test = data.str("arg") == "test")
            }
            "clearKey" -> ApplicationManager.getApplication().executeOnPooledThread { AgentSettings.cursorApiKey = null }
            "auto" -> autoSetup()
            "runmode" -> AgentRunMode.entries.firstOrNull { it.name == data.str("value") }?.let { AgentSettings.runMode = it }
            "terminal" -> {
                terminalStatus = Status("Abrindo o terminal e esperando a IA gravar OK…", null)
                refresh(force = true)
                AgentVerifier.testTerminal(project) { outcome ->
                    terminalStatus = Status(outcome.message, outcome.ok)
                    refresh(force = true)
                }
            }
            "openRun" -> data.str("arg")?.toIntOrNull()?.let(runs::getOrNull)?.let { runner.open(it.path) }
        }
    }

    private companion object {
        val CAPABILITIES = listOf(
            "Radar e barra do editor" to "Disparar Todos com IA, ou Disparar Selecionados e Disparar no cartão do alvo: a IA executa a ordem de interceptação e marca os alvos atendidos",
            "Patrulha" to "Revisar com IA (no modo revisão): comentários da IA como rascunhos do PR",
            "Escudo" to "Explicar Falha com IA e, na resposta, Corrigir com IA",
            "Raio de Impacto" to "Analisar Impacto com IA: as classes que os comentários e as alterações afetam",
            "Caixa-Preta" to "Analisar com IA, no bug gerado e em Ver Logs de Erro",
        )
    }
}
