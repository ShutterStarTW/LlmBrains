package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Shared "read a TOML config file, warn on unreadable/malformed content, parse via
 * CodexMcpConfigParser" flow used by every TOML-based MCP provider (Codex, Grok).
 */
internal object TomlMcpConfigReader {
    fun read(
        configPath: Path,
        agentLabel: String,
        agentId: String,
        scope: McpScope,
        projectName: String?,
        logger: Logger,
    ): List<RawMcpServer> {
        val content = McpConfigFileReader.read(configPath)
        if (content == null) {
            if (Files.exists(configPath)) {
                logger.warning("[McpDiscovery] $agentLabel: unreadable or oversized config: $configPath")
            }
            return emptyList()
        }
        val normalizedPath = configPath.toAbsolutePath().normalize().toString()
        val servers = CodexMcpConfigParser.parse(content, normalizedPath, scope, projectName, agentId)
        if (servers == null) {
            logger.warning("[McpDiscovery] $agentLabel: malformed config: $configPath")
            return emptyList()
        }
        return servers
    }
}
