package com.mio.irondome.patrulha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ReviewRulesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pr = PullRequest(12, "Frete por peso", "dev", "frete", "abc1234def", "main", "https://github.com/o/r/pull/12", "", false)

    @Test
    fun foldersOpenIntoTheirMarkdownFilesAndMissingOnesComeWithoutText() {
        val root = temp.root.toPath()
        write(root, "AGENTS.md", "Use `Result` em vez de exceções.")
        write(root, ".cursor/rules/kotlin.mdc", "---\nglobs: *.kt\n---\nSem `!!`.")
        write(root, ".cursor/rules/testes.md", "Todo serviço tem teste.")
        write(root, ".cursor/rules/nota.txt", "fora")

        val rules = ReviewRules.read(root, listOf("AGENTS.md", ".cursor/rules", "AGENTS.md", "SUMIU.md"))

        assertEquals(listOf("AGENTS.md", ".cursor/rules/kotlin.mdc", ".cursor/rules/testes.md", "SUMIU.md"), rules.map { it.path })
        assertEquals("Use `Result` em vez de exceções.", rules[0].text)
        assertNull("o que não existe fica sem texto", rules[3].text)
    }

    @Test
    fun eachFileAndAllTogetherAreCapped() {
        val root = temp.root.toPath()
        val big = "a".repeat(ReviewRules.FILE_MAX + 10)
        val names = (1..5).map { "r$it.md" }
        names.forEach { write(root, it, big) }

        val rules = ReviewRules.read(root, names)

        assertEquals(ReviewRules.FILE_MAX, rules[0].text!!.length)
        assertTrue(rules[0].truncated)
        assertEquals("todos juntos até o total", ReviewRules.TOTAL_MAX, rules.sumOf { it.text!!.length })
        assertEquals("", rules.last().text)
        assertTrue("o que não coube é avisado", "Não couberam aqui" in ReviewRules.section(rules) && "`r5.md`" in ReviewRules.section(rules))
    }

    @Test
    fun theRulesGoToTheAiReviewAndToTheAutomaticRepliesInAFenceLongerThanTheirCode() {
        val rules = listOf(RuleFile("AGENTS.md", "Exemplo:\n```kotlin\nval x = 1\n```"), RuleFile("SUMIU.md", null))
        val files = PullRequestFiles("abc1234def", "base", listOf(ChangedFile("src/Frete.kt", "modified", "@@ -1 +1 @@\n-a\n+b")))
        val discussion = Discussion(DiscussionKind.THREAD, listOf(DiscussionComment(10, "eu", "texto", "2026-11-03T13:00:00Z")), "T1", "src/Frete.kt", 5)

        val review = AiReview.instructions(pr, files, rules = rules)
        val reply = AutoReply.instructions(pr, discussion, "eu", "código", null, 1, rules = rules)

        listOf(review, reply).forEach { text ->
            assertTrue("## Regras do projeto" in text)
            assertTrue("### `AGENTS.md`" in text && "````markdown\nExemplo:\n```kotlin" in text)
            assertFalse("o que não existe não entra", "SUMIU.md" in text)
        }
        assertFalse("sem regras, sem a seção", "Regras do projeto" in AiReview.instructions(pr, files))
        assertEquals("", ReviewRules.section(listOf(RuleFile("SUMIU.md", null))))
    }

    @Test
    fun linkedPathsAreRelativeToTheProjectAndTheCommonOnesAreDetected() {
        val root = temp.root.toPath()
        write(root, "CLAUDE.md", "x")
        write(root, ".cursor/rules/a.mdc", "x")
        val outside = temp.newFolder("fora").toPath().resolve("regras.md")

        assertEquals(".cursor/rules/a.mdc", ReviewRules.relativize(root, root.resolve(".cursor/rules/a.mdc")))
        assertEquals(outside.toString().replace('\\', '/'), ReviewRules.relativize(root.resolve("projeto"), outside))
        assertEquals(listOf("CLAUDE.md", ".cursor/rules"), ReviewRules.detect(root))
    }

    private fun write(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
