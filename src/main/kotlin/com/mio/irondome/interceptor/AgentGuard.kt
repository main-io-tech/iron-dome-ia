package com.mio.irondome.interceptor

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A contenção da IA: o que ela não pode fazer na máquina do usuário, valendo para qualquer CLI. Cada execução na aba
 * roda com o ambiente de [environment] (sem `git push` e sem os segredos que o CLI não usa), e [violation] confere
 * cada passo que o CLI informa: um comando destrutivo, ou escrever fora do projeto ou dentro do `.git`, para a IA na
 * hora. No Claude Code, [claudeDisallowedTools] bloqueia os mesmos comandos antes de rodarem.
 */
object AgentGuard {

    private const val GIT = """^git(\s+-[cC]\s+\S+)*\s+"""

    /**
     * Os comandos que a IA não roda, com o motivo que aparece para o usuário. Valem no começo de cada comando da
     * linha, para uma busca como `grep -r "shutdown"` não contar.
     */
    private val DANGEROUS: List<Pair<Regex, String>> = listOf(
        """^rm\s+(.*\s)?(-[a-z]*[rf][a-z]*|--recursive|--force)(\s|$)""" to "apagar com rm -r ou -f",
        """^(del|erase)\s.*/[sq]\b""" to "apagar em massa com del",
        """^(rd|rmdir)\s.*/s\b""" to "apagar pastas com rd /s",
        """^(remove-item|ri|rm|del|rmdir|rd)\s.*-(recurse|force)\b""" to "apagar com Remove-Item -Recurse ou -Force",
        """^(format(\.com)?\s+[a-z]:|mkfs(\.\w+)?\b|diskpart\b|fdisk\b)""" to "formatar ou particionar disco",
        """^dd\s.*\bof=""" to "gravar direto em disco com dd",
        """^(shutdown|reboot|halt|poweroff|stop-computer|restart-computer)\b""" to "desligar ou reiniciar a máquina",
        """^(sudo|su|runas|doas)\b""" to "rodar como administrador",
        """^(chmod|chown)\s+(.*\s)?-[a-z]*R|^(icacls|takeown)\b""" to "mudar permissões em massa",
        """^reg(\.exe)?\s+(add|delete|import)\b|^(set|remove|new)-itemproperty\b.*\bhk(lm|cu)""" to "alterar o registro do Windows",
        """${GIT}push\b""" to "enviar para o repositório remoto (git push)",
        """${GIT}reset\b.*--hard\b""" to "descartar alterações com git reset --hard",
        """${GIT}clean\b.*\s-[a-z]*f""" to "apagar arquivos não versionados com git clean",
        """${GIT}checkout\b.*\s(--|\.)(\s|$)|${GIT}restore\b""" to "descartar alterações do working tree",
        """${GIT}branch\b.*\s-D\b""" to "apagar branch",
        """${GIT}stash\s+(drop|clear)\b""" to "apagar stashes",
        """$GIT(rebase|filter-branch|filter-repo|update-ref\s+-d|reflog\s+expire|gc\b.*--prune)\b""" to "reescrever o histórico do git",
    ).map { (pattern, reason) -> Regex(pattern, RegexOption.IGNORE_CASE) to reason }

    /** Os que valem na linha inteira, porque juntam comandos. */
    private val DANGEROUS_LINE: List<Pair<Regex, String>> = listOf(
        """\b(curl|wget|invoke-webrequest|invoke-restmethod|iwr|irm)\b[^|]*\|\s*(sh|bash|zsh|iex|invoke-expression|python3?|node|pwsh|powershell)\b""" to
            "baixar e executar um script da internet",
        """:\(\)\s*\{\s*:\s*\|\s*:""" to "travar a máquina (fork bomb)",
    ).map { (pattern, reason) -> Regex(pattern, RegexOption.IGNORE_CASE) to reason }

    private val SEPARATORS = Regex("""&&|\|\||[;&|\n]|\$\(|`""")

    /** O que vem antes do comando de fato: variáveis, `env`, `nohup`, `cmd /c`, `powershell -Command` e aspas. */
    private val WRAPPER = Regex(
        """^(\w+=\S*\s+|(env|command|exec|nohup|time|xargs)\s+|cmd(\.exe)?\s+/[ck]\s+|(powershell|pwsh)(\.exe)?\s+(-\w+\s+)*|["'(]\s*)""",
        RegexOption.IGNORE_CASE,
    )

    /** O motivo de [command] ser bloqueado, ou nulo quando ele pode rodar. */
    fun dangerousCommand(command: String): String? {
        DANGEROUS_LINE.firstOrNull { it.first.containsMatchIn(command) }?.let { return it.second }
        return command.split(SEPARATORS).asSequence().map(::unwrap).filter(String::isNotEmpty)
            .firstNotNullOfOrNull { part -> DANGEROUS.firstOrNull { it.first.containsMatchIn(part) }?.second }
    }

    private fun unwrap(part: String): String {
        var text = part.trim()
        repeat(6) {
            val next = WRAPPER.find(text)?.let { text.substring(it.range.last + 1).trimStart() } ?: return text
            if (next == text) return text
            text = next
        }
        return text
    }

    /** As ferramentas que escrevem ou apagam arquivos, nos tipos de [AgentEvent.Tool]. */
    private val WRITE_KINDS = setOf("write", "edit", "multiEdit", "delete", "applyPatch")

