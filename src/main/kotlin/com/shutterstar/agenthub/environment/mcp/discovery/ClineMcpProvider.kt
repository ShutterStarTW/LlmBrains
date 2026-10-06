package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class ClineMcpProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : McpProvider {
    override val agentId: String = "cline"

    // CLINE_DATA_DIR replaces ~/.cline/data (Cline CLI environment variable).
    private val dataDirectory = EnvHomeDirectorySupport.resolveGuarded("CLINE_DATA_DIR", homeDirectory, ".cline/data")

    override fun discoverGlobal(): List<RawMcpServer> = JsonSettingsMcpSupport.discover(
        agentId,
        dataDirectory.resolve("settings/cline_mcp_settings.json"),
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
