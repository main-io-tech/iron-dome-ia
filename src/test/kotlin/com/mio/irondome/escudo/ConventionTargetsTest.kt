package com.mio.irondome.escudo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConventionTargetsTest {

    @Test
    fun missingKeyKeepsTheDescriptionAndOffersTheKnownKeys() {
        val text = ConventionTargets.text("FreteTest", Convention.MISSING_KEY, "Cálculo do frete", listOf("Frete", "Pedido"))

        assertTrue(text.contains("`FreteTest`"))
        assertTrue(text.contains("@DisplayName(\"[Chave] Cálculo do frete\")"))
        assertTrue(text.contains("[Frete], [Pedido]"))
        assertTrue(text.contains("sem mexer nos testes"))
    }

    @Test
    fun missingDisplayNameAsksForKeyAndDescription() {
        val text = ConventionTargets.text("PedidoServiceTest", Convention.MISSING_DISPLAY_NAME, "PedidoServiceTest", emptyList())

        assertTrue(text.contains("`PedidoServiceTest` está sem @DisplayName"))
        assertTrue(text.contains("Crie uma chave curta"))
        assertFalse(text.contains("Reaproveite"))
    }
}
