package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class QwenMcpProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : McpProvider {
    override val agentId: String = "qwen"
    private val qwenDirectory = EnvHomeDirectorySupport.resolveGuarded("QWEN_HOME", homeDirectory, ".qwen")

    override fun discoverGlobal(): List<RawMcpServer> = JsonSettingsMcpSupport.discover(
        agentId,
        qwenDirectory.resolve("settings.json"),
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
