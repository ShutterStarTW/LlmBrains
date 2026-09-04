package com.shutterstar.agenthub.projects.model

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

object ProjectPathResolver {
    fun resolveExistingRoot(project: DiscoveredProject): Path? =
        listOfNotNull(project.path, project.gitRoot, project.identity.canonicalPath)
            .distinct()
            .firstNotNullOfOrNull(::toExistingDirectory)

    private fun toExistingDirectory(raw: String): Path? {
        val path = try {
            Path.of(raw).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        return path.takeIf { Files.isDirectory(it) }
    }
}
