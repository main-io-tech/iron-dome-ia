package com.mio.irondome.interceptor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import com.mio.irondome.caixapreta.BugAnalysis
import com.mio.irondome.escudo.FailureExplanation
import com.mio.irondome.escudo.TestKind
import com.mio.irondome.escudo.TestNode
import com.mio.irondome.escudo.TestOutcome
import com.mio.irondome.patrulha.AiReview
import com.mio.irondome.patrulha.ChangedFile
import com.mio.irondome.patrulha.PullRequest
import com.mio.irondome.patrulha.PullRequestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDateTime

class InterceptorTest {

    private val readOnly = AgentTask("Revisão", "Revise o PR\nseguindo \"as\" instruções & responda", write = false)

    @Test
    fun cursorRunsHeadlessInTheWorkspaceAndOnlyWritesWhenAllowed() {
        val read = AgentCommand.build(AgentProvider.CURSOR, "agent", readOnly, "C:/proj", "gpt-5", 20)
        val write = AgentCommand.build(AgentProvider.CURSOR, "agent", readOnly.copy(write = true), "C:/proj", null, 20)

        assertEquals(listOf("agent", "-p", "--output-format", "stream-json", "--trust", "--workspace", "C:/proj"), read.take(7))
        assertTrue("só leitura usa o modo ask", read.containsAll(listOf("--mode", "ask")))
        assertFalse("--force" in read)
        assertEquals(listOf("--model", "gpt-5"), read.subList(read.indexOf("--model"), read.indexOf("--model") + 2))
        assertTrue("--force" in write)
        assertFalse("--model" in write)
        assertEquals("o prompt vai em uma linha, sem acentos nem os caracteres do cmd.exe", "Revise o PR seguindo as instrucoes responda", read.last())
    }

    @Test
    fun whoChangesCodeIgnoresPullRequestCommentsAndReviewsOnlyRead() {
        val write = AgentCommand.guarded(readOnly.copy(prompt = "Corrija", write = true))
        val read = AgentCommand.guarded(readOnly.copy(prompt = "Revise"))

        assertEquals("Corrija ${AgentCommand.PULL_REQUEST_RULE}", write)
        assertTrue("iron-dome/pull-requests/" in write)
        assertEquals("Revise ${AgentCommand.READ_ONLY_RULE}", read)
    }

    @Test
    fun theTerminalCommandIsQuotedForEachShell() {
        val prompt = "Revise o PR de O'Brien"
        val exe = "C:/Program Files/Cursor/agent.cmd"

        assertEquals(
            "& 'C:/Program Files/Cursor/agent.cmd' '--model' 'gpt-5' 'Revise o PR de O''Brien'",
            AgentCommand.terminal(AgentProvider.CURSOR, exe, prompt, "gpt-5", 20, ShellKind.POWERSHELL),
        )
        assertEquals(
            "\"C:/Program Files/Cursor/agent.cmd\" \"Revise o PR de O'Brien\"",
            AgentCommand.terminal(AgentProvider.CURSOR, exe, prompt, null, 20, ShellKind.CMD),
        )
        assertEquals(
            "'agy' '-p' 'Revise o PR de O'\\''Brien' '--print-timeout' '15m'",
            AgentCommand.terminal(AgentProvider.ANTIGRAVITY, "agy", prompt, null, 15, ShellKind.POSIX),
        )
    }

    @Test
    fun inWslTheTerminalRunsTheCommandInTheLoginShellOfTheDistribution() {
        val inner = AgentCommand.terminal(AgentProvider.CURSOR, "agent", "Revise o PR", null, 20, ShellKind.POSIX)

        assertEquals("'agent' 'Revise o PR'", inner)
        assertEquals(
            "& 'wsl.exe' '-d' 'Ubuntu' '--cd' '/mnt/c/proj' '--' 'bash' '-lic' '''agent'' ''Revise o PR'''",
            AgentCommand.wrapWsl("Ubuntu", "/mnt/c/proj", inner, ShellKind.POWERSHELL),
        )
        assertEquals(
            "\"wsl.exe\" \"-d\" \"Ubuntu\" \"--cd\" \"/mnt/c/proj\" \"--\" \"bash\" \"-lic\" \"'agent' 'Revise o PR'\"",
            AgentCommand.wrapWsl("Ubuntu", "/mnt/c/proj", inner, ShellKind.CMD),
        )
    }

