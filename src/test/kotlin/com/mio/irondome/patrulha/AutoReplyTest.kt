package com.mio.irondome.patrulha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyTest {

    private val pr = PullRequest(12, "Frete por peso", "dev", "frete", "abc1234def", "main", "https://github.com/o/r/pull/12", "", false)

    @Test
    fun onlyMyOpenThreadsWithANewAnswerFromSomeoneElseWaitForTheAi() {
        val answered = thread(1, comment(10, "eu"), comment(11, "dev"))
        val mineLast = thread(2, comment(20, "eu"), comment(21, "dev"), comment(22, "eu"))
        val notMine = thread(3, comment(30, "ana"), comment(31, "dev"))
        val resolved = thread(4, comment(40, "eu"), comment(41, "dev"), resolved = true)
        val handled = thread(5, comment(50, "eu"), comment(51, "dev"))
        val stopped = thread(6, comment(60, "Eu"), comment(61, "dev"))
        val draftReply = thread(7, comment(70, "eu"), comment(71, "dev", pending = true))
        val conversation = Discussion(DiscussionKind.CONVERSATION, listOf(comment(80, "eu"), comment(81, "dev")))

        val pending = AutoReply.pending(
            12,
            listOf(answered, mineLast, notMine, resolved, handled, stopped, draftReply, conversation),
            "EU",
            handled = setOf(51L),
            stopped = setOf(AutoReply.key(12, stopped)),
        )

        assertEquals(listOf(answered), pending)
    }

    @Test
    fun theDecisionComesFromTheJsonAndRepliesNeedText() {
        assertEquals(
            AutoAnswer(AutoDecision.CONCLUDE, "Perfeito, obrigado!", "corrigido no head"),
            AutoReply.parse("Pronto:\n```json\n{\"decision\": \"conclude\", \"reply\": \"Perfeito, obrigado!\", \"reason\": \"corrigido no head\"}\n```"),
        )
        assertEquals(AutoDecision.HUMAN, AutoReply.parse("""{"decision": "human", "reply": "", "reason": "regra de negócio"}""")?.decision)
        assertNull("responder sem texto não vale", AutoReply.parse("""{"decision": "reply", "reply": "  "}"""))
        assertNull(AutoReply.parse("""{"decision": "talvez", "reply": "oi"}"""))
        assertNull(AutoReply.parse("sem json"))
    }

    @Test
    fun theInstructionsCarryTheConversationAndTheCodeAroundTheLineAtTheHead() {
        val code = (1..100).joinToString("\n") { "linha $it" }
        val discussion = thread(1, comment(10, "eu", "Isso pode ser nulo?"), comment(11, "dev", "Corrigi no último commit"), line = 50)

        val text = AutoReply.instructions(pr, discussion, "eu", code, request = null, round = 2)

        assertTrue("@eu (revisor)" in text && "Isso pode ser nulo?" in text && "Corrigi no último commit" in text)
        assertTrue("rodada 2 de no máximo ${AutoReply.MAX_ROUNDS}" in text)
        assertTrue("commit `abc1234`" in text)
        assertTrue("\n50| linha 50\n" in text)
        assertTrue("o contexto começa 25 linhas antes", "\n25| linha 25\n" in text && "\n24| linha 24\n" !in text)
        assertTrue("o arquivo que saiu do PR é avisado", "não existe mais" in AutoReply.instructions(pr, discussion, "eu", null, null, 1))
    }

    @Test
    fun theStatusCountsMyOpenThreadsAndTheOnesWaitingForTheDev() {
        val discussions = listOf(
            thread(1, comment(10, "eu"), comment(11, "dev")),
            thread(2, comment(20, "eu"), comment(21, "dev"), comment(22, "Eu")),
            thread(3, comment(30, "eu")),
            thread(4, comment(40, "eu"), resolved = true),
            thread(5, comment(50, "ana"), comment(51, "eu")),
        )

        assertEquals("três abertas minhas, duas com a última palavra minha", 3 to 2, AutoReply.tracked(discussions, "eu"))
    }

    @Test
    fun aRepeatedEventOnlyRefreshesItsTimeAndTheHistoryIsCapped() {
        val nothing = AutoEvent(1, AutoEventKind.CHECKED, "Nada novo")
        val replied = AutoEvent(2, AutoEventKind.REPLIED, "Respondeu @dev")

        val activity = AutoActivity().withEvent(nothing).withEvent(nothing.copy(at = 5)).withEvent(replied).withEvent(nothing.copy(at = 9))

        assertEquals(listOf(9L to AutoEventKind.CHECKED, 2L to AutoEventKind.REPLIED, 5L to AutoEventKind.CHECKED), activity.events.map { it.at to it.kind })
        val full = (1..50).fold(AutoActivity()) { acc, i -> acc.withEvent(AutoEvent(i.toLong(), AutoEventKind.REPLIED, "resposta $i")) }
        assertEquals(AutoActivity.MAX_EVENTS, full.events.size)
        assertEquals("resposta 50", full.events.first().text)
    }

    @Test
    fun theReviewerGuidelinesGoToTheAiReviewAndToTheAutomaticReplies() {
        val guidelines = "Bloqueie só bugs e falta de teste; ignore nomes."
        val discussion = thread(1, comment(10, "eu"), comment(11, "dev"))
        val files = PullRequestFiles("abc1234def", "base", listOf(ChangedFile("src/Frete.kt", "modified", "@@ -1 +1 @@\n-a\n+b")))

        val review = AiReview.instructions(pr, files, guidelines = guidelines)
        val reply = AutoReply.instructions(pr, discussion, "eu", "código", null, 1, guidelines)

        listOf(review, reply).forEach { text ->
            assertTrue("## Como o revisor avalia PRs" in text)
            assertTrue(guidelines in text)
        }
        assertTrue("sem instruções, sem a seção", "Como o revisor avalia" !in AiReview.instructions(pr, files, guidelines = "  "))
        assertEquals("", ReviewerGuidelines.section(""))
    }

    @Test
    fun theFooterSaysTheAnswerCameFromTheAi() {
        assertTrue("@eu" in AutoReply.footer("eu"))
    }

    private fun thread(id: Long, vararg comments: DiscussionComment, resolved: Boolean = false, line: Int? = 5) = Discussion(
        kind = DiscussionKind.THREAD,
        comments = comments.toList(),
        nodeId = "T$id",
        path = "src/Frete.kt",
        line = line,
        resolved = resolved,
    )

    private fun comment(id: Long, author: String, body: String = "texto", pending: Boolean = false) =
        DiscussionComment(id, author, body, "2026-11-03T13:00:00Z", pending = pending)
}
