package com.shutterstar.agenthub.environment.discovery

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object AntigravityHomeSupport {
    fun configuredHome(): Path? {
        val configured = System.getenv("ANTIGRAVITY_HOME")?.trim()?.takeIf(String::isNotEmpty)
            ?: System.getenv("GEMINI_HOME")?.trim()?.takeIf(String::isNotEmpty)
        return configured?.let { runCatching { Path.of(it) }.getOrNull() }
    }

    fun <T> forEachPluginDirectory(
        pluginParentDirs: List<Path>,
        maxEntries: Int,
        action: (Path) -> List<T>,
    ): List<T> {
        val results = mutableListOf<T>()
        for (dir in pluginParentDirs) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
            runCatching {
                Files.list(dir).use { stream ->
                    stream
                        .limit(maxEntries.toLong())
                        .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .forEach { pluginDir -> results += action(pluginDir) }
                }
            }
        }
        return results
    }
}
