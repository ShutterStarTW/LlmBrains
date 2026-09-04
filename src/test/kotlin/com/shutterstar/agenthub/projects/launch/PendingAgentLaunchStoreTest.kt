package com.shutterstar.agenthub.projects.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PendingAgentLaunchStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns the requested agent for a matching, fresh request`() {
        val projectDir = tempDir.resolve("project").also(Files::createDirectories)
        val store = store()

        store.request(projectDir, "claude")

        assertEquals("claude", store.consumeIfMatching(projectDir.toString()))
    }

    @Test
    fun `is one-shot - a second consume call returns null`() {
        val projectDir = tempDir.resolve("project").also(Files::createDirectories)
        val store = store()
        store.request(projectDir, "claude")

        store.consumeIfMatching(projectDir.toString())

        assertNull(store.consumeIfMatching(projectDir.toString()))
        assertTrue(Files.notExists(storeFile()))
    }

    @Test
    fun `returns null when the opened project path does not match`() {
        val projectDir = tempDir.resolve("project").also(Files::createDirectories)
        val otherDir = tempDir.resolve("other-project").also(Files::createDirectories)
        val store = store()
        store.request(projectDir, "claude")

        assertNull(store.consumeIfMatching(otherDir.toString()))
        assertTrue(Files.isRegularFile(storeFile()))
        assertEquals("claude", store.consumeIfMatching(projectDir.toString()))
    }

    @Test
    fun `matches equivalent path representations after normalization`() {
        val projectDir = tempDir.resolve("project").also(Files::createDirectories)
        val store = store()
        store.request(projectDir, "codex")

        val withTrailingSeparator = projectDir.toString() + java.io.File.separator
        assertEquals("codex", store.consumeIfMatching(withTrailingSeparator))
    }

    @Test
    fun `returns null when no request is pending`() {
        assertNull(store().consumeIfMatching(tempDir.toString()))
    }

    @Test
    fun `returns null and clears an expired request`() {
        val projectDir = tempDir.resolve("project").also(Files::createDirectories)
        var clock = 0L
        val store = PendingAgentLaunchStore(storeFile(), now = { clock })
        store.request(projectDir, "claude")
        clock += 6 * 60 * 1000L // past the 5-minute freshness window

        assertNull(store.consumeIfMatching(projectDir.toString()))
        assertTrue(Files.notExists(storeFile()))
    }

    @Test
    fun `returns null for a malformed request file without crashing`() {
        Files.createDirectories(storeFile().parent)
        Files.writeString(storeFile(), "not valid json")

        assertNull(store().consumeIfMatching(tempDir.toString()))
        assertTrue(Files.notExists(storeFile()))
    }

    private fun storeFile(): Path = tempDir.resolve("agenthub-home/pending-launch.json")

    private fun store(): PendingAgentLaunchStore = PendingAgentLaunchStore(storeFile())
}
