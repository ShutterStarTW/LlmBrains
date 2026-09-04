package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectLaunchSupportTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `uses discovered project path as working directory`() {
        val projectPath = Files.createDirectories(tempDirectory.resolve("project"))
        val gitRoot = Files.createDirectories(tempDirectory.resolve("git-root"))

        assertEquals(projectPath, ProjectLaunchSupport.workingDirectory(project(projectPath, gitRoot)))
    }

    @Test
    fun `falls back to existing Git root`() {
        val missingPath = tempDirectory.resolve("missing")
        val gitRoot = Files.createDirectories(tempDirectory.resolve("git-root"))

        assertEquals(gitRoot, ProjectLaunchSupport.workingDirectory(project(missingPath, gitRoot)))
    }

    @Test
    fun `falls back to the canonical project path`() {
        val missingPath = tempDirectory.resolve("missing")
        val missingGitRoot = tempDirectory.resolve("missing-git")
        val canonicalPath = Files.createDirectories(tempDirectory.resolve("canonical"))

        assertEquals(
            canonicalPath,
            ProjectLaunchSupport.workingDirectory(project(missingPath, missingGitRoot, canonicalPath)),
        )
    }

    @Test
    fun `rejects missing project locations`() {
        val project = project(tempDirectory.resolve("missing"), tempDirectory.resolve("also-missing"))

        assertNull(ProjectLaunchSupport.workingDirectory(project))
    }

    private fun project(
        path: Path,
        gitRoot: Path,
        canonicalPath: Path = path,
    ) = DiscoveredProject(
        identity = ProjectIdentity("project", canonicalPath.toString(), gitRoot.toString(), null),
        name = "Project",
        path = path.toString(),
        gitRoot = gitRoot.toString(),
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
}
