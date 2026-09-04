package com.shutterstar.agenthub.projects.discovery

import java.nio.file.Files
import java.nio.file.Path

object ProjectDiscoverySmokeCommand {
    const val SCRIPT_RELATIVE_PATH = "src/test/scripts/run-project-discovery.ps1"

    fun findScript(projectBasePath: String?): Path? {
        if (projectBasePath.isNullOrBlank()) return null

        val scriptPath = Path.of(projectBasePath).resolve(SCRIPT_RELATIVE_PATH).normalize()
        return scriptPath.takeIf(Files::isRegularFile)
    }

    fun build(scriptPath: Path): String {
        val escapedPath = scriptPath.toAbsolutePath().normalize().toString().replace("'", "''")
        return "pwsh -NoProfile -File '$escapedPath'"
    }
}
