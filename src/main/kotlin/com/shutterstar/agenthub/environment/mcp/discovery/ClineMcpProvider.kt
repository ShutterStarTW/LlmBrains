package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class ClineMcpProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = "cline"

    override fun discoverGlobal(): List<RawMcpServer> = JsonSettingsMcpSupport.discover(
        agentId,
        homeDirectory.resolve(".cline/data/settings/cline_mcp_settings.json"),
        McpScope.GLOBAL,
    )

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return JsonSettingsMcpSupport.discover(
            agentId,
            root.resolve(".cline/mcp.json"),
            McpScope.PROJECT,
            projectName = project.name,
        )
    }

}
