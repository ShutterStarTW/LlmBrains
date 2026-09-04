package com.shutterstar.agenthub.projects.resolve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID

@EnabledOnOs(OS.WINDOWS)
class ProjectPathNormalizationTest {
    @Test
    fun `WSL mnt path translates to a Windows drive letter`() {
        val marker = "agenthub-test-${UUID.randomUUID()}"

        val resolved = ProjectResolver.normalizeFilesystemPath("/mnt/c/$marker/nested")

        assertEquals("C:/$marker/nested", resolved)
    }

    @Test
    fun `WSL mnt path without a suffix translates to a bare drive root`() {
        val resolved = ProjectResolver.normalizeFilesystemPath("/mnt/k")

        assertEquals("K:/", resolved)
    }

    @Test
    fun `tilde expands to the current user home directory`() {
        val resolved = ProjectResolver.normalizeFilesystemPath("~")

        assertEquals(ProjectResolver.normalizeFilesystemPath(System.getProperty("user.home")), resolved)
    }

    @Test
    fun `tilde slash path expands beneath the user home directory`() {
        val marker = "agenthub-test-${UUID.randomUUID()}"

        val resolved = ProjectResolver.normalizeFilesystemPath("~/$marker/nested")

        assertEquals(
            ProjectResolver.normalizeFilesystemPath(System.getProperty("user.home")) + "/$marker/nested",
            resolved,
        )
    }

    @Test
    fun `dot segments are collapsed on a path that does not exist on disk`() {
        val marker = "agenthub-test-${UUID.randomUUID()}"

        val resolved = ProjectResolver.normalizeFilesystemPath("K:/$marker/fake/../other/./thing")

        assertEquals("K:/$marker/other/thing", resolved)
    }

    @Test
    fun `parent segments above the root do not underflow`() {
        val marker = "agenthub-test-${UUID.randomUUID()}"

        val resolved = ProjectResolver.normalizeFilesystemPath("K:/$marker/../../../thing")

        assertEquals("K:/thing", resolved)
    }

    @Test
    fun `lowercase drive letters are normalized to uppercase`() {
        val marker = "agenthub-test-${UUID.randomUUID()}"

        val resolved = ProjectResolver.normalizeFilesystemPath("k:/$marker/../other")

        assertEquals("K:/other", resolved)
    }

    @Test
    fun `blank and null input resolve to null`() {
        assertNull(ProjectResolver.normalizeFilesystemPath(null))
        assertNull(ProjectResolver.normalizeFilesystemPath("   "))
    }
}
