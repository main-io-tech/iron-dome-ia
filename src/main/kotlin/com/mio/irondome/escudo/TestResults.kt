package com.mio.irondome.escudo

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import java.text.Collator
import java.util.Locale

enum class TestOutcome(val label: String) {
    RUNNING("Executando"),
    FAILED("Falhou"),
    INTERRUPTED("Interrompido"),
    IGNORED("Ignorado"),
    PASSED("Passou"),
    NOT_RUN("Não executado"),
}

enum class TestKind { KEY, CLASS, GROUP, TEST }

/** A convenção do Escudo para as classes de teste: `@DisplayName("[Chave] Descrição")`. */
enum class Convention(val problem: String?) {
    OK(null),
    MISSING_KEY("sem chave"),
    MISSING_DISPLAY_NAME("sem @DisplayName"),
}

/**
 * Nó da árvore do Escudo. [label] é o `@DisplayName` escrito no código, se houver, ou o nome que o JUnit
 * reportou; nas classes de topo, sem a chave. [codeName] é o nome da classe ou do método, nulo quando
 * igual a [label]. [KEY][TestKind.KEY] reúne as classes de uma chave; [GROUP][TestKind.GROUP], as
 * execuções de um teste parametrizado ou uma suíte sem classe própria.
 */
class TestNode(
    val kind: TestKind,
    val key: String,
    val label: String,
    val codeName: String?,
    val locationUrl: String?,
    val durationMillis: Long?,
    val errorMessage: String?,
    val stacktrace: String?,
    val children: List<TestNode>,
    leafOutcome: TestOutcome,
    /** Nas classes de topo e nos grupos de chave: a chave do `@DisplayName`, nula se não houver. */
    val classKey: String? = null,
    /** Só nas classes de topo. */
    val convention: Convention? = null,
) {
    /** Os testes deste nó: ele próprio, se for um teste, ou todos os de baixo. */
    val tests: List<TestNode> = if (kind == TestKind.TEST) listOf(this) else children.flatMap { it.tests }

    val outcome: TestOutcome = if (kind == TestKind.TEST) leafOutcome else outcomeOf(tests, leafOutcome)

    /** Classes de teste de topo deste nó: ele próprio ou as do grupo de chave. */
    val classes: List<TestNode>
        get() = when (kind) {
            TestKind.KEY -> children.filter { it.kind == TestKind.CLASS }
            TestKind.CLASS -> if (convention != null) listOf(this) else emptyList()
            else -> emptyList()
        }

    fun count(outcome: TestOutcome) = tests.count { it.outcome == outcome }

    /** Classe e método no código, como `loja.FreteTest#calcula`. */
    val codeReference: String? get() = locationUrl?.substringAfter("://")?.replace('/', '#')

    private companion object {
        fun outcomeOf(tests: List<TestNode>, own: TestOutcome): TestOutcome {
            val outcomes = tests.map { it.outcome }.toSet()
            return when {
                TestOutcome.FAILED in outcomes -> TestOutcome.FAILED
                TestOutcome.RUNNING in outcomes -> TestOutcome.RUNNING
                TestOutcome.INTERRUPTED in outcomes -> TestOutcome.INTERRUPTED
                TestOutcome.PASSED in outcomes -> TestOutcome.PASSED
                TestOutcome.IGNORED in outcomes -> TestOutcome.IGNORED
                else -> own
            }
        }
    }
}

object TestResults {

    const val NO_KEY = "Sem chave"
    const val CONVENTION_EXAMPLE = "@DisplayName(\"[Chave] Descrição\")"

