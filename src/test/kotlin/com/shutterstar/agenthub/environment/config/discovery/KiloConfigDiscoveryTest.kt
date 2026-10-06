package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.config.model.ConfigFormat
import com.shutterstar.agenthub.environment.config.model.ConfigScope
import com.shutterstar.agenthub.project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KiloConfigDiscoveryTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `should inventory the global kilo settings files and nothing else`() {
        write(".config/kilo/kilo.jsonc", """{"model":"anthropic/claude-sonnet-4-5"}""")
        write(".config/kilo/tui.json", "{}")
        write(".config/kilo/config.json", "{}")
        write(".config/kilo/credentials.json", """{"token":"secret"}""")
        write(".config/opencode/opencode.json", "{}")

        val sources = KiloConfigProvider(home).discoverGlobal()

        assertEquals(setOf("kilo.jsonc", "tui.json", "config.json"), sources.map { Path.of(it.path).fileName.toString() }.toSet())
        assertTrue(sources.all { it.agentId == "kilo" && it.scope == ConfigScope.GLOBAL && it.format == ConfigFormat.JSON })
        assertEquals(
            "anthropic/claude-sonnet-4-5",
            sources.first { it.path.endsWith("kilo.jsonc") }.highlights.single { it.key == "model" }.value,
        )
    }

    @Test
    fun `should inventory opencode json next to the kilo settings in the global kilo directory`() {
        write(".config/kilo/opencode.jsonc", "{}")
        write(".config/kilo/opencode.json", "{}")
        write(".config/opencode/opencode.json", "{}")

        val names = KiloConfigProvider(home).discoverGlobal().map { Path.of(it.path).fileName.toString() }.toSet()

        // Only the opencode.json(c) inside ~/.config/kilo - the OpenCode config directory itself is not Kilo's.
        assertEquals(setOf("opencode.jsonc", "opencode.json"), names)
    }

    @Test
    fun `should inventory project kilo configs in nested directories only by exact name`() {
        val root = Files.createDirectories(home.resolve("project"))
        write("project/kilo.json", "{}")
        write("project/.kilo/kilo.jsonc", "{}")
        write("project/.kilocode/kilo.json", "{}")
        write("project/packages/app/.kilo/tui.json", "{}")
        write("project/opencode.json", "{}")
        write("project/node_modules/lib/kilo.json", "{}")

        val sources = KiloConfigProvider(home).discoverProject(project(root))

        // Kilo also loads opencode.json(c) next to kilo.json(c), so that file is inventoried too.
        assertEquals(5, sources.size)
        assertTrue(sources.any { it.path.endsWith("opencode.json") })
        assertTrue(sources.none { it.path.contains("node_modules") })
        assertTrue(sources.all { it.projectName == "project" && it.scope == ConfigScope.PROJECT })
    }

    private fun write(relative: String, content: String) {
        val path = home.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
