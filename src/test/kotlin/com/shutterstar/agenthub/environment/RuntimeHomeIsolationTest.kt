package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.instructions.discovery.ClaudeInstructionProvider
import com.shutterstar.agenthub.environment.mcp.discovery.ClaudeMcpProvider
import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every environment provider is constructed with an explicit home directory rather than
 * hardcoding `System.getProperty("user.home")` internally. This is what lets a future host/WSL
 * runtime mode (see WslSupport) point discovery at the correct home for whichever identity is
 * active, without the provider itself knowing about WSL. These tests pin that contract down for
 * one representative provider per capability, using two distinct home directories to stand in
 * for "host" and "WSL" runtime identities.
 */
class RuntimeHomeIsolationTest {
    @TempDir
    lateinit var hostHome: Path

    @TempDir
    lateinit var wslHome: Path

    @Test
    fun `skill discovery only sees the injected home directory`() {
        writeSkill(hostHome.resolve(".claude/skills/host-only"), "host-only")
        writeSkill(wslHome.resolve(".claude/skills/wsl-only"), "wsl-only")

        val hostSkills = ClaudeSkillProvider(hostHome).discoverGlobal().map { it.name }
        val wslSkills = ClaudeSkillProvider(wslHome).discoverGlobal().map { it.name }

        assertEquals(listOf("host-only"), hostSkills)
        assertEquals(listOf("wsl-only"), wslSkills)
    }

    @Test
    fun `MCP discovery only sees the injected home directory`() {
        Files.writeString(
            hostHome.resolve(".claude.json"),
            """{"mcpServers":{"host-server":{"command":"npx","args":["host"]}}}""",
        )
        Files.writeString(
            wslHome.resolve(".claude.json"),
            """{"mcpServers":{"wsl-server":{"command":"npx","args":["wsl"]}}}""",
        )

        val hostServers = ClaudeMcpProvider(hostHome).discoverGlobal().map { it.name }
        val wslServers = ClaudeMcpProvider(wslHome).discoverGlobal().map { it.name }

        assertEquals(listOf("host-server"), hostServers)
        assertEquals(listOf("wsl-server"), wslServers)
    }

    @Test
    fun `instruction discovery only sees the injected home directory`() {
        Files.createDirectories(hostHome.resolve(".claude"))
        Files.createDirectories(wslHome.resolve(".claude"))
        Files.writeString(hostHome.resolve(".claude/CLAUDE.md"), "Host instructions")
        Files.writeString(wslHome.resolve(".claude/CLAUDE.md"), "WSL instructions")

        val hostInstructions = ClaudeInstructionProvider(hostHome).discoverGlobal()
        val wslInstructions = ClaudeInstructionProvider(wslHome).discoverGlobal()

        assertTrue(hostInstructions.single().path.startsWith(hostHome.toAbsolutePath().toString()))
        assertTrue(wslInstructions.single().path.startsWith(wslHome.toAbsolutePath().toString()))
    }

    private fun writeSkill(directory: Path, name: String) {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("SKILL.md"),
            "---\nname: $name\ndescription: Test skill\n---\nInstructions",
        )
    }
}
