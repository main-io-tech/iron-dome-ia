package com.mio.irondome.interceptor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Onde o CLI de IA está instalado. */
enum class AgentHost(val label: String) {
    LOCAL("Nesta máquina"),
    WSL("No WSL"),
}

/**
 * Como rodar o CLI: direto nesta máquina ou dentro de uma distribuição do WSL, pelo shell de login dela (que
 * põe `~/.local/bin` no PATH). [executable] é o caminho ou o nome do comando no sistema onde ele roda.
 */
class AgentLaunch private constructor(val executable: String, val distro: String?) {

    private val wsl: WSLDistribution? by lazy { distro?.let { WslDistributionManager.getInstance().getOrCreateDistributionByMsId(it) } }

    val description: String get() = distro?.let { "WSL $it: $executable" } ?: executable

    /** Para as mensagens: " no WSL Ubuntu" ou vazio. */
    val where: String get() = distro?.let { " no WSL $it" }.orEmpty()

    /** Um caminho desta máquina como o CLI o enxerga: igual, ou `/mnt/c/...` no WSL. */
    fun workspace(path: String): String = wsl?.getWslPath(Path.of(path)) ?: path

    /** O processo de [command] (que começa pelo [executable]) em [workDir], com a chave do Cursor no ambiente. */
    fun process(command: List<String>, workDir: String, cursorApiKey: String?): GeneralCommandLine {
        val line = GeneralCommandLine(command)
            .withWorkDirectory(workDir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        cursorApiKey?.let { line.withEnvironment(CURSOR_API_KEY, it) }
        val distribution = wsl ?: return line
        val options = WSLCommandLineOptions()
            .setExecuteCommandInShell(true)
            .setExecuteCommandInLoginShell(true)
            .setExecuteCommandInInteractiveShell(true)
            .setRemoteWorkingDirectory(workspace(workDir))
            .setPassEnvVarsUsingInterop(true)
        return distribution.patchCommandLine(line, null, options)
    }

    /** O comando de uma linha para o terminal da IDE, que roda em [shell] nesta máquina. */
    fun terminal(
        provider: AgentProvider,
        prompt: String,
        model: String?,
        timeoutMinutes: Int,
        shell: ShellKind,
        workDir: String,
        interactive: Boolean = true,
    ): String {
        val distribution = distro ?: return AgentCommand.terminal(provider, executable, prompt, model, timeoutMinutes, shell, interactive)
        val inner = AgentCommand.terminal(provider, executable, prompt, model, timeoutMinutes, ShellKind.POSIX, interactive)
        return AgentCommand.wrapWsl(distribution, workspace(workDir), inner, shell)
    }

    companion object {
        private const val CURSOR_API_KEY = "CURSOR_API_KEY"
        private const val FIND_TIMEOUT_MS = 20_000

        fun local(executable: Path) = AgentLaunch(executable.toString(), null)

        fun wsl(distro: String, executable: String) = AgentLaunch(executable, distro)

        /** Windows com WSL: só aí a opção aparece. */
        val wslSupported: Boolean get() = SystemInfo.isWindows && runCatching { WSLUtil.isSystemCompatible() }.getOrDefault(false)

        /** As distribuições instaladas, sem as internas do Docker Desktop; roda o `wsl.exe`, então fora da EDT. */
        fun distributions(): List<String> =
            if (!wslSupported) emptyList()
            else runCatching { WslDistributionManager.getInstance().installedDistributions.map { it.msId } }.getOrDefault(emptyList())
                .filterNot { it.startsWith("docker-desktop", ignoreCase = true) }

        /** O caminho do CLI no PATH do shell de login de [distro], ou nulo; fora da EDT. */
        fun findInWsl(provider: AgentProvider, distro: String): String? {
            val lookup = provider.executables.joinToString(" || ") { "command -v $it" }
            val line = GeneralCommandLine("wsl.exe", "-d", distro, "--", "bash", "-lic", lookup).withCharset(StandardCharsets.UTF_8)
            val output = runCatching { ExecUtil.execAndGetOutput(line, FIND_TIMEOUT_MS) }.getOrNull() ?: return null
            return output.stdoutLines.map { it.trim() }.lastOrNull { it.startsWith("/") }
        }
    }
}
