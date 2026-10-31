package com.mio.review.dependencies

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.util.concurrent.TimeUnit

class DependencyAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var testRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package app.data; public interface Repository { }")
        myFixture.addClass("package app.data; public class SqlRepository implements Repository { }")
        myFixture.addClass(
            """
            package app.core;
            import app.data.Repository;
            public class Service {
                private final Repository repository;
                private final Helper helper = new Helper();
                public Service(Repository repository) { this.repository = repository; }
                public static Service create() { return null; }
                static class Nested { Formatter formatter; }
            }
            """.trimIndent(),
        )
        myFixture.addClass("package app.core; public class Helper { Service service; }")
        myFixture.addClass("package app.core; public abstract class Formatter { }")
        myFixture.addClass("package app.web; public class Controller { app.core.Service service = app.core.Service.create(); }")
        myFixture.addClass("package app.web; public class Unrelated { String text; }")

        testRoot = myFixture.tempDirFixture.findOrCreateDir("tests")
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        myFixture.addFileToProject("tests/app/core/ServiceTest.java", "package app.core; public class ServiceTest { Service service; }")
        myFixture.addFileToProject("tests/app/web/FlowTest.java", "package app.web; public class FlowTest { app.core.Helper helper; }")
    }

    override fun tearDown() {
        try {
            PsiTestUtil.removeSourceRoot(module, testRoot)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testEfferentAndAfferentAroundFocus() {
        val graph = analyze("app.core.Service", depth = 1)
        val names = graph.nodes.map { it.qualifiedName }.toSet()

        assertEquals(
            setOf("app.core.Service", "app.data.Repository", "app.core.Helper", "app.core.Formatter", "app.web.Controller"),
            names,
        )
        val service = graph.nodes.single { it.qualifiedName == "app.core.Service" }
        assertEquals(0, service.level)
        assertEquals("Eferentes: Repository, Helper e Formatter (via classe aninhada)", 3, service.efferent)
        assertEquals("Aferentes: Helper e Controller, sem contar ServiceTest", 2, service.afferent)
    }

    fun testCycleBetweenServiceAndHelper() {
        val graph = analyze("app.core.Service", depth = 1)

        assertEquals(1, graph.cycleCount)
        assertEquals(setOf("app.core.Service", "app.core.Helper"), graph.cycles.single())
        assertTrue(graph.edges.single { it.from == "app.core.Helper" && it.to == "app.core.Service" }.inCycle)
        assertFalse(graph.edges.single { it.from == "app.web.Controller" }.inCycle)
    }

    fun testTestsFoundSeparatelyFromProduction() {
        val graph = analyze("app.core.Service", depth = 1)
        val byName = graph.nodes.associateBy { it.name }

        assertFalse(graph.nodes.any { it.name.endsWith("Test") })
        assertEquals(listOf("app.core.ServiceTest"), byName.getValue("Service").tests.testClasses)
        assertEquals("ServiceTest é um teste dedicado", TestCoverage.GOOD, byName.getValue("Service").tests.coverage)
        assertEquals(TestCoverage.PARTIAL, byName.getValue("Helper").tests.coverage)
        assertEquals(TestCoverage.NONE, byName.getValue("Repository").tests.coverage)
    }

    fun testTestClassIsNotReportedWithoutTests() {
        val graph = analyze("app.core.ServiceTest", depth = 1)
        val focus = graph.nodes.single { it.qualifiedName == "app.core.ServiceTest" }

        assertEquals(TestCoverage.TEST_CLASS, focus.tests.coverage)
        assertFalse(focus.needsAttention)
        assertEquals(TestCoverage.GOOD, graph.nodes.single { it.name == "Service" }.tests.coverage)
    }

    fun testChangeRisk() {
        val byName = analyze("app.core.Service", depth = 1).nodes.associateBy { it.name }

        assertEquals("2 dependentes ficaria baixo, mas o ciclo sobe um nível", ChangeRisk.MEDIUM, byName.getValue("Service").risk)
        assertFalse(byName.getValue("Service").needsAttention)
        assertEquals(ChangeRisk.LOW, byName.getValue("Controller").risk)

        assertEquals(ChangeRisk.LOW, ChangeRisk.of(ChangeRisk.MEDIUM_FROM - 1, inCycle = false))
        assertEquals(ChangeRisk.MEDIUM, ChangeRisk.of(ChangeRisk.MEDIUM_FROM, inCycle = false))
        assertEquals(ChangeRisk.HIGH, ChangeRisk.of(ChangeRisk.HIGH_FROM, inCycle = false))
        assertEquals(ChangeRisk.HIGH, ChangeRisk.of(ChangeRisk.HIGH_FROM, inCycle = true))
    }

    fun testInheritanceEdgeAndKinds() {
        val graph = analyze("app.data.SqlRepository", depth = 1)

        val edge = graph.edges.single { it.from == "app.data.SqlRepository" && it.to == "app.data.Repository" }
        assertEquals(EdgeKind.INHERITANCE, edge.kind)
        assertEquals(ClassKind.INTERFACE, graph.nodes.single { it.name == "Repository" }.kind)
    }

    fun testEfferentDirectionOnly() {
        val graph = analyze("app.core.Service", depth = 1, direction = Direction.EFFERENT)

        assertFalse(graph.nodes.any { it.qualifiedName == "app.web.Controller" })
    }

    fun testProjectClassesListsTopLevelClasses() {
        val classes = inBackground { ProjectClassesPanel.collect(project, EmptyProgressIndicator()) }
        val names = classes.map { it.qualifiedName }

        assertContainsElements(names, "app.core.Service", "app.web.Controller", "app.data.Repository", "app.core.ServiceTest")
        assertDoesntContain(names, "app.core.Service.Nested")
        assertTrue(classes.single { it.name == "ServiceTest" }.test)
        assertFalse(classes.single { it.name == "Service" }.test)
        assertEquals(ClassKind.INTERFACE, classes.single { it.name == "Repository" }.kind)
    }

    private fun analyze(focus: String, depth: Int, direction: Direction = Direction.BOTH): DependencyGraph =
        inBackground { DependencyAnalyzer(project, EmptyProgressIndicator()).analyze(focus, depth, direction) }

    /** A análise usa read actions não bloqueantes, que não podem rodar na EDT do teste. */
    private fun <T> inBackground(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { action() }
        return PlatformTestUtil.waitForFuture(future, TimeUnit.SECONDS.toMillis(60))
    }
}
