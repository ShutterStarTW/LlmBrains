package com.shutterstar.agenthub.projects.launch

import com.shutterstar.agenthub.AgentRuntime
import java.nio.file.Files
import java.nio.file.Path

/** A resume must use its recorded project directory; an unavailable path never falls back to another project. */
internal object SessionLaunchDirectory {
    fun resolve(
        recordedPath: String?,
        toHostPath: (String) -> String? = AgentRuntime::toHostPath,
        isDirectory: (Path) -> Boolean = Files::isDirectory,
    ): String? {
        val recorded = recordedPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching {
            val host = toHostPath(recorded) ?: return null
            recorded.takeIf { isDirectory(Path.of(host)) }
        }.getOrNull()
    }
}
