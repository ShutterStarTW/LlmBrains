package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectDiscoverySmokeCommandTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should find discovery script below project root`() {
        val script = tempDir.resolve(ProjectDiscoverySmokeCommand.SCRIPT_RELATIVE_PATH)
        Files.createDirectories(script.parent)
        Files.createFile(script)

        assertEquals(script, ProjectDiscoverySmokeCommand.findScript(tempDir.toString()))
    }

    @Test
    fun `should return null when project or script is missing`() {
        assertNull(ProjectDiscoverySmokeCommand.findScript(null))
        assertNull(ProjectDiscoverySmokeCommand.findScript(" "))
        assertNull(ProjectDiscoverySmokeCommand.findScript(tempDir.toString()))
    }

    @Test
    fun `should build native PowerShell command with escaped path`() {
        val script = Path.of("C:/Agent's Hub/run-project-discovery.ps1")

        val command = ProjectDiscoverySmokeCommand.build(script)

        assertTrue(command.startsWith("pwsh -NoProfile -File '"))
        assertTrue(command.contains("Agent''s Hub"))
        assertTrue(command.endsWith("run-project-discovery.ps1'"))
    }
}
