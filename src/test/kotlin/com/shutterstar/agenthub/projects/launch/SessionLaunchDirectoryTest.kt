package com.shutterstar.agenthub.projects.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionLaunchDirectoryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `should retain the recorded directory when it exists`() {
        assertEquals(root.toString(), SessionLaunchDirectory.resolve(root.toString(), toHostPath = { it }))
    }

    @Test
    fun `should refuse missing unknown and invalid directories`() {
        assertNull(SessionLaunchDirectory.resolve(root.resolve("missing").toString(), toHostPath = { it }))
        assertNull(SessionLaunchDirectory.resolve(null))
        assertNull(SessionLaunchDirectory.resolve(" "))
        assertNull(SessionLaunchDirectory.resolve("invalid\u0000path", toHostPath = { it }))
        assertNull(SessionLaunchDirectory.resolve(Files.createFile(root.resolve("file")).toString(), toHostPath = { it }))
    }

    @Test
    fun `should validate a Linux directory through the host mapping and retain the Linux cwd`() {
        assertEquals("/home/me/project", SessionLaunchDirectory.resolve("/home/me/project", toHostPath = { root.toString() }))
        assertNull(SessionLaunchDirectory.resolve("/home/me/project", toHostPath = { null }))
        assertNull(SessionLaunchDirectory.resolve("/home/me/project", toHostPath = { root.resolve("missing").toString() }))
    }
}
