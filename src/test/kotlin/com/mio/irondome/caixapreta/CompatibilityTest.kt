package com.mio.irondome.caixapreta

import com.mio.irondome.base.ApiRequirement
import com.mio.irondome.base.IdeSupport
import com.mio.irondome.base.TabFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {

    private val healthy = Environment(
        pluginVersion = "0.1.0",
        ide = "IntelliJ IDEA 2025.2 (build IU-252.1)",
        system = "Windows 11, Java 21",
        javaPlugin = true,
        junitPlugin = true,
        githubPlugins = true,
        jcef = true,
        vcs = listOf("Git"),
        extraTabs = setOf("Raio de Impacto", "Escudo", "Patrulha"),
        tabFailures = emptyMap(),
        linearConfigured = true,
        agentCli = "C:/Users/dev/.local/bin/agent.exe",
    )

    private fun check(env: Environment, mechanism: Mechanism) = Compatibility.evaluate(env).single { it.mechanism == mechanism }

    @Test
    fun everythingAvailableHasNoProblems() {
        val checks = Compatibility.evaluate(healthy)

        assertTrue(checks.all { it.status == CheckStatus.OK })
        assertTrue(Compatibility.problems(checks).isEmpty())
    }

    @Test
    fun withoutTheJavaPluginTheImpactTabIsUnavailable() {
        val env = healthy.copy(javaPlugin = false, extraTabs = setOf("Escudo", "Patrulha"))

        val impact = check(env, Mechanism.IMPACTO)

        assertEquals(CheckStatus.ERROR, impact.status)
        assertTrue(impact.detail.contains("plugin Java"))
        assertEquals(listOf(Mechanism.IMPACTO), Compatibility.problems(Compatibility.evaluate(env)).map { it.mechanism })
    }

    @Test
    fun pluginActiveButTabNotRegisteredMeansThePartDidNotLoad() {
        val env = healthy.copy(extraTabs = setOf("Escudo", "Patrulha"))

        val impact = check(env, Mechanism.IMPACTO)

        assertEquals(CheckStatus.ERROR, impact.status)
        assertTrue(impact.detail.contains("não carregou"))
    }

    @Test
    fun aTabThatFailedToOpenShowsTheError() {
        val env = healthy.copy(tabFailures = mapOf("Raio de Impacto" to "IllegalStateException: JCEF falhou"))

        val impact = check(env, Mechanism.IMPACTO)

        assertEquals(CheckStatus.ERROR, impact.status)
        assertTrue(impact.detail.contains("IllegalStateException: JCEF falhou"))
        assertEquals(CheckStatus.OK, check(env, Mechanism.ESCUDO).status)
    }

    @Test
    fun withoutJcefTheDiagramIsLimited() {
        val impact = check(healthy.copy(jcef = false), Mechanism.IMPACTO)

        assertEquals(CheckStatus.WARNING, impact.status)
        assertTrue(impact.fix!!.contains("Choose Boot Java Runtime"))
    }

    @Test
    fun withoutJUnitTheShieldIsUnavailable() {
        assertEquals(CheckStatus.ERROR, check(healthy.copy(junitPlugin = false, extraTabs = setOf("Raio de Impacto", "Patrulha")), Mechanism.ESCUDO).status)
    }

    @Test
    fun withoutTheGitHubPluginsThePatrolIsOptional() {
        val env = healthy.copy(githubPlugins = false, extraTabs = setOf("Raio de Impacto", "Escudo"))

        assertEquals(CheckStatus.INFO, check(env, Mechanism.PATRULHA).status)
        assertTrue(Compatibility.problems(Compatibility.evaluate(env)).isEmpty())
    }

    @Test
    fun optionalSettingsAreInformationOnlyAndDoNotWarn() {
        val env = healthy.copy(vcs = emptyList(), linearConfigured = false, agentCli = null)

        assertEquals(CheckStatus.INFO, check(env, Mechanism.SENTINELA).status)
        assertEquals(CheckStatus.INFO, check(env, Mechanism.CAIXA_PRETA).status)
        assertEquals(CheckStatus.INFO, check(env, Mechanism.INTERCEPTOR).status)
        assertTrue(check(env, Mechanism.INTERCEPTOR).fix!!.contains("Interceptador (IA)"))
        assertTrue(Compatibility.problems(Compatibility.evaluate(env)).isEmpty())
    }

    @Test
    fun markdownListsTheChecksAndTheLogErrors() {
        val env = healthy.copy(javaPlugin = false)
        val error = PluginError(
            "2026-11-03 13:00:00", "WARN", "A aba Raio de Impacto do Iron Dome IA não pôde ser aberta",
            listOf("java.lang.IllegalStateException: boom", "\tat com.mio.irondome.impacto.DependencyPanel.<init>(DependencyPanel.kt:72)"),
        )

        val text = Compatibility.markdown(env, Compatibility.evaluate(env), listOf(error))

        assertTrue(text.contains("| Raio de Impacto | Indisponível |"))
        assertTrue(text.contains("### 2026-11-03 13:00:00 · WARN: A aba Raio de Impacto"))
        assertTrue(text.contains("com.mio.irondome.impacto.DependencyPanel.<init>"))
    }

    @Test
    fun missingIdeApisTurnTheMechanismOffAndOnlyInform() {
        val env = healthy.copy(ideBuild = "252.23892.409", missingApis = mapOf("Patrulha" to listOf("login do GitHub com token")))

        val patrol = check(env, Mechanism.PATRULHA)

        assertEquals(CheckStatus.ERROR, patrol.status)
        assertTrue(patrol.detail.contains("Desligado nesta versão do IntelliJ (build 252.23892.409)"))
        assertFalse(patrol.fix!!.contains("Atualize"))
        assertEquals(listOf(Mechanism.PATRULHA), Compatibility.limited(env))
        val message = Compatibility.limitedMessage(env)
        assertTrue(message.contains("Patrulha, que usa login do GitHub com token"))
        assertFalse(message.contains("Atualize"))
        assertTrue(env.olderThanTested)
        assertEquals(CheckStatus.OK, check(env, Mechanism.RADAR).status)
    }

    @Test
    fun aTabThatFailedForLackOfAnIdeApiIsReported() {
        val env = healthy.copy(
            tabFailures = mapOf("Escudo" to "NoSuchMethodError: setVMParameters"),
            ideVersionFailures = setOf("Escudo"),
        )

        val shield = check(env, Mechanism.ESCUDO)

        assertTrue(shield.detail.contains("esta versão do IntelliJ não tem"))
        assertTrue(shield.fix!!.contains("Reportar Bug"))
        assertTrue(Compatibility.limited(env).isEmpty())
    }

    @Test
    fun probeReportsMissingClassesAndMethodsAndSkipsInactivePlugins() {
        val requirements = listOf(
            ApiRequirement("Radar", "lista", "java.util.ArrayList", "add"),
            ApiRequirement("Radar", "método inexistente", "java.util.ArrayList", "voar"),
            ApiRequirement("Escudo", "classe inexistente", "com.exemplo.NaoExiste"),
            ApiRequirement("Patrulha", "plugin desativado", "com.exemplo.Outra", plugin = "desativado"),
        )

        val missing = IdeSupport.probe(requirements) { plugin -> if (plugin == "desativado") null else javaClass.classLoader }

        assertEquals(mapOf("Radar" to listOf("método inexistente"), "Escudo" to listOf("classe inexistente")), missing)
    }

    @Test
    fun buildsAreComparedByTheTestedParts() {
        assertTrue(IdeSupport.isOlder("252.23892.409", "252.28539"))
        assertTrue(IdeSupport.isOlder("251.1", "252.28539"))
        assertFalse(IdeSupport.isOlder("252.28539.97", "252.28539"))
        assertFalse(IdeSupport.isOlder("253.100", "252.28539"))
    }

    @Test
    fun linkageErrorsComeFromTheIdeVersion() {
        assertTrue(TabFailures.isIdeVersionError(NoSuchMethodError("x")))
        assertTrue(TabFailures.isIdeVersionError(IllegalStateException("embrulho", NoClassDefFoundError("y"))))
        assertFalse(TabFailures.isIdeVersionError(IllegalStateException("outro")))
    }

    @Test
    fun tabFailureDescriptionUsesTheFirstLineOfTheMessage() {
        assertEquals("IllegalStateException: primeira", TabFailures.describe(IllegalStateException("primeira\nsegunda")))
        assertEquals("NoClassDefFoundError", TabFailures.describe(NoClassDefFoundError()))
    }
}
