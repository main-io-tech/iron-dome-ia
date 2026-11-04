package com.mio.irondome.interceptor

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.messages.Topic
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Qual CLI de IA o Interceptador usa e como. A chave de API do Cursor fica no cofre de senhas da IDE; o resto, nas
 * preferências. Sem caminho configurado, o executável é procurado no PATH e nas pastas de instalação.
 */
object AgentSettings {

    private const val PROVIDER_KEY = "IronDome.Agent.Provider"
    private const val EXECUTABLE_KEY = "IronDome.Agent.Executable"
    private const val MODEL_KEY = "IronDome.Agent.Model"
    private const val TIMEOUT_KEY = "IronDome.Agent.TimeoutMinutes"
    private const val KEY_VERSION_KEY = "IronDome.Agent.KeyVersion"
    private const val VERIFIED_KEY = "IronDome.Agent.Verified"
    private const val VERIFIED_AT_KEY = "IronDome.Agent.VerifiedAt"
    private const val AUTO_SETUP_KEY = "IronDome.Agent.AutoSetupTried"
    private const val RUN_MODE_KEY = "IronDome.Agent.RunMode"
    private const val HOST_KEY = "IronDome.Agent.Host"
    private const val WSL_DISTRO_KEY = "IronDome.Agent.WslDistro"
    private const val TERMINAL_VERIFIED_KEY = "IronDome.Agent.TerminalVerified"
    private const val TERMINAL_VERIFIED_AT_KEY = "IronDome.Agent.TerminalVerifiedAt"
    private val cursorCredentials = CredentialAttributes(generateServiceName("Iron Dome IA", "Cursor API key"))

    const val DEFAULT_TIMEOUT_MINUTES = 20