    @Test
    fun theShellComesFromTheTerminalSettings() {
        assertEquals(ShellKind.POWERSHELL, ShellKind.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", windows = true))
        assertEquals(ShellKind.POWERSHELL, ShellKind.of("pwsh", windows = false))
        assertEquals(ShellKind.CMD, ShellKind.of("cmd.exe", windows = true))
        assertEquals(ShellKind.POWERSHELL, ShellKind.of(null, windows = true))
        assertEquals(ShellKind.POSIX, ShellKind.of("/bin/zsh", windows = false))
        assertEquals(ShellKind.POSIX, ShellKind.of("C:\\Program Files\\Git\\bin\\bash.exe", windows = true))
    }

    @Test
    fun antigravityUsesItsOwnFlagsAndATimeout() {
        val command = AgentCommand.build(AgentProvider.ANTIGRAVITY, "agy", readOnly, "C:/proj", "gemini-3-pro", 15)

        assertEquals(listOf("agy", "-p", "Revise o PR seguindo as instrucoes responda", "--output-format", "stream-json", "--print-timeout", "15m"), command.take(7))
        assertTrue(command.containsAll(listOf("--model", "gemini-3-pro")))
    }

    @Test
    fun streamEventsBecomeProgressFilesAndTheFinalAnswer() {
        assertEquals(AgentEvent.Init("Claude 4 Sonnet"), AgentEvents.parse("""{"type":"system","subtype":"init","model":"Claude 4 Sonnet"}"""))
        assertEquals(
            AgentEvent.Text("Vou ler o arquivo"),
            AgentEvents.parse("""{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Vou ler o arquivo"}]}}"""),
        )
        assertEquals(
            AgentEvent.Tool(true, "write", "src/Frete.kt"),
            AgentEvents.parse("""{"type":"tool_call","subtype":"started","tool_call":{"writeToolCall":{"args":{"path":"src/Frete.kt"}}}}"""),
        )
        assertEquals(AgentEvent.Result("Pronto", false), AgentEvents.parse("""{"type":"result","subtype":"success","is_error":false,"result":"Pronto"}"""))
        assertEquals(AgentEvent.Result("falhou", true), AgentEvents.parse("""{"type":"result","is_error":true,"result":"falhou"}"""))
        assertEquals(AgentEvent.Other("texto solto"), AgentEvents.parse("texto solto"))
        assertNull(AgentEvents.parse("   "))
        assertNull("o eco do pedido não interessa", AgentEvents.parse("""{"type":"user","message":{}}"""))
    }

    /** Linhas copiadas de uma execução real do Cursor CLI 2026.10.22 no Windows. */
    @Test
    fun realCursorOutputWithThinkingAndExtraToolFields() {
        val read = """{"type":"tool_call","subtype":"started","call_id":"toolu_01","tool_call":{"readToolCall":{"args":{"path":"C:\\Temp\\Frete.kt"}},""" +
            """"hookAdditionalContexts":[],"toolCallId":"toolu_01","startedAtMs":"1793749753735"},"session_id":"s","timestamp_ms":1793749780602}"""
        val glob = """{"type":"tool_call","subtype":"started","tool_call":{"hookAdditionalContexts":[],"globToolCall":{"args":{"globPattern":"**/Frete.kt"}}}}"""

        assertEquals(AgentEvent.Tool(true, "read", "C:\\Temp\\Frete.kt"), AgentEvents.parse(read))
        assertEquals(AgentEvent.Tool(true, "glob", null), AgentEvents.parse(glob))
        assertNull("o raciocínio do modelo não entra no registro", AgentEvents.parse("""{"type":"thinking","subtype":"delta","text":"I should"}"""))
    }

    /** No Windows, o CLI do Cursor é um `agent.cmd`: o prompt precisa chegar inteiro passando pelo cmd.exe. */
    @Test
    fun aCmdShimReceivesThePromptIntactOnWindows() {
        assumeTrue(SystemInfo.isWindows)
        val dir = Files.createTempDirectory("irondome agente")
        val shim = dir.resolve("agent.cmd")
        Files.writeString(shim, "@echo off\r\n>\"%~dp0args.txt\" echo %*\r\necho {\"type\":\"result\",\"is_error\":false,\"result\":\"ok\"}\r\n")
        val task = AgentTask("Teste", "Execute a ordem em iron-dome/ordem-interceptacao-3.md: corrija a função de frete (peso)", write = true)

        val command = AgentCommand.build(AgentProvider.CURSOR, shim.toString(), task, dir.toString(), null, 5)
        val output = ExecUtil.execAndGetOutput(GeneralCommandLine(command).withWorkDirectory(dir.toFile()).withCharset(StandardCharsets.UTF_8), 30_000)

        assertEquals(0, output.exitCode)
        assertEquals(AgentEvent.Result("ok", false), output.stdoutLines.mapNotNull(AgentEvents::parse).single())
        val received = Files.readString(dir.resolve("args.txt"), StandardCharsets.ISO_8859_1)
        assertTrue(received, received.contains("--force"))
        assertTrue("sem acento, o prompt chega igual", received.contains("iron-dome/ordem-interceptacao-3.md: corrija a funcao de frete (peso)"))
        assertTrue("o diretório com espaço chega como um argumento só", received.contains("\"$dir\""))
    }

    @Test
    fun theJsonIsFoundInsideTheAnswer() {
        assertEquals(2, AgentEvents.extractJson("Achei isto:\n```json\n[{\"a\":1},{\"a\":2}]\n```\nFim.")!!.asJsonArray.size())
        assertEquals(1, AgentEvents.extractJson("Resposta: [{\"a\":1}] e pronto")!!.asJsonArray.size())
        assertNull(AgentEvents.extractJson("nada de JSON aqui"))
    }

    @Test
    fun aiReviewCommentsAreReadAndInvalidOnesSkipped() {
        val answer = """
            ```json
            [
              {"path": "./src/Frete.kt", "startLine": 10, "endLine": 12, "comment": "Divisão por zero quando o peso é 0.", "suggestion": "if (peso == 0) return 0\n"},
              {"path": "src/Pedido.kt", "line": 4, "comment": "Nome confuso."},
              {"path": "src/Sem.kt", "startLine": 3, "comment": ""},
              {"startLine": 3, "comment": "sem caminho"},
              {"path": "src/Zero.kt", "startLine": 0, "comment": "linha inválida"}
            ]
            ```
        """.trimIndent()

        val comments = AiReview.parse(answer)

        assertEquals(listOf("src/Frete.kt", "src/Pedido.kt"), comments.map { it.path })
        assertEquals(10..12, comments[0].startLine..comments[0].endLine)
        assertEquals("if (peso == 0) return 0", comments[0].suggestion)
        assertEquals("só line vira uma linha", 4..4, comments[1].startLine..comments[1].endLine)
        assertTrue(AiReview.parse("[]").isEmpty())
    }

    @Test
    fun aiReviewInstructionsCarryTheRulesAndTheDiffs() {
        val pr = PullRequest(12, "Frete por peso", "ana", "frete", "abc1234567", "main", "u", "", false)
        val files = PullRequestFiles("abc", "base", listOf(ChangedFile("src/Frete.kt", "modified", "@@ -1 +1 @@\n-a\n+b", 1, 1)))

        val text = AiReview.instructions(pr, files)

        assertTrue(text.contains("**Não altere nenhum arquivo.**"))
        assertTrue(text.contains("No máximo ${AiReview.MAX_COMMENTS} comentários"))
        assertTrue(text.contains("### `src/Frete.kt` · Alterado · +1 −1"))
        assertTrue(text.contains("```diff\n@@ -1 +1 @@\n-a\n+b\n```"))
    }

    @Test
    fun failureInstructionsListTheFailuresWithMessageAndStack() {
        val failure = TestNode(
            TestKind.TEST, "frete", "calcula o frete por peso", "calculaPorPeso", "java:test://loja.FreteTest/calculaPorPeso", 12,
            "expected: <10> but was: <0>", "org.opentest4j.AssertionFailedError: expected\n\tat loja.FreteTest.calculaPorPeso(FreteTest.kt:20)",
            emptyList(), TestOutcome.FAILED,
        )

        val text = FailureExplanation.instructions(listOf(failure))

        assertTrue(text.contains("## calcula o frete por peso"))
        assertTrue(text.contains("- **Teste:** `loja.FreteTest#calculaPorPeso`"))
        assertTrue(text.contains("expected: <10> but was: <0>"))
        assertTrue(text.contains("at loja.FreteTest.calculaPorPeso(FreteTest.kt:20)"))
        assertTrue(text.contains("**Não altere nenhum arquivo.**"))
    }

    @Test
    fun bugAnalysisGoesToTheEndOfTheReportAndReplacesTheOldOne() {
        val now = LocalDateTime.of(2026, 11, 3, 21, 0)
        val first = BugAnalysis.append("# Bug: algo\n\ntexto\n", "Cursor", "Causa: X", now)
        val second = BugAnalysis.append(first, "Cursor", "Causa: Y", now)

        assertTrue(first.endsWith("${BugAnalysis.SECTION}\n\n_Feita pelo Cursor em 03/11/2026 21:00; confira antes de seguir._\n\nCausa: X\n"))
        assertTrue(second.contains("Causa: Y"))
        assertFalse("a análise anterior sai", second.contains("Causa: X"))
        assertTrue(second.startsWith("# Bug: algo\n\ntexto\n\n${BugAnalysis.SECTION}"))
    }

    @Test
    fun transcriptRecordsWhatTheAiDid() {
        val result = AgentResult(
            AgentTask("Disparar", "Execute a ordem", write = true), AgentProvider.CURSOR, "Corrigi 2 alvos.", null, false,
            setOf("src/Frete.kt"), listOf("Lendo src/Frete.kt", "Editando src/Frete.kt"), "Claude", Duration.ofSeconds(42), null,
        )

        val text = AgentTranscript.render(result, listOf("agent", "-p", "--force", "Execute a ordem"), "", LocalDateTime.of(2026, 11, 3, 21, 0))

        assertTrue(text.startsWith("# IA: Disparar\n"))
        assertTrue(text.contains("- **CLI:** Cursor · modelo Claude"))
        assertTrue(text.contains("- **Situação:** concluída"))
        assertTrue(text.contains("## Arquivos alterados\n\n- `src/Frete.kt`"))
        assertTrue(text.contains("## Resposta\n\nCorrigi 2 alvos."))
    }
}
