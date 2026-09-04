package com.shutterstar.agenthub.environment.mcp.discovery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object McpConfigFileReader {
    private const val MAXIMUM_CONFIG_BYTES = 8L * 1024L * 1024L

    fun read(path: Path): String? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        return runCatching {
            val size = Files.size(path)
            if (size > MAXIMUM_CONFIG_BYTES) {
                return null
            }
            Files.newInputStream(path).use { input ->
                String(input.readNBytes((size + 1).toInt()), StandardCharsets.UTF_8)
            }
        }.getOrNull()
    }
}
