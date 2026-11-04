package com.mio.irondome.caixapreta

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.ui.jcef.JBCefApp
import com.mio.irondome.base.IdeSupport
import com.mio.irondome.base.ReviewToolWindowTab
import com.mio.irondome.base.TabFailures
import com.mio.irondome.interceptor.AgentSettings

/** [INFO] é uma configuração opcional; só [WARNING] e [ERROR] merecem aviso ao abrir o projeto. */
enum class CheckStatus(val label: String) {
    OK("OK"),
    INFO("Opcional"),
    WARNING("Limitado"),
    ERROR("Indisponível"),
}

/** [fix] diz o que fazer para resolver, quando há o que fazer. */
data class CompatibilityCheck(val mechanism: Mechanism, val status: CheckStatus, val detail: String, val fix: String? = null)

/** O que a IDE oferece ao plugin, lido de uma vez para as regras serem testadas sem a IDE. */
data class Environment(
    val pluginVersion: String,
    val ide: String,
    val system: String,
    val javaPlugin: Boolean,
    val junitPlugin: Boolean,
    /** Os plugins Git e GitHub da IDE, que a Patrulha usa juntos. */
    val githubPlugins: Boolean,
    val jcef: Boolean,
    val vcs: List<String>,
    /** Títulos das abas extras registradas, que só existem quando o plugin de que dependem carregou. */
    val extraTabs: Set<String>,
    /** Abas que não abriram nesta sessão, pelo título, com a mensagem do erro. */
    val tabFailures: Map<String, String>,
    val linearConfigured: Boolean,
    /** Build da IDE sem o código do produto, como `252.28539.97`. */
    val ideBuild: String = IdeSupport.TESTED_BUILD,
    /** Por título do mecanismo, o que esta IDE não tem e ele usa: são os desligados no modo limitado. */
    val missingApis: Map<String, List<String>> = emptyMap(),
    /** Abas que não abriram por faltar na IDE uma classe ou método que elas usam. */
    val ideVersionFailures: Set<String> = emptySet(),
    /** O CLI de IA escolhido e onde ele foi encontrado; nulo se não foi. */
    val agentProvider: String = "Cursor",
    val agentCli: String? = null,
) {
    val olderThanTested: Boolean get() = IdeSupport.isOlder(ideBuild, IdeSupport.TESTED_BUILD)
}

object Compatibility {

    private const val PLUGIN_ID = "com.mio.irondome"
    private const val JAVA_PLUGIN = "com.intellij.java"
    private const val JUNIT_PLUGIN = "JUnit"
    private const val GIT_PLUGIN = "Git4Idea"
    private const val GITHUB_PLUGIN = "org.jetbrains.plugins.github"

