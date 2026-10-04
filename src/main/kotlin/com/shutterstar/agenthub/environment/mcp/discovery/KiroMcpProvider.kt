package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class KiroMcpProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = "kiro"
    private val kiroDirectory = EnvHomeDirectorySupport.resolveGuarded("KIRO_HOME", homeDirectory, ".kiro")

    override fun discoverGlobal(): List<RawMcpServer> = JsonSettingsMcpSupport.discover(
        agentId,
        kiroDirectory.resolve("settings/mcp.json"),
        McpScope.GLOBAL,
    )

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return JsonSettingsMcpSupport.discover(
            agentId,
            root.resolve(".kiro/settings/mcp.json"),
            McpScope.PROJECT,
            projectName = project.name,
        )
    }

}
