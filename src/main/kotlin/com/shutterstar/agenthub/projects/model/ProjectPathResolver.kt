package com.shutterstar.agenthub.projects.model

import com.shutterstar.agenthub.AgentRuntime
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

object ProjectPathResolver {
    fun resolveExistingRoot(project: DiscoveredProject): Path? =
        listOfNotNull(project.path, project.gitRoot, project.identity.canonicalPath)
            .distinct()
            .firstNotNullOfOrNull(::toExistingDirectory)

    private fun toExistingDirectory(raw: String): Path? {
        // In WSL mode a project recorded as `/home/me/proj` is opened through the distro share.
        val hostPath = AgentRuntime.toHostPath(raw) ?: return null
        val path = try {
            Path.of(hostPath).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        return path.takeIf { Files.isDirectory(it) }
    }
}
