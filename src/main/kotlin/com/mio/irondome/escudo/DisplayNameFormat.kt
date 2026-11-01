package com.mio.irondome.escudo

/** Como a aba Escudo destaca cada trecho do nome de um teste. */
enum class FragmentStyle {
    TEXT,
    /** "deve", "should": o que o teste espera. */
    VERB,
    /** "não deve", "should not": a expectativa negativa. */
    NEGATION,
    /** Entre crases ou aspas. */
    CODE,
    NUMBER,
    /** Nome de exceção, como `IllegalArgumentException`. */
    ERROR,
    ARROW,
    /** O que vem depois de uma seta (`->`, `=>`, `→`): o resultado esperado. */
    RESULT,
}

class Fragment(val text: String, val style: FragmentStyle)

/** As etapas do formato dado/quando/então, em português ou inglês. */
enum class StepRole(val label: String) {
    GIVEN("Dado"),
    WHEN("Quando"),
    THEN("Então"),
}

/** Um trecho do nome; [keyword] é a palavra que abre a etapa, como foi escrita, e nula no trecho antes da primeira. */
class Step(val keyword: String?, val role: StepRole?, val fragments: List<Fragment>)

class Parameter(val name: String?, val value: String)

/**
 * O `@DisplayName` de um teste quebrado nas partes que a aba Escudo destaca. [index] e [parameters] vêm do nome
 * padrão das execuções de um teste parametrizado (`[1] cep=01001-000, valor=10`); [key], de uma chave no início
 * (`[Frete] calcula o frete`); [steps], das palavras dado/quando/então (given/when/then).
 */
class DisplayNameParts(val index: Int?, val key: String?, val steps: List<Step>, val parameters: List<Parameter>) {

    /** Tem etapas dado/quando/então ou parâmetros, que valem uma explicação nos detalhes. */
    val structured: Boolean get() = parameters.isNotEmpty() || steps.any { it.role != null }
}

object DisplayNameFormat {

    private val invocation = Regex("""^\s*\[(\d+)]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val leadingKey = Regex("""^\s*\[\s*([^\]\d\s][^\]]*?)\s*]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
    /** "Dado" sozinho também quer dizer "informação" (os dados do cliente): só abre etapa no início do nome. */
    private val keyword = Regex(
        """(?<![\p{L}\p{N}_])(?:dad[oa]s? que|given|quando|when|então|entao|then)(?![\p{L}\p{N}_])|^dad[oa]s?(?![\p{L}\p{N}_])""",
        RegexOption.IGNORE_CASE,
    )
    private val verb = Regex(
        """^(não deve|nao deve|não devem|nao devem|should not|shouldn't|must not|deve|devem|should|must)(?![\p{L}\p{N}_])""",
        RegexOption.IGNORE_CASE,
    )
    private val arrow = Regex("""\s*(->|=>|→|⇒)\s*""")
    private val token = Regex(
        listOf(
            """`(?<backtick>[^`]+)`""",
            """(?<quoted>"[^"]+"|“[^”]+”|(?<![\p{L}])'[^']+'(?![\p{L}]))""",
            """(?<error>(?<![\p{L}\p{N}_])[A-Z][\p{L}\p{N}_]*(?:Exception|Error)(?![\p{L}\p{N}_]))""",
            """(?<number>(?<![\p{L}\p{N}_.,])(?:R\$\s?)?-?\d+(?:[.,]\d+)*%?(?![\p{L}\p{N}_]))""",
        ).joinToString("|"),
    )
    private const val SEPARATORS = " ,;:-–—|"

    fun parse(label: String): DisplayNameParts {
        invocation.matchEntire(label)?.let { match ->
            val arguments = match.groupValues[2].trim()
            return DisplayNameParts(match.groupValues[1].toInt(), null, emptyList(), if (arguments.isEmpty()) emptyList() else parameters(arguments))
        }
        val keyed = leadingKey.matchEntire(label)
        val text = keyed?.groupValues?.get(2) ?: label
        return DisplayNameParts(null, keyed?.groupValues?.get(1), steps(text.trim()), emptyList())
    }

    private fun steps(text: String): List<Step> {
        val matches = keyword.findAll(text).toList()
        if (matches.isEmpty()) return listOf(Step(null, null, fragments(text, verbAtStart = true)))
        val steps = mutableListOf<Step>()
        val before = text.substring(0, matches.first().range.first).trimEnd(*SEPARATORS.toCharArray())
        if (before.isNotBlank()) steps += Step(null, null, fragments(before, verbAtStart = true))
        matches.forEachIndexed { i, match ->
            val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
            val body = text.substring(match.range.last + 1, end).trim(*SEPARATORS.toCharArray())
            steps += Step(match.value, roleOf(match.value), fragments(body, verbAtStart = true))
        }
        return steps
    }

    private fun roleOf(word: String): StepRole = when (word.lowercase()) {
        "quando", "when" -> StepRole.WHEN
        "então", "entao", "then" -> StepRole.THEN
        else -> StepRole.GIVEN
    }

    /** Texto em trechos: verbo de expectativa no início, código, números, exceções e o resultado depois da seta. */
    fun fragments(text: String, verbAtStart: Boolean = false): List<Fragment> {
        if (text.isEmpty()) return emptyList()
        val split = arrow.find(text)
        if (split != null && split.range.first > 0 && split.range.last < text.length - 1) {
            val result = inline(text.substring(split.range.last + 1), verbAtStart = false)
                .map { if (it.style == FragmentStyle.TEXT) Fragment(it.text, FragmentStyle.RESULT) else it }
            return inline(text.substring(0, split.range.first), verbAtStart) + Fragment(" → ", FragmentStyle.ARROW) + result
        }
        return inline(text, verbAtStart)
    }

    private fun inline(text: String, verbAtStart: Boolean): List<Fragment> {
        val fragments = mutableListOf<Fragment>()
        var rest = text
        if (verbAtStart) verb.find(rest)?.let { match ->
            val negative = match.value.lowercase().let { it.startsWith("não") || it.startsWith("nao") || " not" in it || "n't" in it }
            fragments += Fragment(match.value, if (negative) FragmentStyle.NEGATION else FragmentStyle.VERB)
            rest = rest.substring(match.value.length)
        }
        var position = 0
        for (match in token.findAll(rest)) {
            if (match.range.first > position) fragments += Fragment(rest.substring(position, match.range.first), FragmentStyle.TEXT)
            val groups = match.groups
            fragments += when {
                groups["backtick"] != null -> Fragment(groups["backtick"]!!.value, FragmentStyle.CODE)
                groups["quoted"] != null -> Fragment(match.value, FragmentStyle.CODE)
                groups["error"] != null -> Fragment(match.value, FragmentStyle.ERROR)
                else -> Fragment(match.value, FragmentStyle.NUMBER)
            }
            position = match.range.last + 1
        }
        if (position < rest.length) fragments += Fragment(rest.substring(position), FragmentStyle.TEXT)
        return fragments
    }

    /** Argumentos separados por vírgula fora de aspas e parênteses; `nome=valor` vira um parâmetro com nome. */
    private fun parameters(arguments: String): List<Parameter> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (char in arguments) {
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char in "([{" -> depth++
                char in ")]}" -> depth = maxOf(0, depth - 1)
                char == ',' && depth == 0 -> {
                    parts += current.toString().trim()
                    current.clear()
                    continue
                }
            }
            current.append(char)
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }.map { part ->
            val name = part.substringBefore('=', "").trim()
            if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' } && !name.first().isDigit()) {
                Parameter(name, part.substringAfter('=').trim())
            } else {
                Parameter(null, part)
            }
        }
    }
}
