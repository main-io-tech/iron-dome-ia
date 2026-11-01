package com.mio.irondome.escudo

import com.mio.irondome.escudo.FragmentStyle.ARROW
import com.mio.irondome.escudo.FragmentStyle.CODE
import com.mio.irondome.escudo.FragmentStyle.ERROR
import com.mio.irondome.escudo.FragmentStyle.NEGATION
import com.mio.irondome.escudo.FragmentStyle.NUMBER
import com.mio.irondome.escudo.FragmentStyle.RESULT
import com.mio.irondome.escudo.FragmentStyle.TEXT
import com.mio.irondome.escudo.FragmentStyle.VERB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameFormatTest {

    @Test
    fun splitsTheStepsOfAScenario() {
        val name = DisplayNameFormat.parse("deve cobrar frete de 10 quando o pedido passa de R$ 200, então é grátis")

        assertEquals(listOf(null, StepRole.WHEN, StepRole.THEN), name.steps.map { it.role })
        assertEquals(listOf(null, "quando", "então"), name.steps.map { it.keyword })
        assertEquals(listOf(VERB to "deve", TEXT to " cobrar frete de ", NUMBER to "10"), fragments(name.steps[0]))
        assertEquals(listOf(TEXT to "o pedido passa de ", NUMBER to "R$ 200"), fragments(name.steps[1]))
        assertEquals(listOf(TEXT to "é grátis"), fragments(name.steps[2]))
        assertTrue(name.structured)
    }

    @Test
    fun understandsEnglishScenarios() {
        val name = DisplayNameFormat.parse("Given an empty cart when the user pays then IllegalStateException is thrown")

        assertEquals(listOf(StepRole.GIVEN, StepRole.WHEN, StepRole.THEN), name.steps.map { it.role })
        assertEquals(listOf(ERROR to "IllegalStateException", TEXT to " is thrown"), fragments(name.steps[2]))
    }

    @Test
    fun dadoOnlyOpensAStepAtTheStartOrAsDadoQue() {
        val plain = DisplayNameFormat.parse("valida os dados do cliente")
        assertEquals(listOf<StepRole?>(null), plain.steps.map { it.role })
        assertFalse(plain.structured)

        assertEquals(listOf(StepRole.GIVEN), DisplayNameFormat.parse("Dados válidos").steps.map { it.role })
        val dadoQue = DisplayNameFormat.parse("Dado que o cliente existe, quando busca")
        assertEquals(listOf("Dado que", "quando"), dadoQue.steps.map { it.keyword })
        assertEquals(listOf(TEXT to "o cliente existe"), fragments(dadoQue.steps[0]))
    }

    @Test
    fun highlightsCodeNumbersAndTheExpectedResult() {
        assertEquals(
            listOf(TEXT to "soma ", NUMBER to "2", TEXT to " e ", NUMBER to "3", ARROW to " → ", NUMBER to "5"),
            styled("soma 2 e 3 -> 5"),
        )
        assertEquals(listOf(TEXT to "lista vazia", ARROW to " → ", RESULT to "retorna zero"), styled("lista vazia => retorna zero"))
        assertEquals(
            listOf(TEXT to "rejeita ", CODE to "null", TEXT to " e ", CODE to "\"abc\"", TEXT to " sem quebrar o don't"),
            styled("rejeita `null` e \"abc\" sem quebrar o don't"),
        )
        assertEquals(listOf(TEXT to "versão v2 do CEP ", NUMBER to "01001"), styled("versão v2 do CEP 01001"))
    }

    @Test
    fun negativeExpectationsStandOut() {
        val name = DisplayNameFormat.parse("não deve aceitar cupom vencido")
        assertEquals(NEGATION to "não deve", fragments(name.steps.single()).first())
        assertEquals(VERB to "should", fragments(DisplayNameFormat.parse("should return 0").steps.single()).first())
    }

    @Test
    fun readsTheNameJUnitGivesToParameterizedInvocations() {
        val name = DisplayNameFormat.parse("[1] cep=01001-000, valor=10.0")
        assertEquals(1, name.index)
        assertEquals(listOf("cep" to "01001-000", "valor" to "10.0"), name.parameters.map { it.name to it.value })
        assertTrue(name.steps.isEmpty())

        val unnamed = DisplayNameFormat.parse("[2] \"a, b\", 3")
        assertEquals(listOf(null to "\"a, b\"", null to "3"), unnamed.parameters.map { it.name to it.value })
    }

    @Test
    fun aLeadingKeyBecomesALabel() {
        val name = DisplayNameFormat.parse("[Frete] cobra frete grátis")
        assertEquals("Frete", name.key)
        assertNull(name.index)
        assertEquals(listOf(TEXT to "cobra frete grátis"), fragments(name.steps.single()))
    }

    private fun fragments(step: Step) = step.fragments.map { it.style to it.text }

    private fun styled(text: String) = DisplayNameFormat.fragments(text).map { it.style to it.text }
}
