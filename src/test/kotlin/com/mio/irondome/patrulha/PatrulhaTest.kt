package com.mio.irondome.patrulha

import com.google.gson.JsonParser
import com.mio.irondome.radar.CommentTarget
import com.mio.irondome.radar.ReviewComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theStashLeavesOutIronDomeAndIdeaWithoutExcludingWhatGitAlreadyIgnores() {
        assertEquals(listOf(".", ":(exclude)iron-dome", ":(exclude).idea"), PatrulhaService.workPaths(emptySet()).toList())
        assertEquals(listOf(".", ":(exclude)iron-dome"), PatrulhaService.workPaths(setOf(".idea")).toList())
        assertEquals(listOf("."), PatrulhaService.workPaths(setOf(".idea", "iron-dome")).toList())
    }

    @Test
    fun theVerdictUsesTheGitHubEventsAndOnlyCommentsAreAllowedOnOwnPullRequests() {
        assertEquals(listOf("APPROVE", "REQUEST_CHANGES", "COMMENT"), ReviewVerdict.entries.map { it.event })
        assertEquals(listOf(ReviewVerdict.COMMENT), ReviewVerdict.entries.filter { it.allowedOnOwnPr })
        assertFalse("aprovar dispensa o texto", ReviewVerdict.APPROVE.needsBody)
        assertTrue(ReviewVerdict.REQUEST_CHANGES.needsBody && ReviewVerdict.COMMENT.needsBody)
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
    fun markdownRoundTripKeepsRequests() {
        val record = PullRequestRecord(
            number = 12, title = "Frete por peso", url = "https://github.com/o/r/pull/12", headRef = "frete", baseRef = "main",
            requests = listOf(
                request("P1", code = CodeStatus.CHANGED, thread = ThreadStatus.RESOLVED, lastReply = "@ana: \"feito\""),
                request("P2", commentId = null),
            ),
            checkedAt = "2026-11-03T14:00:00",
        )
        val written = ChangeRequestMarkdown.render(record)

        val parsed = ChangeRequestMarkdown.parse(written)!!

        assertEquals(12, parsed.number)
        assertEquals("frete", parsed.headRef)
        assertEquals(listOf("P1", "P2"), parsed.requests.map { it.id })
        val first = parsed.requests[0]
        assertEquals(CodeStatus.CHANGED, first.code)
        assertEquals(ThreadStatus.RESOLVED, first.thread)
        assertEquals("val total = itens.sum()", first.snippet)
        assertEquals(ThreadStatus.NONE, parsed.requests[1].thread)
        assertFalse(written.contains("Parecer da IA"))
    }

    @Test
    fun theGitHubDraftSurvivesTheMarkdownAndLeavesTheCountsUntilPublished() {
        val record = PullRequestRecord(
            7, "t", "u", "h", "b",
            listOf(request("P1", code = CodeStatus.CHANGED), request("P2").copy(pending = true), request("P3", commentId = null).copy(pending = true)),
            pendingReviewId = 99,
            pendingReviewNodeId = "PRR_abc",
        )

        val parsed = ChangeRequestMarkdown.parse(ChangeRequestMarkdown.render(record))!!

        assertEquals(99L, parsed.pendingReviewId)
        assertEquals("PRR_abc", parsed.pendingReviewNodeId)
        assertEquals(listOf("P2", "P3"), parsed.drafts.map { it.id })
        assertEquals("rascunhos não contam como atendidos nem pendentes", listOf("P1"), parsed.active.map { it.id })

        val published = parsed.withoutDraft(published = true)
        assertNull(published.pendingReviewId)
        assertEquals(listOf("P1", "P2", "P3"), published.active.map { it.id })

        val discarded = parsed.withoutDraft(published = false)
        assertEquals(listOf("P1"), discarded.requests.map { it.id })
        assertNull(discarded.pendingReviewNodeId)
    }

    @Test
    fun oldMarkdownWithTheAiOpinionStillReads() {
        val old = ChangeRequestMarkdown.render(PullRequestRecord(3, "t", "u", "h", "b", listOf(request("P1")))) +
            "- [x] **Atendido (IA)**\n\n**Parecer da IA:** feito\n"

        assertEquals(listOf("P1"), ChangeRequestMarkdown.parse(old)!!.requests.map { it.id })
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
    fun discussionsBringEveryoneButLeaveOutDraftThreadsAndEmptyReviews() {
        val pullRequest = JsonParser.parseString(
            """
            {
              "reviewThreads": { "nodes": [
                { "id": "T1", "path": "src/Frete.kt", "line": 12, "isResolved": false, "isOutdated": false,
                  "comments": { "nodes": [
                    { "databaseId": 77, "body": "Meu pedido", "createdAt": "2026-11-01T10:00:00Z", "author": { "login": "eu" }, "state": "SUBMITTED" },
                    { "databaseId": 78, "body": "Feito", "createdAt": "2026-11-02T10:00:00Z", "author": { "login": "ana" }, "state": "SUBMITTED" } ] } },
                { "id": "T2", "path": "src/Peso.kt", "line": null, "originalLine": 5, "isResolved": true, "isOutdated": true,
                  "comments": { "nodes": [
                    { "databaseId": 90, "body": "Isso pode ser nulo?", "createdAt": "2026-11-03T09:00:00Z", "author": { "login": "bia" }, "state": "SUBMITTED" } ] } },
                { "id": "T3", "path": "src/Peso.kt", "line": 1,
                  "comments": { "nodes": [
                    { "databaseId": 95, "body": "rascunho", "createdAt": "2026-11-03T11:00:00Z", "author": { "login": "eu" }, "state": "PENDING" } ] } }
              ] },
              "comments": { "nodes": [
                { "databaseId": 100, "body": "Pode subir hoje?", "createdAt": "2026-11-01T08:00:00Z", "author": null } ] },
              "reviews": { "nodes": [
                { "databaseId": 200, "body": "", "state": "APPROVED", "submittedAt": "2026-11-03T12:00:00Z", "author": { "login": "caio" } },
                { "databaseId": 201, "body": "", "state": "COMMENTED", "submittedAt": "2026-11-03T12:00:00Z", "author": { "login": "ana" } },
                { "databaseId": 202, "body": "eu", "state": "PENDING", "author": { "login": "eu" } } ] }
            }
            """.trimIndent(),
        ).asJsonObject

        val discussions = Discussions.parse(pullRequest)

        assertEquals(listOf("review:200", "thread:90", "thread:77", "conversation:100"), discussions.map { it.key })
        val outdated = discussions.first { it.first.id == 90L }
        assertEquals(5, outdated.line)
        assertTrue(outdated.resolved && outdated.outdated)
        assertEquals("fantasma", discussions.first { it.kind == DiscussionKind.CONVERSATION }.first.author)
        assertEquals("APPROVED", discussions.first().reviewState)

        val record = PullRequestRecord(12, "Frete", "u", "frete", "main", listOf(request("P1", commentId = 77)))
        assertEquals(listOf("review:200", "thread:90", "conversation:100"), Discussions.others(discussions, record).map { it.key })
        assertEquals(listOf("Feito"), Discussions.of(record.requests.first(), discussions)?.replies?.map { it.body })
    }

    @Test
    fun repliesOutsideLineThreadsQuoteTheOriginalComment() {
        val to = DiscussionComment(1, "ana", "Pode subir hoje?\nPreciso do deploy.", "2026-11-01T08:00:00Z")

        assertEquals(
            "> **@ana** escreveu:\n> Pode subir hoje?\n> Preciso do deploy.\n\nSim, depois do merge.",
            Discussions.quote(to, "  Sim, depois do merge.\n"),
        )
        assertTrue(Discussion(DiscussionKind.THREAD, listOf(to)).repliesInPlace)
        assertFalse(Discussion(DiscussionKind.CONVERSATION, listOf(to)).repliesInPlace)
        assertTrue(Discussions.isMine(to, "ANA"))
        assertFalse(Discussions.isMine(to, null))
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
    fun changedFilesShowTheirStatusAndThePrTotals() {
        val files = PullRequestFiles(
            "abc", "base",
            listOf(
                ChangedFile("src/Novo.kt", "added", null, additions = 30),
                ChangedFile("src/Frete.kt", "modified", null, additions = 5, deletions = 2),
                ChangedFile("src/Velho.kt", "removed", null, deletions = 40),
                ChangedFile("src/Pedido.kt", "renamed", null, additions = 1, deletions = 1, previousPath = "src/Ordem.kt"),
            ),
        )

        assertEquals(listOf("Novo", "Alterado", "Removido", "Renomeado"), files.files.map { it.statusLabel })
        assertEquals(36, files.additions)
        assertEquals(43, files.deletions)
        assertTrue(files.files[0].added)
        assertTrue(files.files[2].removed)
    }

    @Test
    fun snippetIsFoundWhereTheCodeIsNowOrStaysOnTheRecordedLines() {
        val now = "import a\n\nfun a() {}\nval total = itens.sum()\nfun b() {}\nval total = itens.sum()"

        assertEquals("a ocorrência mais perto das linhas gravadas", 5..5, SnippetLocator.locate(now, "val total = itens.sum()", 6..6))
        assertEquals(3..3, SnippetLocator.locate(now, "val total = itens.sum()", 1..1))
        assertEquals("sem o trecho, as linhas gravadas", 2..3, SnippetLocator.locate(now, "outro", 2..3))
        assertEquals("limitadas ao fim do arquivo", 5..5, SnippetLocator.locate(now, "outro", 40..42))
    }

    @Test
    fun editingRebuildsTheSameGitHubTextAsSending() {
        val files = listOf(ChangedFile("src/Frete.kt", "modified", "@@ -1,2 +1,3 @@\n a\n+b\n c"))
        val comment = ReviewComment(id = "P1", filePath = "src/Frete.kt", startLine = 1, endLine = 1, text = "use o peso", suggestion = "val p = 1")

        val sent = ReviewPlan.plan(listOf(comment), files).single()

        assertEquals(sent.body, ReviewPlan.bodyFor(comment, sent.placement))
    }

    @Test
    fun theGitHubTextGoesBackToTextAndSuggestionInEveryFormat() {
        val code = "    val total = itens.sumOf { it.peso }"
        val inline = ReviewComment(id = "P1", filePath = "src/Frete.kt", startLine = 1, endLine = 1, text = "use o peso", suggestion = code)
        val outside = ReviewComment(id = "P2", filePath = "src/Frete.kt", startLine = 9, endLine = 10, snippet = "a\nb", text = "fora", suggestion = code)
        val wholeFile = ReviewComment(id = "P3", filePath = "src/Frete.kt", text = "arquivo", target = CommentTarget.FILE, suggestion = "fun a() = 1")
        val plain = ReviewComment(id = "P4", filePath = "src/Frete.kt", startLine = 1, endLine = 1, text = "só texto\ncom duas linhas")

        assertEquals("use o peso" to code, ReviewPlan.parseBody(ReviewPlan.bodyFor(inline, Placement.INLINE)))
        assertEquals("fora" to code, ReviewPlan.parseBody(ReviewPlan.bodyFor(outside, Placement.FILE)))
        assertEquals("arquivo" to "fun a() = 1", ReviewPlan.parseBody(ReviewPlan.bodyFor(wholeFile, Placement.FILE)))
        assertEquals("só texto\ncom duas linhas" to "", ReviewPlan.parseBody(ReviewPlan.bodyFor(plain, Placement.INLINE)))
        assertEquals("escrito à mão no GitHub" to "", ReviewPlan.parseBody("escrito à mão no GitHub"))
    }

    @Test
    fun syncFindsCommentsEditedOrDeletedOnGitHub() {
        val sent = request("P1", commentId = 10).copy(suggestion = "val p = 1")
        val sameBody = ReviewPlan.bodyFor(sent.toComment(3), Placement.INLINE)
        fun remote(body: String) = mapOf(10L to GitHubClient.PostedComment(10, "u", "src/Frete.kt", 5, body, updatedAt = "2026-11-03T20:00:00Z"))

        assertEquals("o mesmo texto não muda nada", sent, RemoteSync.apply(sent, remote(sameBody)))
        val edited = RemoteSync.apply(sent, remote("Considere o peso e o volume\n\n```suggestion\nval p = 2\n```\n\n${ReviewPlan.marker("P1")}"))
        assertEquals(RemoteStatus.EDITED, edited.remote)
        assertEquals("Considere o peso e o volume", edited.text)
        assertEquals("val p = 2", edited.suggestion)
        assertEquals("2026-11-03T20:00:00Z", edited.remoteUpdatedAt)
        val deleted = RemoteSync.apply(sent, emptyMap())
        assertTrue(deleted.deleted)
        assertFalse("apagado não conta como atendido", deleted.copy(code = CodeStatus.CHANGED).likelyDone)
        assertEquals("no texto geral da revisão não há o que sincronizar", request("P2", commentId = null), RemoteSync.apply(request("P2", commentId = null), emptyMap()))
    }

    @Test
    fun remoteStatusSurvivesTheMarkdownAndDeletedRequestsLeaveTheCounts() {
        val record = PullRequestRecord(
            3, "t", "u", "h", "b",
            listOf(
                request("P1").copy(remote = RemoteStatus.EDITED, remoteUpdatedAt = "2026-11-03T20:00:00Z"),
                request("P2").copy(remote = RemoteStatus.DELETED),
            ),
        )
        val markdown = ChangeRequestMarkdown.render(record)

        val parsed = ChangeRequestMarkdown.parse(markdown)!!

        assertEquals(listOf(RemoteStatus.EDITED, RemoteStatus.DELETED), parsed.requests.map { it.remote })
        assertEquals("2026-11-03T20:00:00Z", parsed.requests[0].remoteUpdatedAt)
        assertEquals(listOf("P1"), parsed.active.map { it.id })
        assertTrue(markdown.contains("| ~~P2~~ (Apagado no GitHub) |"))
        assertTrue(markdown.contains("- **No GitHub:** Editado no GitHub em "))
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
