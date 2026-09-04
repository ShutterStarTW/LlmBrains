package com.shutterstar.agenthub.environment.mcp.discovery

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Shared "read a config file, warn on unreadable/malformed content, parse as JSON(C)" flow used
 * by every JSON-based MCP provider. The knobs below intentionally preserve each provider's
 * pre-existing behavior (whether the existence check follows symlinks, whether blank content is
 * silently ignored, JSON vs JSONC) rather than unifying it — this only removes the duplicated
 * control flow, not the per-agent differences.
 */
internal object McpConfigRootReader {
    fun read(
        configPath: Path,
        agentLabel: String,
        logger: Logger,
        jsonc: Boolean = false,
        warnOnlyIfFileExists: Boolean = true,
        existenceCheckFollowsLinks: Boolean = true,
        ignoreBlankContent: Boolean = false,
    ): JsonObject? {
        val content = McpConfigFileReader.read(configPath)
        if (content == null) {
            val shouldWarn = !warnOnlyIfFileExists || if (existenceCheckFollowsLinks) {
                Files.exists(configPath)
            } else {
                Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)
            }
            if (shouldWarn) {
                logger.warning("[McpDiscovery] $agentLabel: unreadable or oversized config: $configPath")
            }
            return null
        }
        if (ignoreBlankContent && content.isBlank()) return null
        val root = (if (jsonc) SafeJsonParser.parseJsonc(content) else SafeJsonParser.parse(content)) as? JsonObject
        if (root == null) {
            logger.warning("[McpDiscovery] $agentLabel: malformed config: $configPath")
        }
        return root
    }
}
