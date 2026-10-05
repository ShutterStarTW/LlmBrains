package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KiloInstructionProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should use the Kilo global AGENTS file and fall back to the global Claude file`() {
        Files.writeString(Files.createDirectories(temporaryDirectory.resolve(".claude")).resolve("CLAUDE.md"), "Claude")
        val fallback = KiloInstructionProvider(temporaryDirectory).discoverGlobal().single()
        assertEquals(InstructionType.CLAUDE_MD, fallback.type)

        Files.writeString(Files.createDirectories(temporaryDirectory.resolve(".config/kilo")).resolve("AGENTS.md"), "Kilo")
        val global = KiloInstructionProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals(InstructionType.AGENTS_MD, global.type)
        assertEquals(InstructionScope.GLOBAL, global.scope)
        assertEquals(setOf("kilo"), global.agentIds)
        assertTrue(global.path.replace('\\', '/').endsWith(".config/kilo/AGENTS.md"))
    }

    @Test
    fun `should list CLAUDE md with a note only when an AGENTS md shadows it`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(root.resolve("AGENTS.md"), "Agents")
        Files.writeString(root.resolve("CLAUDE.md"), "Claude")
        val lonely = Files.createDirectories(root.resolve("pkg"))
        Files.writeString(lonely.resolve("CLAUDE.md"), "Only Claude")

        val sources = KiloInstructionProvider(temporaryDirectory).discoverProject(project(root))
        val byPath = sources.associateBy { root.relativize(Path.of(it.path)).toString().replace('\\', '/') }

        assertEquals(setOf("AGENTS.md", "CLAUDE.md", "pkg/CLAUDE.md"), byPath.keys)
        assertTrue(byPath.getValue("AGENTS.md").agentNotes.isEmpty())
        assertTrue(byPath.getValue("CLAUDE.md").agentNotes.getValue("kilo").startsWith("Not used by Kilo"))
        assertTrue(byPath.getValue("pkg/CLAUDE.md").agentNotes.isEmpty())
        assertEquals("project", byPath.getValue("AGENTS.md").projectName)
    }
}