    /** Lê o cofre de senhas e o JCEF: chame fora da EDT. */
    fun environment(project: Project): Environment {
        val app = ApplicationInfo.getInstance()
        return Environment(
            pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "desconhecida",
            ide = "${app.fullApplicationName} (build ${app.build.asString()})",
            system = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}, Java ${SystemInfo.JAVA_RUNTIME_VERSION}",
            javaPlugin = pluginEnabled(JAVA_PLUGIN),
            junitPlugin = pluginEnabled(JUNIT_PLUGIN),
            githubPlugins = pluginEnabled(GIT_PLUGIN) && pluginEnabled(GITHUB_PLUGIN),
            jcef = runCatching { JBCefApp.isSupported() }.getOrDefault(false),
            vcs = ProjectLevelVcsManager.getInstance(project).allActiveVcss.map { it.displayName },
            extraTabs = ReviewToolWindowTab.EP_NAME.extensionList.mapTo(mutableSetOf()) { it.title },
            tabFailures = TabFailures.all,
            linearConfigured = runCatching { LinearSettings.configured }.getOrDefault(false),
            ideBuild = app.build.asStringWithoutProductCode(),
            missingApis = IdeSupport.missing,
            ideVersionFailures = TabFailures.fromIdeVersion,
            agentProvider = AgentSettings.provider.title,
            agentCli = runCatching { AgentSettings.resolveExecutable()?.toString() }.getOrNull(),
        )
    }

    fun pluginEnabled(id: String): Boolean {
        val pluginId = PluginId.getId(id)
        return PluginManagerCore.getPlugin(pluginId) != null && !PluginManagerCore.isDisabled(pluginId)
    }

    fun evaluate(env: Environment): List<CompatibilityCheck> = listOf(
        radar(env),
        CompatibilityCheck(Mechanism.COMANDO, CheckStatus.OK, "Não depende de outros plugins."),
        sentinela(env),
        impacto(env),
        escudo(env),
        patrulha(env),
        interceptor(env),
        caixaPreta(env),
    ).map { check -> ideVersion(env, check.mechanism) ?: check }

    /** Opcional: sem o CLI, as ações de IA caem no fluxo manual de copiar o pedido. */
    private fun interceptor(env: Environment): CompatibilityCheck = when (val cli = env.agentCli) {
        null -> CompatibilityCheck(
            Mechanism.INTERCEPTOR, CheckStatus.INFO,
            "${env.agentProvider} CLI não encontrado: as ações com IA ficam indisponíveis ou só copiam o pedido.",
            "Instale o CLI ou informe o caminho em Settings > Tools > Iron Dome IA > Interceptador (IA).",
        )
        else -> CompatibilityCheck(Mechanism.INTERCEPTOR, CheckStatus.OK, "${env.agentProvider} CLI em $cli.")
    }

    /** Os que merecem aviso ao abrir o projeto. */
    fun problems(checks: List<CompatibilityCheck>) = checks.filter { it.status == CheckStatus.WARNING || it.status == CheckStatus.ERROR }

    /** Mecanismos que esta versão do IntelliJ não comporta: pedem atualizar a IDE ou trabalhar sem eles. */
    fun limited(env: Environment): List<Mechanism> = Mechanism.entries.filter { it.title in env.missingApis }

    /** O texto da pergunta ao abrir o projeto: atualizar o IntelliJ ou trabalhar limitado. */
    fun limitedMessage(env: Environment): String = buildString {
        append("Esta versão do IntelliJ (build ${env.ideBuild}) não tem tudo de que o Iron Dome IA precisa. ")
        append("Ele foi testado com o IntelliJ ${IdeSupport.TESTED_VERSION} (build ${IdeSupport.TESTED_BUILD}) ou mais novo.\n\n")
        limited(env).forEach { mechanism ->
            append("• ${mechanism.title}: falta ${env.missingApis.getValue(mechanism.title).joinToString()}\n")
        }
        append("\nAtualize o IntelliJ para usar tudo. Se não puder atualizar agora, trabalhe limitado: ")
        append("só esses mecanismos ficam desligados, e o restante funciona normalmente.")
    }

    private fun ideVersion(env: Environment, mechanism: Mechanism): CompatibilityCheck? {
        val missing = env.missingApis[mechanism.title]
        val fix = "Atualize o IntelliJ para ${IdeSupport.TESTED_VERSION} ou mais novo (Help > Check for Updates)."
        return when {
            missing != null -> CompatibilityCheck(
                mechanism, CheckStatus.ERROR,
                "Desligado no modo limitado: esta versão do IntelliJ (build ${env.ideBuild}) não tem ${missing.joinToString()}.",
                fix,
            )
            mechanism.title in env.ideVersionFailures -> CompatibilityCheck(
                mechanism, CheckStatus.ERROR,
                "A aba ${mechanism.title} não abriu porque esta versão do IntelliJ não tem algo que ela usa: ${env.tabFailures[mechanism.title].orEmpty()}",
                fix,
            )
            else -> null
        }
    }

    private fun radar(env: Environment): CompatibilityCheck {
        val failure = env.tabFailures[Mechanism.RADAR.title]
        return when {
            failure != null -> failed(Mechanism.RADAR, Mechanism.RADAR.title, failure)
            else -> CompatibilityCheck(Mechanism.RADAR, CheckStatus.OK, "Não depende de outros plugins.")
        }
    }

    private fun sentinela(env: Environment): CompatibilityCheck {
        val failure = env.tabFailures[Mechanism.SENTINELA.title]
        return when {
            failure != null -> failed(Mechanism.SENTINELA, Mechanism.SENTINELA.title, failure)
            env.vcs.isEmpty() -> CompatibilityCheck(
                Mechanism.SENTINELA, CheckStatus.INFO,
                "O projeto não usa controle de versão: a aba fica vazia.",
                "Ative o Git em VCS > Enable Version Control Integration.",
            )
            else -> CompatibilityCheck(Mechanism.SENTINELA, CheckStatus.OK, "Controle de versão: ${env.vcs.joinToString()}.")
        }
    }

    private fun impacto(env: Environment): CompatibilityCheck {
        val title = Mechanism.IMPACTO.title
        val failure = env.tabFailures[title]
        return when {
            failure != null -> failed(Mechanism.IMPACTO, title, failure)
            !env.javaPlugin -> CompatibilityCheck(
                Mechanism.IMPACTO, CheckStatus.ERROR,
                "O plugin Java está ausente ou desativado nesta IDE, então a aba não aparece.",
                "Ative o plugin Java em Settings > Plugins > Installed. Ele vem no IntelliJ IDEA; IDEs sem suporte a Java, como PyCharm e WebStorm, não têm.",
            )
            title !in env.extraTabs -> notLoaded(Mechanism.IMPACTO, "Java")
            !env.jcef -> CompatibilityCheck(
                Mechanism.IMPACTO, CheckStatus.WARNING,
                "O navegador embutido (JCEF) não está disponível: a aba abre, mas o diagrama não é desenhado.",
                "Em Help > Find Action, rode Choose Boot Java Runtime for the IDE, escolha um runtime com JCEF e reinicie a IDE.",
            )
            else -> CompatibilityCheck(Mechanism.IMPACTO, CheckStatus.OK, "Plugin Java ativo e navegador embutido (JCEF) disponível.")
        }
    }

    private fun escudo(env: Environment): CompatibilityCheck {
        val title = Mechanism.ESCUDO.title
        val failure = env.tabFailures[title]
        return when {
            failure != null -> failed(Mechanism.ESCUDO, title, failure)
            !env.junitPlugin -> CompatibilityCheck(
                Mechanism.ESCUDO, CheckStatus.ERROR,
                "O plugin JUnit está ausente ou desativado nesta IDE, então a aba não aparece.",
                "Ative o plugin JUnit em Settings > Plugins > Installed. Ele vem no IntelliJ IDEA.",
            )
            title !in env.extraTabs -> notLoaded(Mechanism.ESCUDO, "JUnit")
            else -> CompatibilityCheck(Mechanism.ESCUDO, CheckStatus.OK, "Plugin JUnit ativo.")
        }
    }

    /** Opcional: quem não usa GitHub não precisa ser avisado ao abrir o projeto. */
    private fun patrulha(env: Environment): CompatibilityCheck {
        val title = Mechanism.PATRULHA.title
        val failure = env.tabFailures[title]
        return when {
            failure != null -> failed(Mechanism.PATRULHA, title, failure)
            !env.githubPlugins -> CompatibilityCheck(
                Mechanism.PATRULHA, CheckStatus.INFO,
                "Os plugins Git e GitHub da IDE estão ausentes ou desativados, então a aba não aparece.",
                "Ative os plugins Git e GitHub em Settings > Plugins > Installed.",
            )
            title !in env.extraTabs -> notLoaded(Mechanism.PATRULHA, "GitHub")
            else -> CompatibilityCheck(Mechanism.PATRULHA, CheckStatus.OK, "Plugins Git e GitHub ativos; a conta e o repositório aparecem na aba Patrulha.")
        }
    }

    private fun caixaPreta(env: Environment): CompatibilityCheck = when {
        env.linearConfigured -> CompatibilityCheck(Mechanism.CAIXA_PRETA, CheckStatus.OK, "Linear configurado: os bugs podem ser enviados.")
        else -> CompatibilityCheck(
            Mechanism.CAIXA_PRETA, CheckStatus.INFO,
            "Linear não configurado: os relatórios de bug são gerados só localmente.",
            "Informe a API key em Settings > Tools > Iron Dome IA.",
        )
    }

    private fun failed(mechanism: Mechanism, tab: String, failure: String) = CompatibilityCheck(
        mechanism, CheckStatus.ERROR,
        "A aba $tab não abriu: $failure",
        "Veja o erro completo no log, abaixo, e use Reportar Bug para enviá-lo.",
    )

    private fun notLoaded(mechanism: Mechanism, plugin: String) = CompatibilityCheck(
        mechanism, CheckStatus.ERROR,
        "O plugin $plugin está ativo, mas a parte do Iron Dome IA que depende dele não carregou.",
        "Reinicie a IDE. Se continuar, veja o log abaixo e use Reportar Bug.",
    )

    /** Texto para colar numa conversa ou issue. */
    fun markdown(env: Environment, checks: List<CompatibilityCheck>, errors: List<PluginError>): String = buildString {
        appendLine("# Compatibilidade do Iron Dome IA")
        appendLine()
        appendLine("- **Plugin:** ${env.pluginVersion}")
        appendLine("- **IDE:** ${env.ide}")
        appendLine("- **Testado com:** IntelliJ ${IdeSupport.TESTED_VERSION} (build ${IdeSupport.TESTED_BUILD})${if (env.olderThanTested) "; esta IDE é mais antiga" else ""}")
        appendLine("- **Sistema:** ${env.system}")
        appendLine()
        appendLine("| Mecanismo | Situação | Detalhe |")
        appendLine("| --- | --- | --- |")
        checks.forEach { check ->
            val detail = listOfNotNull(check.detail, check.fix).joinToString(" ").replace("|", "\\|")
            appendLine("| ${check.mechanism.title} | ${check.status.label} | $detail |")
        }
        appendLine()
        appendLine("## Erros do plugin no log da IDE")
        appendLine()
        if (errors.isEmpty()) {
            appendLine("Nenhum erro ou aviso do plugin no `idea.log`.")
        } else {
            errors.forEach { error ->
                appendLine("### ${error.time} · ${error.level}: ${error.message}")
                if (error.stack.isNotEmpty()) {
                    appendLine()
                    appendLine("```text")
                    BugReportMarkdown.compactStack(error.stack).forEach(::appendLine)
                    appendLine("```")
                }
                appendLine()
            }
        }
    }.trimEnd() + "\n"
}
