package com.mio.irondome.base

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId

/**
 * Uma parte do IntelliJ de que um mecanismo depende, conferida por reflexão na IDE em execução.
 * [mechanism] é o título do mecanismo, igual ao da aba; [plugin], o plugin da IDE que traz a classe, se
 * não for a plataforma. Sem [method], basta a classe existir.
 */
class ApiRequirement(
    val mechanism: String,
    val label: String,
    val className: String,
    val method: String? = null,
    val plugin: String? = null,
)

/**
 * O que esta versão do IntelliJ oferece aos mecanismos. Os que não têm tudo de que precisam ficam
 * desligados (modo limitado), no lugar de quebrar ao abrir.
 */
object IdeSupport {

    /** Build do `platformVersion` do `gradle.properties`, com que o plugin é compilado e testado. */
    const val TESTED_BUILD = "252.28539"
    const val TESTED_VERSION = "2025.2.6"

    private const val UPDATE_ACTION = "CheckForUpdate"
    private const val DOWNLOAD_URL = "https://www.jetbrains.com/idea/download/"
    private const val LIMITED_KEY = "IronDome.LimitedMode.Build"

    val REQUIREMENTS = listOf(
        ApiRequirement("Radar", "formulário embutido no editor", "com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager\$Properties"),
        ApiRequirement("Radar", "cartões no editor", "com.intellij.openapi.editor.InlayModel", "addBlockElement"),
        ApiRequirement("Radar", "campo de texto com dica", "com.intellij.ui.EditorTextField", "setPlaceholder"),
        ApiRequirement("Centro de Comando", "barra flutuante do editor", "com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider"),
        ApiRequirement("Centro de Comando", "aviso de arquivo salvo", "com.intellij.openapi.fileEditor.FileDocumentManagerListener"),
        ApiRequirement("Sentinela", "diff em aba do editor", "com.intellij.diff.editor.DiffEditorTabFilesManager"),
        ApiRequirement("Sentinela", "cadeia de diffs", "com.intellij.diff.editor.ChainDiffVirtualFile"),
        ApiRequirement("Sentinela", "aviso de alterações", "com.intellij.openapi.vcs.changes.ChangeListListener"),
        ApiRequirement("Raio de Impacto", "ponte entre o diagrama e a IDE", "com.intellij.ui.jcef.JBCefJSQuery", "create"),
        ApiRequirement("Raio de Impacto", "busca de referências", "com.intellij.psi.search.searches.ReferencesSearch", "search"),
        ApiRequirement("Raio de Impacto", "classes Java", "com.intellij.psi.JavaPsiFacade", "findClass", plugin = "com.intellij.java"),
        ApiRequirement("Escudo", "configuração JUnit", "com.intellij.execution.junit.JUnitConfiguration", "setVMParameters", plugin = "JUnit"),
        ApiRequirement("Escudo", "eventos dos testes", "com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener"),
        ApiRequirement("Patrulha", "comandos Git", "git4idea.commands.GitLineHandler", "addParameters", plugin = "Git4Idea"),
        ApiRequirement("Patrulha", "login do GitHub com token", "org.jetbrains.plugins.github.authentication.GHAccountsUtil", "requestReLogin", plugin = "org.jetbrains.plugins.github"),
        ApiRequirement("Patrulha", "origem do login do GitHub", "org.jetbrains.plugins.github.authentication.GHLoginSource", plugin = "org.jetbrains.plugins.github"),
        ApiRequirement("Patrulha", "token da conta do GitHub", "org.jetbrains.plugins.github.util.GHCompatibilityUtil", "getOrRequestToken", plugin = "org.jetbrains.plugins.github"),
        ApiRequirement("Caixa-Preta", "cofre de senhas", "com.intellij.ide.passwordSafe.PasswordSafe", "getInstance"),
    )

    /** Por mecanismo, o que falta nesta IDE; conferido uma vez por sessão. */
    val missing: Map<String, List<String>> by lazy { probe(REQUIREMENTS, ::classLoaderOf) }

    fun isLimited(mechanism: String): Boolean = mechanism in missing

    val ideBuild: String get() = ApplicationInfo.getInstance().build.asStringWithoutProductCode()

    val ideVersion: String get() = ApplicationInfo.getInstance().fullVersion

    /** O usuário já escolheu trabalhar limitado nesta versão da IDE: não pergunta de novo até ela mudar. */
    var limitedAccepted: Boolean
        get() = PropertiesComponent.getInstance().getValue(LIMITED_KEY) == ideBuild
        set(value) = PropertiesComponent.getInstance().setValue(LIMITED_KEY, if (value) ideBuild else null)

    /**
     * [loader] devolve onde procurar as classes de um plugin, ou nulo se ele não está ativo: aí o requisito
     * não conta, porque a falta do plugin já é avisada à parte.
     */
    fun probe(requirements: List<ApiRequirement>, loader: (plugin: String?) -> ClassLoader?): Map<String, List<String>> =
        requirements.filterNot { requirement ->
            val classLoader = loader(requirement.plugin) ?: return@filterNot true
            val type = runCatching { Class.forName(requirement.className, false, classLoader) }.getOrNull() ?: return@filterNot false
            requirement.method == null || runCatching { type.methods.any { it.name == requirement.method } }.getOrDefault(false)
        }.groupBy({ it.mechanism }, { it.label })

    /** Compara só as partes de [than]: `252.28000.5` é mais antigo que `252.28539`. */
    fun isOlder(build: String, than: String): Boolean {
        val mine = build.split('.').map { it.toIntOrNull() ?: 0 }
        val reference = than.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in reference.indices) {
            val part = mine.getOrElse(index) { 0 }
            if (part != reference[index]) return part < reference[index]
        }
        return false
    }

    /** Abre a busca de atualizações da IDE ou, sem ela, a página de download. */
    fun openUpdate() {
        val actions = ActionManager.getInstance()
        val action = actions.getAction(UPDATE_ACTION)
        if (action == null) {
            BrowserUtil.browse(DOWNLOAD_URL)
            return
        }
        actions.tryToExecute(action, null, null, ActionPlaces.UNKNOWN, true)
    }

    private fun classLoaderOf(plugin: String?): ClassLoader? {
        if (plugin == null) return IdeSupport::class.java.classLoader
        val id = PluginId.getId(plugin)
        if (PluginManagerCore.isDisabled(id)) return null
        return PluginManagerCore.getPlugin(id)?.pluginClassLoader
    }
}
