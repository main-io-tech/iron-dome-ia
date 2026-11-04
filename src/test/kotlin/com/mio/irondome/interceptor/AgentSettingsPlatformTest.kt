package com.mio.irondome.interceptor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class AgentSettingsPlatformTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgentSettings.provider = AgentProvider.CURSOR
            AgentSettings.executable = null
            AgentSettings.model = null
            AgentSettings.clearVerified()
        } finally {
            super.tearDown()
        }
    }

    fun testTheAiIsReadyOnlyAfterATestWithTheCurrentConfiguration() {
        val cli = Files.createTempFile("agent", ".cmd")
        AgentSettings.provider = AgentProvider.CURSOR
        AgentSettings.executable = cli.toString()
        AgentSettings.model = "gpt-5"
        assertFalse("sem teste, os botões ficam desligados", AgentSettings.ready)

        AgentSettings.markVerified("03/11/2026 21:00")
        assertTrue(AgentSettings.ready)
        assertEquals("03/11/2026 21:00", AgentSettings.verifiedAt)

        AgentSettings.model = "claude"
        assertFalse("outro modelo invalida o teste", AgentSettings.ready)
        assertNull(AgentSettings.verifiedAt)

        AgentSettings.markVerified("03/11/2026 21:05")
        AgentSettings.provider = AgentProvider.ANTIGRAVITY
        assertFalse("outro CLI também", AgentSettings.ready)

        AgentSettings.provider = AgentProvider.CURSOR
        assertTrue("voltar à configuração testada volta a valer", AgentSettings.ready)
        Files.delete(cli)
        AgentSettings.model = "claude"
        assertFalse("sem o executável, não está pronta", AgentSettings.ready)
    }

    fun testTranscriptSummaryFeedsTheRunsList() {
        val (title, status) = AgentTranscript.summary(listOf("# IA: Revisão do PR #12", "", "- **Quando:** agora", "- **Situação:** concluída"))

        assertEquals("Revisão do PR #12", title)
        assertEquals("concluída", status)
        assertEquals(null to null, AgentTranscript.summary(listOf("outro arquivo")))
    }
}
