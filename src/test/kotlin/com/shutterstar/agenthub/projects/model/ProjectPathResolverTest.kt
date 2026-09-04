package com.shutterstar.agenthub.projects.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ProjectPathResolverTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `resolves an existing path directly`() {
        val existing = Files.createDirectories(tempDirectory.resolve("existing"))

        val resolved = ProjectPathResolver.resolveExistingRoot(project(path = existing.toString()))

        assertEquals(existing.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `resolves a path that is itself a symlink to an existing directory`() {
        val target = Files.createDirectories(tempDirectory.resolve("target"))
        val link = tempDirectory.resolve("link")
        val symlink = try {
            Files.createSymbolicLink(link, target)
        } catch (_: IOException) {
            // No permission to create symlinks (e.g. non-admin Windows without Developer Mode) - skip.
            return
        } catch (_: UnsupportedOperationException) {
            return
        }

        val resolved = ProjectPathResolver.resolveExistingRoot(project(path = symlink.toString()))

        assertEquals(symlink.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `falls back to gitRoot when path no longer exists`() {
        val missing = tempDirectory.resolve("missing").toString()
        val gitRoot = Files.createDirectories(tempDirectory.resolve("git-root"))

        val resolved = ProjectPathResolver.resolveExistingRoot(project(path = missing, gitRoot = gitRoot.toString()))

        assertEquals(gitRoot.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `falls back to canonicalPath when path and gitRoot no longer exist`() {
        val missingPath = tempDirectory.resolve("missing-path").toString()
        val missingGitRoot = tempDirectory.resolve("missing-git-root").toString()
        val canonicalPath = Files.createDirectories(tempDirectory.resolve("canonical"))

        val resolved = ProjectPathResolver.resolveExistingRoot(
            project(path = missingPath, gitRoot = missingGitRoot, canonicalPath = canonicalPath.toString()),
        )

        assertEquals(canonicalPath.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `returns null when every candidate is missing or invalid`() {
        val resolved = ProjectPathResolver.resolveExistingRoot(
            project(
                path = tempDirectory.resolve("nope-1").toString(),
                gitRoot = tempDirectory.resolve("nope-2").toString(),
                canonicalPath = tempDirectory.resolve("nope-3").toString(),
            ),
        )

        assertNull(resolved)
    }

    @Test
    fun `returns null when there are no candidates at all`() {
        assertNull(ProjectPathResolver.resolveExistingRoot(project(path = null)))
    }

    private fun project(
        path: String?,
        gitRoot: String? = null,
        canonicalPath: String? = null,
    ): DiscoveredProject = DiscoveredProject(
        identity = ProjectIdentity(id = "project", canonicalPath = canonicalPath, gitRoot = gitRoot, gitRemote = null),
        name = "project",
        path = path,
        gitRoot = gitRoot,
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
}
