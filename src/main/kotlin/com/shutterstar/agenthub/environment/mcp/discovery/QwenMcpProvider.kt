package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class QwenMcpProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = "qwen"

    override fun discoverGlobal(): List<RawMcpServer> = JsonSettingsMcpSupport.discover(
        agentId,
        homeDirectory.resolve(".qwen/settings.json"),
        McpScope.GLOBAL,
        urlImpliesSse = true,
    )

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return JsonSettingsMcpSupport.discover(
            agentId,
            root.resolve(".qwen/settings.json"),
            McpScope.PROJECT,
            urlImpliesSse = true,
            projectName = project.name,
        )
    }

}
