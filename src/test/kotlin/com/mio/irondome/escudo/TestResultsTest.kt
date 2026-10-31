package com.mio.irondome.escudo

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class TestResultsTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var names: JUnitDisplayNames

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.junit.jupiter.api; public @interface DisplayName { String value(); }")
        myFixture.addClass(
            """
            package loja;
            import org.junit.jupiter.api.DisplayName;
            @DisplayName("[Pedido] Cálculo de frete")
            public class FreteTest {
                @DisplayName("cobra frete grátis acima de 200") void gratis() { }
                void semNome() { }
                @DisplayName("Quando o CEP é inválido")
                class CepInvalido {
                    @DisplayName("recusa o pedido") void recusa() { }
                }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            "package loja; @org.junit.jupiter.api.DisplayName(\"[pedido] Cupom de desconto\") public class CupomTest { void aplica() { } }",
        )
        myFixture.addClass(
            "package loja; @org.junit.jupiter.api.DisplayName(\"[Estoque] Reserva de itens\") public class ReservaTest { void reserva() { } }",
        )
        myFixture.addClass(
            "package loja; @org.junit.jupiter.api.DisplayName(\"Nota fiscal\") public class NotaTest { void emite() { } }",
        )
        myFixture.addClass("package loja; public class AbcTest { void um() { } void parametros(int n) { } }")
        names = JUnitDisplayNames(project)
    }

    fun testClassesAreGroupedByTheKeyOfTheirDisplayName() {
        val root = SMTestProxy.SMRootTestProxy()
        val engine = root.child(suite("JUnit Jupiter", null))
        engine.child(testClass("CupomTest", passed("aplica()", "loja.CupomTest/aplica")))
        val frete = engine.child(suite("FreteTest", "java:suite://loja.FreteTest"))
        frete.child(passed("gratis()", "loja.FreteTest/gratis"))
        frete.child(failed("semNome()", "loja.FreteTest/semNome", "esperado 0, veio 15"))
        val nested = frete.child(suite("CepInvalido", "java:suite://loja.FreteTest\$CepInvalido"))
        nested.child(passed("recusa()", "loja.FreteTest\$CepInvalido/recusa"))
        engine.child(testClass("ReservaTest", passed("reserva()", "loja.ReservaTest/reserva")))
        engine.child(testClass("NotaTest", passed("emite()", "loja.NotaTest/emite")))
        engine.child(testClass("AbcTest", passed("um()", "loja.AbcTest/um")))

        val groups = TestResults.build(root, names::of)

        assertEquals(listOf("Estoque", "Pedido", TestResults.NO_KEY), groups.map { it.label })
        assertTrue(groups.all { it.kind == TestKind.KEY })
        val pedido = groups[1]
        assertEquals(listOf("Cálculo de frete", "Cupom de desconto"), pedido.children.map { it.label })
        assertEquals(TestOutcome.FAILED, pedido.outcome)
        assertEquals(2, pedido.classes.size)

        val freteNode = pedido.children[0]
        assertEquals("FreteTest", freteNode.codeName)
        assertEquals(Convention.OK, freteNode.convention)
        assertEquals(
            listOf("cobra frete grátis acima de 200", "semNome()", "Quando o CEP é inválido"),
            freteNode.children.map { it.label },
        )
        assertEquals("gratis()", freteNode.children[0].codeName)
        assertNull(freteNode.children[1].codeName)
        assertEquals(TestKind.CLASS, freteNode.children[2].kind)
        assertNull("classe aninhada não entra na convenção", freteNode.children[2].convention)
        assertEquals("recusa o pedido", freteNode.children[2].children.single().label)
        assertEquals(2, freteNode.count(TestOutcome.PASSED))
        assertEquals(1, freteNode.count(TestOutcome.FAILED))
        assertEquals("esperado 0, veio 15", freteNode.children[1].errorMessage)

        val withoutKey = groups[2].children
        assertEquals(listOf("AbcTest", "Nota fiscal"), withoutKey.map { it.label })
        assertEquals(listOf(Convention.MISSING_DISPLAY_NAME, Convention.MISSING_KEY), withoutKey.map { it.convention })
        assertNull(withoutKey[0].codeName)
    }

    fun testParseKey() {
        assertEquals("Frete" to "Cálculo do frete", TestResults.parseKey("[Frete] Cálculo do frete"))
        assertEquals("Pedido / Frete" to "Cálculo", TestResults.parseKey("  [ Pedido / Frete ]Cálculo"))
        assertEquals("Frete" to "[Frete]", TestResults.parseKey("[Frete]"))
        assertEquals(null to "Cálculo [Frete]", TestResults.parseKey("Cálculo [Frete]"))
        assertEquals(null to "[] Cálculo", TestResults.parseKey("[] Cálculo"))
    }

    fun testParameterizedInvocationsKeepTheNameGivenByJUnit() {
        val root = SMTestProxy.SMRootTestProxy()
        val abc = root.child(suite("AbcTest", "java:suite://loja.AbcTest"))
        val method = abc.child(suite("parametros(int)", "java:test://loja.AbcTest/parametros"))
        method.child(passed("[1] n=1", "loja.AbcTest/parametros"))
        method.child(failed("[2] n=2", "loja.AbcTest/parametros", "falhou"))

        val group = TestResults.build(root, names::of).single().children.single().children.single()

        assertEquals(TestKind.GROUP, group.kind)
        assertEquals(listOf("[1] n=1", "[2] n=2"), group.children.map { it.label })
        assertEquals(TestOutcome.FAILED, group.outcome)
        assertEquals(2, group.tests.size)
    }

    private fun SMTestProxy.child(proxy: SMTestProxy): SMTestProxy = proxy.also(::addChild)

    private fun testClass(name: String, vararg tests: SMTestProxy) =
        suite(name, "java:suite://loja.$name").apply { tests.forEach(::addChild) }

    private fun suite(name: String, url: String?) = SMTestProxy(name, true, url).apply { setSuiteStarted() }

    private fun passed(name: String, path: String) = SMTestProxy(name, false, "java:test://$path").apply {
        setStarted()
        setFinished()
    }

    private fun failed(name: String, path: String, message: String) = SMTestProxy(name, false, "java:test://$path").apply {
        setStarted()
        setTestFailed(message, "at loja.FreteTest.semNome(FreteTest.java:7)", false)
    }
}
