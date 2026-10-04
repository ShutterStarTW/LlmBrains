package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.writeSkill
import com.shutterstar.agenthub.environment.mcp.discovery.CopilotMcpProvider
import com.shutterstar.agenthub.environment.skills.discovery.CopilotSkillProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CopilotPluginDiscoverySupportTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover portable and legacy plugin skills and sanitized MCP servers`() {
        val copilotHome = Files.createDirectories(temporaryDirectory.resolve(".copilot"))
        val portable = Files.createDirectories(copilotHome.resolve("installed-plugins/marketplace/portable"))
        Files.writeString(
            portable.resolve("plugin.json"),
            """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"portable"}""",
        )
        writeSkill(portable.resolve("skills/portable-skill"), "portable-skill")
        Files.writeString(
            portable.resolve("mcp.json"),
            """{"mcpServers":{"portable-server":{"command":"npx","args":["--token=secret-value"]}}}""",
        )

        val legacy = Files.createDirectories(copilotHome.resolve("installed-plugins/_direct/legacy"))
        Files.writeString(
            legacy.resolve("plugin.json"),
            """{"name":"legacy","skills":"custom-skills","mcpServers":"config/servers.json"}""",
        )
        writeSkill(legacy.resolve("custom-skills/legacy-skill"), "legacy-skill")
        val legacyMcp = legacy.resolve("config/servers.json")
        Files.createDirectories(legacyMcp.parent)
        Files.writeString(legacyMcp, """{"mcpServers":{"legacy-server":{"command":"node"}}}""")

        assertEquals(
            setOf("portable-skill", "legacy-skill"),
            CopilotSkillProvider(copilotHome).discoverGlobal().mapTo(mutableSetOf()) { it.name },
        )
        val servers = CopilotMcpProvider(copilotHome).discoverGlobal().associateBy { it.name }
        assertEquals(setOf("portable-server", "legacy-server"), servers.keys)
        assertTrue(servers.values.all { it.agentId == "copilot" })
        assertFalse(servers.toString().contains("secret-value"))
    }

    @Test
    fun `should not traverse plugin manifest paths outside the plugin`() {
        val copilotHome = Files.createDirectories(temporaryDirectory.resolve(".copilot"))
        val plugin = Files.createDirectories(copilotHome.resolve("installed-plugins/_direct/unsafe"))
        Files.writeString(plugin.resolve("plugin.json"), """{"name":"unsafe","skills":"../../../../outside"}""")

        val discovered = CopilotPluginDiscoverySupport.discover(copilotHome).single()

        assertTrue(discovered.skillDirectories.isEmpty())
    }

}