    private const val SUITE_PROTOCOL = "java:suite://"
    private val keyed = Regex("""^\s*\[\s*([^\]]*?)\s*]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val byLabel = compareBy<TestNode, String>(Collator.getInstance(Locale.of("pt", "BR"))) { it.label }

    /**
     * Classes de teste sob [root], agrupadas pela chave do `@DisplayName` (`[Frete] Cálculo do frete` fica
     * na chave `Frete`), em ordem alfabética, e por último as classes sem chave. Classes `@Nested` ficam
     * dentro da classe que as contém; suítes sem classe, como a do módulo ou a do motor do JUnit, são
     * achatadas. [displayNames] dá o `@DisplayName` do código para a URL de uma classe ou de um método.
     */
    fun build(root: SMTestProxy, displayNames: (String) -> String?): List<TestNode> {
        val (withKey, withoutKey) = topLevel(root, displayNames).partition { it.classKey != null }
        val groups = withKey.groupBy { it.classKey!!.lowercase() }.values
            .map { classes -> keyGroup(spellingOf(classes.mapNotNull { it.classKey }), classes) }
            .sortedWith(byLabel)
        return groups + listOfNotNull(withoutKey.takeIf { it.isNotEmpty() }?.let { keyGroup(null, it) })
    }

    /** A chave e a descrição de um `@DisplayName` no formato `[Chave] Descrição`; sem chave, nula e o texto todo. */
    fun parseKey(displayName: String): Pair<String?, String> {
        val match = keyed.matchEntire(displayName) ?: return null to displayName
        val key = match.groupValues[1].ifBlank { return null to displayName }
        return key to match.groupValues[2].trim().ifEmpty { displayName.trim() }
    }

    /** A chave não diferencia maiúsculas: vale a grafia mais usada e, no empate, a com maiúscula. */
    private fun spellingOf(keys: List<String>): String =
        keys.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key

    private fun keyGroup(key: String?, classes: List<TestNode>) = TestNode(
        kind = TestKind.KEY,
        key = "key:${key?.lowercase().orEmpty()}",
        label = key ?: NO_KEY,
        codeName = null,
        locationUrl = null,
        durationMillis = classes.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum(),
        errorMessage = null,
        stacktrace = null,
        children = classes.sortedWith(byLabel),
        leafOutcome = TestOutcome.NOT_RUN,
        classKey = key,
    )

    private fun topLevel(proxy: SMTestProxy, displayNames: (String) -> String?): List<TestNode> =
        proxy.children.flatMap { child ->
            if (child.isSuite && !isClass(child)) topLevel(child, displayNames)
            else listOf(node(child, "", useCodeName = true, topLevel = true, displayNames))
        }

    /** Execuções de um teste parametrizado repetem a URL do método: ficam com o nome que o JUnit deu. */
    private fun node(
        proxy: SMTestProxy,
        parentKey: String,
        useCodeName: Boolean,
        topLevel: Boolean,
        displayNames: (String) -> String?,
    ): TestNode {
        val url = proxy.locationUrl
        val kind = when {
            isClass(proxy) -> TestKind.CLASS
            proxy.isSuite -> TestKind.GROUP
            else -> TestKind.TEST
        }
        val reported = proxy.presentableName.ifBlank { proxy.name }
        val written = url?.takeIf { useCodeName }?.let(displayNames)
        val codeName = url?.let(::codeNameOf)
        var label = written ?: reported
        var classKey: String? = null
        var convention: Convention? = null
        if (topLevel && kind == TestKind.CLASS) {
            // Sem o código, vale o nome que o JUnit 5 reportou, se for diferente do nome da classe.
            val displayName = written ?: reported.takeIf { it != codeName }
            val (key, description) = displayName?.let(::parseKey) ?: (null to reported)
            classKey = key
            label = description
            convention = when {
                displayName == null -> Convention.MISSING_DISPLAY_NAME
                key == null -> Convention.MISSING_KEY
                else -> Convention.OK
            }
        }
        val key = "$parentKey/${url.orEmpty()}|$label"
        return TestNode(
            kind = kind,
            key = key,
            label = label,
            codeName = codeName?.takeIf { useCodeName && it.removeSuffix("()") != label.removeSuffix("()") },
            locationUrl = url,
            durationMillis = proxy.duration,
            errorMessage = proxy.errorMessage?.takeIf { it.isNotBlank() },
            stacktrace = proxy.stacktrace?.takeIf { it.isNotBlank() },
            children = proxy.children.map { node(it, key, useCodeName = kind == TestKind.CLASS, topLevel = false, displayNames) },
            leafOutcome = outcomeOf(proxy),
            classKey = classKey,
            convention = convention,
        )
    }

    private fun isClass(proxy: SMTestProxy): Boolean {
        val url = proxy.locationUrl ?: return false
        return url.startsWith(SUITE_PROTOCOL) && '/' !in url.removePrefix(SUITE_PROTOCOL)
    }

    private fun codeNameOf(url: String): String {
        val path = url.substringAfter("://")
        val method = path.substringAfter('/', "")
        return if (method.isNotEmpty()) "${method.substringBefore('(')}()"
        else path.substringAfterLast('.').substringAfterLast('$')
    }

    private fun outcomeOf(proxy: SMTestProxy): TestOutcome = when {
        proxy.isInProgress -> TestOutcome.RUNNING
        proxy.isInterrupted -> TestOutcome.INTERRUPTED
        proxy.isIgnored -> TestOutcome.IGNORED
        proxy.isDefect -> TestOutcome.FAILED
        proxy.isPassed -> TestOutcome.PASSED
        else -> TestOutcome.NOT_RUN
    }

    fun formatDuration(millis: Long?): String? = when {
        millis == null -> null
        millis < 1000 -> "$millis ms"
        else -> String.format(Locale.of("pt", "BR"), "%.1f s", millis / 1000.0)
    }
}
