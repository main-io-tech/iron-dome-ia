package com.mio.irondome.patrulha

import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatrulhaTest {

    @Test
    fun readsTheRepositoryFromHttpsSshAndScpRemotes() {
        val expected = GitHubRepo("github.com", "main-io-tech", "iron-dome-ia")

        assertEquals(expected, GitHubRepo.fromRemoteUrl("https://github.com/main-io-tech/iron-dome-ia.git"))
        assertEquals(expected, GitHubRepo.fromRemoteUrl("git@github.com:main-io-tech/iron-dome-ia.git"))
        assertEquals(expected, GitHubRepo.fromRemoteUrl("ssh://git@github.com/main-io-tech/iron-dome-ia"))
        assertEquals("ghe.empresa.com", GitHubRepo.fromRemoteUrl("https://ghe.empresa.com/time/app.git")?.host)
        assertNull(GitHubRepo.fromRemoteUrl("https://gitlab.com/grupo/sub/projeto.git"))
    }

    @Test
    fun commentableLinesAreTheNewSideOfEachHunk() {
        val patch = """
            @@ -10,4 +10,5 @@ fun a() {
             contexto
            -removida
            +nova 1
            +nova 2
             contexto
            @@ -40,2 +41,2 @@
             outra
            \ No newline at end of file
        """.trimIndent()

        assertEquals(setOf(10, 11, 12, 13, 41), DiffLines.rightSide(patch))
        assertTrue(DiffLines.rightSide(null).isEmpty())
    }

    @Test
    fun markdownRoundTripKeepsRequestsAndReadsTheAiVerdict() {
        val record = PullRequestRecord(
            number = 12, title = "Frete por peso", url = "https://github.com/o/r/pull/12", headRef = "frete", baseRef = "main",
            requests = listOf(
                request("P1", code = CodeStatus.CHANGED, thread = ThreadStatus.RESOLVED, lastReply = "@ana: \"feito\""),
                request("P2", commentId = null),
            ),
            checkedAt = "2026-11-03T14:00:00",
        )
        val written = ChangeRequestMarkdown.render(record)
            .replace("- [ ] **Atendido (IA)**\n\n**Parecer da IA:** _ainda não avaliado_", "- [x] **Atendido (IA)**\n\n**Parecer da IA:** validação adicionada")

        val parsed = ChangeRequestMarkdown.parse(written)!!

        assertEquals(12, parsed.number)
        assertEquals("frete", parsed.headRef)
        assertEquals(listOf("P1", "P2"), parsed.requests.map { it.id })
        val first = parsed.requests[0]
        assertEquals(CodeStatus.CHANGED, first.code)
        assertEquals(ThreadStatus.RESOLVED, first.thread)
        assertEquals("val total = itens.sum()", first.snippet)
        assertEquals(AiVerdict.DONE, first.ai)
        assertEquals("validação adicionada", first.aiNote)
        assertEquals(ThreadStatus.NONE, parsed.requests[1].thread)
    }

    @Test
    fun anUncheckedBoxWithAnOpinionMeansNotDone() {
        val markdown = ChangeRequestMarkdown.render(PullRequestRecord(3, "t", "u", "h", "b", listOf(request("P1"))))
            .replace("_ainda não avaliado_", "o trecho continua igual")

        val request = ChangeRequestMarkdown.parse(markdown)!!.requests.single()

        assertEquals(AiVerdict.NOT_DONE, request.ai)
        assertEquals("o trecho continua igual", request.aiNote)
    }

    @Test
    fun codeCheckComparesTheSnippetWithTheCurrentHead() {
        val request = request("P1", startLine = 2, endLine = 2)

        assertEquals(CodeStatus.NO_NEW_COMMITS, CodeCheck.check(request, "abc", "x", null))
        assertEquals(CodeStatus.UNCHANGED, CodeCheck.check(request, "def", "a\nval total = itens.sum()\r\nb", null))
        assertEquals(CodeStatus.MOVED, CodeCheck.check(request, "def", "a\nb\nc\nval total = itens.sum()", null))
        assertEquals(CodeStatus.CHANGED, CodeCheck.check(request, "def", "a\nval total = itens.sumOf { it.preco }", null))
        assertEquals(CodeStatus.FILE_REMOVED, CodeCheck.check(request, "def", null, null))
        assertEquals(CodeStatus.NOT_APPLICABLE, CodeCheck.check(request.copy(target = CommentTarget.PACKAGE), "def", "a", null))
        assertEquals(CodeStatus.CHANGED, CodeCheck.check(request.copy(target = CommentTarget.FILE), "def", "novo", "antigo"))
    }

    @Test
    fun threadCheckIgnoresRepliesFromTheReviewer() {
        val request = request("P1", commentId = 77)
        val mine = ThreadReply("eu", "lembrete", "t1")
        val theirs = ThreadReply("ana", "Corrigido\nno commit x", "t2")

        assertEquals(ThreadStatus.OPEN, ThreadCheck.check(request, listOf(ReviewThread(77, false, false, listOf(mine))), "eu").first)
        val (replied, summary) = ThreadCheck.check(request, listOf(ReviewThread(77, false, false, listOf(theirs, mine))), "eu")
        assertEquals(ThreadStatus.REPLIED, replied)
        assertEquals("@ana: \"Corrigido no commit x\"", summary)
        assertEquals(ThreadStatus.RESOLVED, ThreadCheck.check(request, listOf(ReviewThread(77, true, false, emptyList())), "eu").first)
        assertEquals(ThreadStatus.NONE, ThreadCheck.check(request.copy(commentId = null), emptyList(), "eu").first)
    }

    @Test
    fun planPutsEachCommentWhereGitHubAcceptsIt() {
        val files = listOf(ChangedFile("src/Frete.kt", "modified", "@@ -1,2 +1,3 @@\n a\n+b\n c"))
        val inDiff = ReviewComment(id = "P1", filePath = "src/Frete.kt", startLine = 0, endLine = 1, text = "nas linhas")
        val outsideDiff = ReviewComment(id = "P2", filePath = "src/Frete.kt", startLine = 9, endLine = 9, snippet = "fora", text = "fora do diff")
        val wholeFile = ReviewComment(id = "P3", filePath = "src/Frete.kt", text = "arquivo", target = CommentTarget.FILE)
        val otherFile = ReviewComment(id = "P4", filePath = "src/Outro.kt", text = "outro", target = CommentTarget.FILE)
        val pkg = ReviewComment(id = "P5", filePath = "src/pedidos", text = "pacote", target = CommentTarget.PACKAGE)

        val planned = ReviewPlan.plan(listOf(inDiff, outsideDiff, wholeFile, otherFile, pkg), files).associateBy { it.comment.id }

        assertEquals(Placement.INLINE, planned.getValue("P1").placement)
        assertEquals(1, planned.getValue("P1").startLine)
        assertEquals(2, planned.getValue("P1").line)
        assertTrue(planned.getValue("P1").body.contains(ReviewPlan.marker("P1")))
        assertEquals(Placement.FILE, planned.getValue("P2").placement)
        assertTrue(planned.getValue("P2").body.contains("**Linha 10** (fora do diff)"))
        assertEquals(Placement.FILE, planned.getValue("P3").placement)
        assertEquals(Placement.BODY, planned.getValue("P4").placement)
        assertEquals(Placement.BODY, planned.getValue("P5").placement)
        val body = ReviewPlan.reviewBody("Olhei o PR.", planned.values.toList())
        assertTrue(body.startsWith("Olhei o PR."))
        assertTrue(body.contains("- **P5** · `src/pedidos`, pacote inteiro: pacote"))
    }

    @Test
    fun suggestedCodeIsAGitHubSuggestionInTheDiffAndAnExampleOutsideIt() {
        val files = listOf(ChangedFile("src/Frete.kt", "modified", "@@ -1,2 +1,3 @@\n a\n+b\n c"))
        val code = "    val total = itens.sumOf { it.peso }"
        val inDiff = ReviewComment(id = "P1", filePath = "src/Frete.kt", startLine = 1, endLine = 1, text = "use o peso", suggestion = code)
        val outsideDiff = ReviewComment(id = "P2", filePath = "src/Frete.kt", startLine = 9, endLine = 9, snippet = "fora", text = "fora", suggestion = code)
        val otherFile = ReviewComment(id = "P3", filePath = "src/Outro.kt", text = "outro", target = CommentTarget.FILE, suggestion = "fun a() = 1")

        val planned = ReviewPlan.plan(listOf(inDiff, outsideDiff, otherFile), files).associateBy { it.comment.id }

        assertTrue(planned.getValue("P1").body.startsWith("use o peso\n\n```suggestion\n$code\n```"))
        assertTrue(planned.getValue("P2").body.contains("**Como sugiro que fique:**\n\n```kt\n$code\n```"))
        assertTrue("suggestion" !in planned.getValue("P2").body)
        assertTrue(ReviewPlan.reviewBody("", planned.values.toList()).contains("  ```kt\n  fun a() = 1\n  ```"))
    }

    @Test
    fun suggestionSurvivesTheMarkdownAndMarksTheCodeAsSuggested() {
        val sent = request("P1", startLine = 2, endLine = 2).copy(suggestion = "val total = itens.sumOf { it.peso }")
        val markdown = ChangeRequestMarkdown.render(PullRequestRecord(3, "t", "u", "h", "b", listOf(sent)))

        val parsed = ChangeRequestMarkdown.parse(markdown)!!.requests.single()

        assertTrue(markdown.contains("**Código sugerido:**"))
        assertEquals(sent.suggestion, parsed.suggestion)
        assertEquals(CodeStatus.AS_SUGGESTED, CodeCheck.check(parsed, "def", "a\nval total = itens.sumOf { it.peso }\nb", null))
        assertEquals(CodeStatus.CHANGED, CodeCheck.check(parsed, "def", "a\nval total = calcula()\nb", null))
        assertTrue(parsed.copy(code = CodeStatus.AS_SUGGESTED).likelyDone)
    }

    private fun request(
        id: String,
        startLine: Int = 5,
        endLine: Int = 5,
        commentId: Long? = 1,
        code: CodeStatus = CodeStatus.NOT_CHECKED,
        thread: ThreadStatus? = null,
        lastReply: String? = null,
    ) = ChangeRequest(
        id = id, path = "src/Frete.kt", target = CommentTarget.LINES, startLine = startLine, endLine = endLine,
        snippet = "val total = itens.sum()", text = "Considere o peso", commit = "abc", commentId = commentId,
        url = null, sentAt = "2026-11-03T13:00:00", code = code,
    ).let { if (thread != null) it.copy(thread = thread, lastReply = lastReply) else it }
}
