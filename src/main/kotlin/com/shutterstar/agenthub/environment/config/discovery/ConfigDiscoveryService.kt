package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.config.model.AgentConfigSource
import com.shutterstar.agenthub.environment.config.model.ConfigRisk
import com.shutterstar.agenthub.environment.discovery.ProviderBackedDiscovery
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.OsDetector

class ConfigDiscoveryService(
    providers: List<ConfigProvider> = listOf(
        AntigravityConfigProvider(), ClaudeConfigProvider(), ClineConfigProvider(), CodexConfigProvider(),
        CopilotConfigProvider(), CursorConfigProvider(), GrokConfigProvider(), KiroConfigProvider(),
        OpenCodeConfigProvider(), QwenConfigProvider(),
    ),
    isAgentVisible: (String) -> Boolean = { true },
) : ProviderBackedDiscovery<ConfigProvider, AgentConfigSource>(
    providers, isAgentVisible, "config", "ConfigDiscovery", ConfigProvider::agentId,
) {
    fun discoverGlobal(): List<AgentConfigSource> = discoverGlobalRecordsWithWarnings().first
    fun discoverProject(project: DiscoveredProject): List<AgentConfigSource> = discoverProjectRecordsWithWarnings(project).first
    fun discover(project: DiscoveredProject): List<AgentConfigSource> = normalize(discoverGlobal() + discoverProject(project))

    override fun discoverGlobalFrom(provider: ConfigProvider) = provider.discoverGlobal()

    override fun discoverProjectFrom(provider: ConfigProvider, project: DiscoveredProject) =
        provider.discoverProject(project)

    override fun collect(
        scope: String,
        discover: (ConfigProvider) -> List<AgentConfigSource>,
    ): Pair<List<AgentConfigSource>, List<EnvironmentWarning>> {
        val (records, failures) = super.collect(scope, discover)
        val normalized = normalize(records)
        val risks = normalized.flatMap { source ->
            ConfigHighlightReader.sanitize(source.agentId, source.highlights).filter { it.risk == ConfigRisk.WARNING }.map {
                EnvironmentWarning("config", source.agentId, scope, "${it.label}: ${it.value} · ${source.path} (configured value; session overrides may differ)")
            }
        }
        return normalized to (failures + risks).distinct()
    }

    fun normalize(sources: List<AgentConfigSource>): List<AgentConfigSource> = sources
        .distinctBy { listOf(it.agentId, OsDetector.pathKey(it.path), it.scope.name) }
        .map { it.copy(highlights = ConfigHighlightReader.sanitize(it.agentId, it.highlights)) }
        .sortedWith(compareBy({ it.agentId }, { it.scope }, { OsDetector.pathKey(it.path) }))

}
