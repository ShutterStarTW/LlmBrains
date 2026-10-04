package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyAgentMigrationTest {
    private val kimi = LegacyAgentMigration.forAgent("kimi")!!

    @Test
    fun `python kimi cli 1_52 output with the maintenance notice is legacy`() {
        val out = "kimi, version 1.52.0\nkimi-cli is no longer maintained. Please use the new Kimi Code CLI."
        assertTrue(LegacyAgentMigration.isLegacyKimiOutput(out))
    }

    @Test
    fun `python kimi cli 1_49 output without the notice is legacy`() {
        assertTrue(LegacyAgentMigration.isLegacyKimiOutput("kimi, version 1.49.0"))
    }

    @Test
    fun `new kimi code 2_x output is not legacy`() {
        assertFalse(LegacyAgentMigration.isLegacyKimiOutput("2.1.1"))
        assertFalse(LegacyAgentMigration.isLegacyKimiOutput("kimi, version 2.1.1"))
        assertFalse(LegacyAgentMigration.isLegacyKimiOutput(""))
    }

    @Test
    fun `migration targets an existing agent whose npm package matches its update hint`() {
        val agent = CodingAgents.all.first { it.id == kimi.agentId }
        assertTrue(agent.updateHint.contains(kimi.newPackage))
        assertNotNull(LegacyAgentMigration.forAgent("kimi"))
    }

    @Test
    fun `commands install first and only then remove the legacy installs`() {
        for (powerShell in listOf(true, false)) {
            val cmd = LegacyAgentMigration.command(kimi, powerShell)
            val install = cmd.indexOf("npm install -g @moonshot-ai/kimi-code")
            assertTrue(install >= 0)
            assertTrue(cmd.indexOf("uninstall kimi-cli") > install)
            assertTrue(cmd.contains("pip uninstall -y kimi-cli"))
            assertTrue(cmd.contains("uv tool uninstall kimi-cli"))
            assertTrue(cmd.contains("pipx uninstall kimi-cli"))
        }
    }

    @Test
    fun `commands contain no double quotes so they survive the WSL wrapper`() {
        for (powerShell in listOf(true, false)) {
            assertFalse(LegacyAgentMigration.command(kimi, powerShell).contains('"'))
        }
    }

    @Test
    fun `powershell variant checks the install exit code and bash variant branches on it`() {
        assertTrue(LegacyAgentMigration.command(kimi, true).contains("\$LASTEXITCODE -eq 0"))
        val bash = LegacyAgentMigration.command(kimi, false)
        assertTrue(bash.contains("if npm install -g @moonshot-ai/kimi-code; then"))
        assertEquals(1, Regex("command -v npm").findAll(bash).count())
    }
}