    /** Avisa quando a configuração ou o teste mudam, para as abas e os botões se atualizarem. */
    fun interface Listener {
        fun changed()

        companion object {
            @Topic.AppLevel
            val TOPIC = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    var provider: AgentProvider
        get() = AgentProvider.entries.firstOrNull { it.name == PropertiesComponent.getInstance().getValue(PROVIDER_KEY) } ?: AgentProvider.CURSOR
        set(value) = change { PropertiesComponent.getInstance().setValue(PROVIDER_KEY, value.name, AgentProvider.CURSOR.name) }

    /** Onde o CLI está instalado: nesta máquina ou no WSL. */
    var host: AgentHost
        get() = AgentHost.entries.firstOrNull { it.name == PropertiesComponent.getInstance().getValue(HOST_KEY) } ?: AgentHost.LOCAL
        set(value) = change { PropertiesComponent.getInstance().setValue(HOST_KEY, value.name, AgentHost.LOCAL.name) }

    /** A distribuição do WSL, como `Ubuntu`, quando [host] é o WSL. */
    var wslDistro: String?
        get() = PropertiesComponent.getInstance().getValue(WSL_DISTRO_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { PropertiesComponent.getInstance().setValue(WSL_DISTRO_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    /** Caminho do executável (no WSL, o caminho do Linux); vazio procura sozinho. */
    var executable: String?
        get() = PropertiesComponent.getInstance().getValue(EXECUTABLE_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { PropertiesComponent.getInstance().setValue(EXECUTABLE_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    var model: String?
        get() = PropertiesComponent.getInstance().getValue(MODEL_KEY)?.takeIf { it.isNotBlank() }
        set(value) = change { PropertiesComponent.getInstance().setValue(MODEL_KEY, value?.trim()?.takeIf { it.isNotEmpty() }) }

    var timeoutMinutes: Int
        get() = PropertiesComponent.getInstance().getInt(TIMEOUT_KEY, DEFAULT_TIMEOUT_MINUTES).coerceIn(1, 240)
        set(value) = PropertiesComponent.getInstance().setValue(TIMEOUT_KEY, value.coerceIn(1, 240), DEFAULT_TIMEOUT_MINUTES)

    /** Onde a IA roda: não entra no teste, que vale para os dois jeitos. */
    var runMode: AgentRunMode
        get() = AgentRunMode.entries.firstOrNull { it.name == PropertiesComponent.getInstance().getValue(RUN_MODE_KEY) } ?: AgentRunMode.BACKGROUND
        set(value) = change { PropertiesComponent.getInstance().setValue(RUN_MODE_KEY, value.name, AgentRunMode.BACKGROUND.name) }

    /** A configuração automática roda sozinha só na primeira vez; depois, só pelo botão. */
    var autoSetupTried: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(AUTO_SETUP_KEY)
        set(value) = PropertiesComponent.getInstance().setValue(AUTO_SETUP_KEY, value)

    /** Nada foi configurado à mão: sem executável, sem modelo e sem teste. */
    val untouched: Boolean get() = executable == null && model == null && PropertiesComponent.getInstance().getValue(VERIFIED_KEY) == null

    /**
     * Opcional: sem ela, o Cursor CLI usa o login feito com `agent login`. Trocar a chave muda a versão dela,
     * o que invalida o teste sem precisar ler o cofre de senhas a cada atualização dos botões.
     */
    var cursorApiKey: String?
        get() = PasswordSafe.instance.getPassword(cursorCredentials)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val key = value?.trim()?.takeIf { it.isNotEmpty() }
            if (key == cursorApiKey) return
            PasswordSafe.instance.setPassword(cursorCredentials, key)
            change { PropertiesComponent.getInstance().setValue(KEY_VERSION_KEY, PropertiesComponent.getInstance().getInt(KEY_VERSION_KEY, 0) + 1, 0) }
        }

    @Volatile
    private var readyCache: Boolean? = null

    /**
     * A IA pode ser usada: o executável existe e o teste passou com a configuração de agora. Os botões de IA
     * só se habilitam assim.
     */
    val ready: Boolean
        get() = readyCache ?: (launch() != null && verifiedFingerprint == fingerprint()).also { readyCache = it }

    /** Testada com a configuração de agora, mesmo que o executável tenha sumido depois. */
    val verified: Boolean get() = verifiedFingerprint == fingerprint()

    val verifiedAt: String? get() = PropertiesComponent.getInstance().getValue(VERIFIED_AT_KEY)?.takeIf { verified }

    private val verifiedFingerprint: String? get() = PropertiesComponent.getInstance().getValue(VERIFIED_KEY)

    /** O que um teste valida: muda com o CLI, onde ele está e o executável, o modelo ou a chave. */
    fun fingerprint(): String = listOf(
        provider.name,
        launch()?.description.orEmpty(),
        model.orEmpty(),
        PropertiesComponent.getInstance().getInt(KEY_VERSION_KEY, 0).toString(),
    ).joinToString("|")

    fun markVerified(at: String) = change {
        PropertiesComponent.getInstance().setValue(VERIFIED_KEY, fingerprint())
        PropertiesComponent.getInstance().setValue(VERIFIED_AT_KEY, at)
    }

    fun clearVerified() = change { PropertiesComponent.getInstance().unsetValue(VERIFIED_KEY) }

    /** Quando o teste pelo terminal passou com a configuração de agora; nulo se nunca, ou se ela mudou. */
    var terminalVerifiedAt: String?
        get() = PropertiesComponent.getInstance().getValue(TERMINAL_VERIFIED_AT_KEY)
            ?.takeIf { PropertiesComponent.getInstance().getValue(TERMINAL_VERIFIED_KEY) == fingerprint() }
        set(value) = change {
            PropertiesComponent.getInstance().setValue(TERMINAL_VERIFIED_KEY, value?.let { fingerprint() })
            PropertiesComponent.getInstance().setValue(TERMINAL_VERIFIED_AT_KEY, value)
        }

    private fun change(update: () -> Unit) {
        update()
        readyCache = null
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(Listener.TOPIC)?.changed()
    }

    /**
     * Como rodar o CLI com a configuração dada; nulo se ele não existe nesta máquina ou falta a distribuição do
     * WSL. No WSL não roda nada: sem caminho configurado, vale o nome do comando, achado pelo shell de login.
     */
    fun launch(
        provider: AgentProvider = this.provider,
        configured: String? = executable,
        host: AgentHost = this.host,
        distro: String? = wslDistro,
    ): AgentLaunch? = when (host) {
        AgentHost.LOCAL -> resolveExecutable(provider, configured)?.let(AgentLaunch::local)
        AgentHost.WSL -> distro?.takeIf { AgentLaunch.wslSupported }?.let { AgentLaunch.wsl(it, configured ?: provider.executables.first()) }
    }

    /** O executável configurado, se existir, ou o encontrado no PATH e nas pastas de instalação. */
    fun resolveExecutable(provider: AgentProvider = this.provider, configured: String? = executable): Path? {
        configured?.let { path -> return Path.of(path).takeIf(Files::isRegularFile) }
        return find(provider)
    }

    fun find(provider: AgentProvider): Path? {
        val names = provider.executables.flatMap { name ->
            if (SystemInfo.isWindows) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        }
        names.firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it) }?.let { return it.toPath() }
        return installDirs(provider).flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile }?.toPath()
    }

    /** Onde os instaladores costumam deixar o CLI quando a IDE não herdou o PATH do terminal. */
    private fun installDirs(provider: AgentProvider): List<File> {
        val home = System.getProperty("user.home")
        val localAppData = System.getenv("LOCALAPPDATA")
        return when (provider) {
            AgentProvider.CURSOR -> listOfNotNull(
                File(home, ".local/bin"),
                File(home, ".cursor/bin"),
                localAppData?.let { File(it, "cursor-agent") },
                localAppData?.let { File(it, "Programs/cursor-agent") },
            )
            AgentProvider.ANTIGRAVITY -> listOfNotNull(
                File(home, ".local/bin"),
                localAppData?.let { File(it, "agy/bin") },
                System.getenv("ProgramFiles")?.let { File(it, "Google/antigravity-cli") },
                File(home, ".antigravity/bin"),
                localAppData?.let { File(it, "Programs/Antigravity/bin") },
            )
        }
    }
}
