package com.mio.irondome.defesa

import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.psi.PsiManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.mio.irondome.impacto.ImpactCodeVisionProvider
import com.mio.irondome.impacto.ImpactRadar
import com.mio.irondome.impacto.TestCoverage
import com.mio.irondome.radar.ReviewCommentService
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JLabel

/** As defesas automáticas numa IDE de verdade: projeto em disco, PSI do Java, message bus e extensões do plugin.xml. */
class DefenseIntegrationTest : HeavyPlatformTestCase() {

    private lateinit var frete: VirtualFile
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val savedFlags = mutableMapOf<kotlin.reflect.KMutableProperty0<Boolean>, Boolean>()

    override fun setUp() {
        super.setUp()
        listOf(DefenseSettings::commitGuard, DefenseSettings::passiveRadar, DefenseSettings::shieldSuggest, DefenseSettings::shieldAutoRun)
            .forEach { savedFlags[it] = it.get() }
        DefenseSettings.commitGuard = true
        DefenseSettings.passiveRadar = false
        DefenseSettings.shieldSuggest = false
        DefenseSettings.shieldAutoRun = false

        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(project.basePath!!).also(Files::createDirectories))!!
        val src = VfsTestUtil.createDir(root, "src")
        val tests = VfsTestUtil.createDir(root, "tests")
        PsiTestUtil.addSourceRoot(module, src)
        PsiTestUtil.addSourceRoot(module, tests, true)
        frete = VfsTestUtil.createFile(src, "app/Frete.java", "package app;\n\npublic class Frete {\n    public int valor() { return 1; }\n}\n")
        VfsTestUtil.createFile(src, "app/Pedido.java", "package app; public class Pedido { Frete frete; }")
        VfsTestUtil.createFile(src, "app/Carrinho.java", "package app; public class Carrinho { Frete frete; }")
        VfsTestUtil.createFile(tests, "app/PedidoTest.java", "package app; public class PedidoTest { Pedido pedido; }")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        DefenseSettings.passiveRadar = true

        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            savedFlags.forEach { (flag, value) -> flag.set(value) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testEveryDefenseIsRegisteredInThePlugin() {
        assertTrue(extensions("com.intellij.checkinHandlerFactory").any { it is CommitGuardFactory })
        assertTrue(ExtensionPointName<StatusBarWidgetFactory>("com.intellij.statusBarWidgetFactory").extensionList.any { it.id == DefenseStatusWidget.ID })
        assertTrue(extensions("com.intellij.codeInsight.daemonBoundCodeVisionProvider").any { it is ImpactCodeVisionProvider })
        val startups = extensions("com.intellij.postStartupActivity").map { it.javaClass.name }
        assertContainsElements(
            startups,
            "com.mio.irondome.defesa.DefenseStartup",
            "com.mio.irondome.impacto.BackgroundImpactStartup",
            "com.mio.irondome.patrulha.PatrolStartup",
        )
        listOf("IronDome.RunRadiusTests", "IronDome.BackgroundImpactNow", "IronDome.PatrolNow").forEach {
            assertNotNull("ação $it registrada", ActionManager.getInstance().getAction(it))
        }
    }

    fun testRadarComputesTheRadiusOfTheSavedClass() {
        val snapshot = radiusOf(frete)

        assertEquals("app.Frete", snapshot.qualifiedName)
        assertEquals("src/app/Frete.java", snapshot.filePath)
        assertEquals("Pedido e Carrinho dependem de Frete", 2, snapshot.dependents)
        assertEquals("só Pedido tem teste", 1, snapshot.dependentsWithoutTests)
        assertEquals(TestCoverage.NONE, snapshot.coverage)
        assertEquals(listOf("app.PedidoTest"), snapshot.radiusTests)
        assertTrue(snapshot.summary, snapshot.summary.startsWith("afeta 2 classes · 1 sem teste · risco "))
        assertTrue(snapshot.risk.explain().isNotBlank())
    }

    fun testSavingAClassPublishesTheRadiusAndTheShieldSuggestsItsTests() {
        DefenseSettings.shieldSuggest = true
        val status = DefenseStatus.getInstance(project)

        runWriteAction { VfsUtil.saveText(frete, "package app;\n\npublic class Frete {\n    public int valor() { return 2; }\n}\n") }
        waitUntil("o raio passivo publicar o raio de Frete") { status.snapshotOf("src/app/Frete.java") != null }
        waitUntil("o Escudo sugerir os testes do raio") { notifications.any { it.title == "Escudo: testes do raio de Frete" } }

        assertEquals("app.Frete", status.lastSnapshot?.qualifiedName)
        assertNull("só a classe salva entra no radar", status.snapshotOf("src/app/Pedido.java"))
        val suggestion = notifications.first { it.title == "Escudo: testes do raio de Frete" }
        assertTrue(suggestion.content, suggestion.content.contains("PedidoTest"))
        assertContainsElements(suggestion.actions.map { it.templateText }, "Rodar Testes do Raio", "Rodar Sempre Sozinho")
    }

    fun testCodeVisionShowsTheRadiusAboveTheClass() {
        DefenseStatus.getInstance(project).publish(radiusOf(frete))
        val document = FileDocumentManager.getInstance().getDocument(frete)!!
        val editor = EditorFactory.getInstance().createEditor(document, project)
        try {
            val entries = runReadAction {
                ImpactCodeVisionProvider().computeForEditor(editor, PsiManager.getInstance(project).findFile(frete)!!)
            }
            val (range, entry) = entries.single()
            assertTrue((entry as ClickableTextCodeVisionEntry).text, entry.text.startsWith("Raio: afeta 2 classes · 1 sem teste"))
            assertEquals("a linha fica na declaração da classe", 2, document.getLineNumber(range.startOffset))
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testStatusBarShieldFollowsTheDefense() {
        val widget = DefenseStatusWidgetFactory().createWidget(project)
        Disposer.register(testRootDisposable, widget)
        val label = (widget as DefenseStatusWidget).component as JLabel
        val status = DefenseStatus.getInstance(project)

        status.testsFinished(2, 10, "raio de Frete")
        waitUntil("o escudo mostrar os testes falhando") { label.text == "2 teste(s) falhando" }
        assertEquals(DefenseLevel.RED, status.level)
        assertTrue(label.toolTipText, label.toolTipText.contains("2 de 10 testes falharam (raio de Frete)"))

        status.testsFinished(0, 10, null)
        status.patrol(ciFailing = 1, reviewRequests = 0, alerts = emptyList())
        waitUntil("o escudo mostrar a CI quebrada") { label.text == "CI quebrada" }
        assertEquals(DefenseLevel.RED, status.level)

        status.patrol(ciFailing = 0, reviewRequests = 0, alerts = emptyList())
        waitUntil("o escudo voltar ao verde") { label.text == "Defesa" }
        assertEquals(DefenseLevel.GREEN, status.level)
    }

    fun testCommitGuardInterceptsPendingTargetsAndLetsTheUserDecide() {
        comment(frete, 3..3, "o valor do frete está fixo")
        val messages = mutableListOf<String>()

        assertEquals(CheckinHandler.ReturnResult.CANCEL, commitWithAnswer(frete, 2, messages))
        assertTrue(messages.single(), messages.single().contains("alvo não resolvido em Frete.java"))
        assertEquals(CheckinHandler.ReturnResult.COMMIT, commitWithAnswer(frete, 0, messages))

        DefenseSettings.commitGuard = false
        assertEquals("desligada, nem pergunta", CheckinHandler.ReturnResult.COMMIT, commitWithAnswer(frete, 2, messages))
        assertEquals(2, messages.size)
    }

    fun testCommitWithoutRisksGoesThroughWithoutDialog() {
        val messages = mutableListOf<String>()
        assertEquals(CheckinHandler.ReturnResult.COMMIT, commitWithAnswer(frete, 2, messages))
        assertEmpty(messages)
    }

    fun testCommentsOnTheSameLinesAreFlaggedAsConflict() {
        val status = DefenseStatus.getInstance(project)
        comment(frete, 3..3, "extraia o cálculo para um método")
        comment(frete, 3..3, "mantenha o cálculo inline")

        waitUntil("a defesa perceber os comentários no mesmo trecho") { status.conflicts.size == 1 }
        waitUntil("o aviso de conflito chegar") { notifications.any { it.title == "Comentários no mesmo trecho" } }
        assertEquals(2, status.pendingTargets)
        assertContainsElements(notifications.first { it.title == "Comentários no mesmo trecho" }.actions.map { it.templateText }, "Pedir Síntese à IA")
    }

    fun testSettingsPageTurnsEachDefenseOnAndOff() {
        val page = DefenseConfigurable()
        val component = page.createComponent()
        page.reset()
        assertFalse(page.isModified)

        val commitBox = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).first { it.text == "Conferir antes de cada commit" }
        assertTrue(commitBox.isSelected)
        commitBox.isSelected = false
        assertTrue(page.isModified)
        page.apply()

        assertFalse(DefenseSettings.commitGuard)
        assertFalse(page.isModified)
    }

    private fun radiusOf(file: VirtualFile): ClassSnapshot {
        val future = ApplicationManager.getApplication().executeOnPooledThread<ClassSnapshot?> { ImpactRadar.getInstance(project).compute(file) }
        val snapshot = PlatformTestUtil.waitForFuture(future, TimeUnit.SECONDS.toMillis(60))
        assertNotNull("o raio de ${file.name} foi calculado", snapshot)
        return snapshot!!
    }

    private fun comment(file: VirtualFile, lines: IntRange, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertNotNull(project.service<ReviewCommentService>().addComment(file, document, lines, text, discuss = false))
    }

    /** Commita [file] respondendo [answer] ao diálogo (0 commita, 1 revisa com a IA, 2 cancela); guarda a mensagem. */
    private fun commitWithAnswer(file: VirtualFile, answer: Int, messages: MutableList<String>): CheckinHandler.ReturnResult {
        val panel = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CheckinProjectPanel::class.java)) { _, method, _ ->
            when (method.name) {
                "getProject" -> project
                "getVirtualFiles" -> listOf(file)
                else -> null
            }
        } as CheckinProjectPanel
        val previous = TestDialogManager.setTestDialog(TestDialog { message -> messages += message; answer })
        try {
            return CommitGuard(panel).beforeCheckin()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Esperou demais por: $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    private fun extensions(name: String): List<Any> = ExtensionPointName<Any>(name).extensionList
}