    /**
     * Por que o passo [tool] da IA precisa ser parado, ou nulo. [roots] são a pasta do projeto como esta máquina e
     * como o CLI a enxergam (no WSL, `/mnt/c/...`).
     */
    fun violation(tool: AgentEvent.Tool, roots: List<String>): String? {
        tool.command?.let { command -> dangerousCommand(command)?.let { return "comando bloqueado: $it (${command.take(120)})" } }
        val path = tool.path?.takeIf { tool.kind in WRITE_KINDS } ?: return null
        return when {
            outside(path, roots) -> "escrever fora do projeto ($path)"
            insideGit(path) -> "mexer no .git do projeto ($path)"
            else -> null
        }
    }

    /** [path] absoluto fora de todas as [roots], ou relativo subindo além delas com `..`. */
    fun outside(path: String, roots: List<String>): Boolean {
        val normalized = normalize(path) ?: return false
        if (!isAbsolute(path)) return normalized.startsWith("../") || normalized == ".."
        return roots.mapNotNull(::normalize).none { root -> normalized.equals(root, true) || normalized.startsWith("$root/", true) }
    }

    private fun insideGit(path: String): Boolean = normalize(path)?.split('/')?.contains(".git") == true

    private fun isAbsolute(path: String) = path.startsWith("/") || path.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(path)

    private fun normalize(path: String): String? = try {
        Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/').trimEnd('/')
    } catch (_: InvalidPathException) {
        null
    }

    /**
     * Os mesmos bloqueios no Claude Code, que os recusa antes de rodar: comandos destrutivos e alterações no `.git` e
     * nas chaves do usuário. Separados por vírgula, como o `--disallowedTools` aceita.
     */
    val claudeDisallowedTools: String = listOf(
        "rm -rf", "rm -fr", "rm -r", "rm -f", "del", "erase", "rd", "rmdir", "Remove-Item", "format", "mkfs", "diskpart", "dd",
        "shutdown", "reboot", "sudo", "su", "runas", "reg", "chmod -R", "chown -R",
        "git push", "git reset --hard", "git clean", "git checkout --", "git restore", "git branch -D",
        "git stash drop", "git stash clear", "git rebase", "git filter-branch", "git update-ref -d", "git reflog expire",
        "curl", "wget",
    ).flatMap { listOf("Bash($it)", "Bash($it *)") }
        .plus(listOf("Edit(./.git/**)", "Edit(~/.ssh/**)", "Read(~/.ssh/**)", "Edit(~/.aws/**)", "Read(~/.aws/**)"))
        .joinToString(",")

    /** Para os pushes que a IA tentar: todo endereço de push vira um destino que não existe. Buscar continua. */
    private val PUSH_PREFIXES = listOf("https://", "http://", "git@", "ssh://", "git://")

    /** O que entra no ambiente da IA: o push bloqueado e o git sem pedir senha, o que travaria a execução. */
    val gitEnvironment: Map<String, String> = buildMap {
        put("GIT_TERMINAL_PROMPT", "0")
        put("GIT_CONFIG_COUNT", PUSH_PREFIXES.size.toString())
        PUSH_PREFIXES.forEachIndexed { index, prefix ->
            put("GIT_CONFIG_KEY_$index", "url.iron-dome-push-bloqueado://.pushInsteadOf")
            put("GIT_CONFIG_VALUE_$index", prefix)
        }
    }

    /** Partes inteiras do nome, como em `GITHUB_TOKEN` ou `SSH_AUTH_SOCK`: `GIT_AUTHOR_NAME` fica. */
    private val SECRET = Regex(
        "(^|_)(TOKEN|SECRET|PASSWORD|PASSWD|PASS|CREDENTIALS?|PRIVATE_KEY|API_KEY|ACCESS_KEY|AUTH|PAT)(_|$)",
        RegexOption.IGNORE_CASE,
    )

    /** O que cada CLI usa para entrar: essas variáveis passam mesmo parecendo segredo. */
    private fun needed(provider: AgentProvider, parent: Map<String, String>): (String) -> Boolean = when (provider) {
        AgentProvider.CURSOR -> { name -> name.startsWith("CURSOR_") }
        AgentProvider.CLAUDE -> { name ->
            name.startsWith("ANTHROPIC_") || name.startsWith("CLAUDE_") ||
                ("CLAUDE_CODE_USE_BEDROCK" in parent && name.startsWith("AWS_")) ||
                ("CLAUDE_CODE_USE_VERTEX" in parent && (name.startsWith("GOOGLE_") || name.startsWith("CLOUD_ML_")))
        }
        AgentProvider.ANTIGRAVITY -> { name -> name.startsWith("GEMINI_") || name.startsWith("GOOGLE_") || name.startsWith("ANTIGRAVITY_") }
    }

    /**
     * O ambiente da IA a partir do da IDE ([parent]): sem tokens, senhas e chaves que o CLI de [provider] não usa (como
     * `GITHUB_TOKEN` ou `AWS_SECRET_ACCESS_KEY`), e com [gitEnvironment].
     */
    fun environment(parent: Map<String, String>, provider: AgentProvider): Map<String, String> {
        val keep = needed(provider, parent)
        return parent.filter { (name, _) -> !SECRET.containsMatchIn(name) || keep(name) } + gitEnvironment
    }

    /** A regra que vai no pedido de toda tarefa que altera arquivos: a IA sabe o que não fazer antes de tentar. */
    const val RULE =
        "Seguranca: nao rode comandos destrutivos (apagar pastas, git push, git reset --hard, git clean, reescrever o historico, " +
            "sudo, formatar disco, baixar e executar scripts) e nao altere arquivos fora do projeto nem dentro de .git. " +
            "Se a tarefa precisar disso, pare e explique na resposta."
}
