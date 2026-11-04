package com.mio.irondome.patrulha

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.UIUtil
import com.mio.irondome.radar.ReviewComment
import java.awt.Component
import javax.swing.JComponent

class SendReviewDialogTest : BasePlatformTestCase() {

    private val first = ReviewComment(id = "P1", filePath = "src/Frete.kt", startLine = 0, endLine = 0, text = "frete fixo")
    private val second = ReviewComment(id = "P2", filePath = "src/Frete.kt", startLine = 4, endLine = 4, text = "nome confuso")
    private val third = ReviewComment(id = "P3", filePath = "src/Pedido.kt", startLine = 2, endLine = 2, text = "falta teste")
    private val pr = PullRequest(12, "Frete por peso", "ana", "frete", "abc1234567", "main", "u", "", false)

    fun testEveryCommentIsSentByDefault() = withDialog { dialog ->
        assertEquals(listOf(first, second, third), dialog.toSend)
        assertEmpty(dialog.toDelete)
        assertTrue(hasLabel(dialog, "3 para enviar"))
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testEachCommentCanBeSentLeftForLaterOrDeleted() = withDialog { dialog ->
        choose(dialog, row = 1, "Excluir")
        choose(dialog, row = 2, "Depois")

        assertEquals(listOf(first), dialog.toSend)
        assertEquals(listOf(second), dialog.toDelete)
        assertTrue(hasLabel(dialog, "1 para enviar · 1 para depois · 1 para excluir"))
    }

    fun testOnlyDeletingIsEnough() = withDialog { dialog ->
        link(dialog, "Excluir todos").doClick()

        assertEmpty(dialog.toSend)
        assertEquals(listOf(first, second, third), dialog.toDelete)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testNothingChosenCannotBeConfirmed() = withDialog { dialog ->
        link(dialog, "Todos para depois").doClick()

        assertEmpty(dialog.toSend)
        assertEmpty(dialog.toDelete)
        assertFalse(dialog.isOKActionEnabled)

        link(dialog, "Enviar todos").doClick()
        assertEquals(3, dialog.toSend.size)
        assertTrue(dialog.isOKActionEnabled)
    }

    private fun withDialog(test: (SendReviewDialog) -> Unit) {
        val planned = listOf(first, second, third).map { PlannedRequest(it, Placement.INLINE, it.text, null, it.startLine + 1) }
        val dialog = SendReviewDialog(project, pr, planned, warning = null)
        try {
            test(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun choose(dialog: SendReviewDialog, row: Int, label: String) {
        UIUtil.findComponentsOfType(root(dialog), JBRadioButton::class.java).filter { it.text == label }[row].doClick()
    }

    private fun link(dialog: SendReviewDialog, text: String): ActionLink =
        UIUtil.findComponentsOfType(root(dialog), ActionLink::class.java).first { it.text == text }

    private fun hasLabel(dialog: SendReviewDialog, text: String) =
        UIUtil.findComponentsOfType(root(dialog), JBLabel::class.java).any { it.text == text }

    /** Sem janela no modo headless, o painel do diálogo é o topo da hierarquia do campo de texto geral. */
    private fun root(dialog: SendReviewDialog): JComponent =
        generateSequence(dialog.preferredFocusedComponent as Component) { it.parent }.last() as JComponent
}
