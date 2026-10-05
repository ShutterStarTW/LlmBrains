package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService
import com.shutterstar.agenthub.environment.config.model.AgentConfigSource

import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.mcp.discovery.RawMcpServer
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.discovery.SkillSourceRecord
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class ProjectEnvironmentDiscoveryService(
    private val skillDiscovery: SkillDiscoveryService = SkillDiscoveryService(),
    private val mcpDiscovery: McpDiscoveryService = McpDiscoveryService(),
    private val instructionDiscovery: InstructionDiscoveryService = InstructionDiscoveryService(),
    private val persist: (projectId: String, environment: ProjectEnvironment) -> Unit = { projectId, environment ->
        runCatching { EnvironmentIndexService.getInstance() }.getOrNull()?.record(projectId, environment)
    },
    /** Agents that are not (or not known to be) installed contribute nothing to a project's environment. */
    private val isAgentVisible: (String) -> Boolean = { true },
    private val nowNanos: () -> Long = System::nanoTime,
    private val configDiscovery: ConfigDiscoveryService = ConfigDiscoveryService(isAgentVisible = isAgentVisible),
) {
    private val cache = object : LinkedHashMap<CacheKey, CachedEnvironment>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedEnvironment>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private var cacheGeneration = 0L
    /** Changes on explicit invalidation, independently of project identity or list filtering. */
    val generation: Long get() = synchronized(cache) { cacheGeneration }
    @Volatile private var globalCache: CachedGlobalRecords? = null

    fun discover(project: DiscoveredProject): ProjectEnvironment {
        val agentIds = project.agents.map { it.agentId }.filterTo(sortedSetOf(), isAgentVisible)
        val cacheKey = CacheKey(
            projectId = project.identity.id,
            path = project.path,
            gitRoot = project.gitRoot,
            agentIds = agentIds,
        )
        val cacheSnapshot = synchronized(cache) {
            val cached = cache[cacheKey]?.takeIf { nowNanos() - it.createdAtNanos < CACHE_TTL_NANOS }
            CacheSnapshot(cached?.environment, cacheGeneration)
        }
        cacheSnapshot.environment?.let { return it }

        val global = globalRecords()
        val (skillRecords, skillWarnings) = skillDiscovery.discoverProjectRecordsWithWarnings(project)
        val (mcpRecords, mcpWarnings) = mcpDiscovery.discoverProjectRecordsWithWarnings(project)
        val (instructionRecords, instructionWarnings) = instructionDiscovery.discoverProjectRecordsWithWarnings(project)
        val (configRecords, configWarnings) = configDiscovery.discoverProjectRecordsWithWarnings(project)
        val environment = ProjectEnvironment(
            projectId = project.identity.id,
            agentIds = agentIds,
            skills = skillDiscovery.normalize(
                (global.skills + skillRecords).filter { record ->
                    record.shared || record.agentId == null || record.agentId in agentIds
                },
            ),
            mcpServers = mcpDiscovery.normalize(
                (global.mcpServers + mcpRecords).filter { it.agentId in agentIds },
            ),
            instructions = instructionDiscovery.normalize(
                (global.instructions + instructionRecords).filter { source ->
                    source.agentIds.any { it in agentIds }
                },
            ),
            configs = configDiscovery.normalize((global.configs + configRecords).filter { it.agentId in agentIds }),
            warnings = (
                global.warnings + skillWarnings + mcpWarnings + instructionWarnings + configWarnings
                ).filter { warning -> warning.agentId == null || warning.agentId in agentIds },
        )
        val shouldPersist = synchronized(cache) {
            if (cacheSnapshot.generation == cacheGeneration && environment.warnings.none { it.capability != "config" || it.message.startsWith("Discovery failed:") }) {
                cache[cacheKey] = CachedEnvironment(environment, nowNanos())
                true
            } else {
                false
            }
        }
        if (shouldPersist) {
            runCatching { persist(project.identity.id, environment) }
                .onFailure { error ->
                    LOG.warning("[ProjectEnvironmentDiscovery] persist failed for ${project.identity.id}: ${error.javaClass.simpleName}")
                }
        }
        return environment
    }

    fun invalidateAll() {
        synchronized(cache) {
            cache.clear()
            cacheGeneration++
        }
    }

    private fun globalRecords(): GlobalRecords {
        val generation = synchronized(cache) { cacheGeneration }
        globalCache?.let { cached ->
            if (cached.generation == generation && nowNanos() - cached.createdAtNanos < CACHE_TTL_NANOS) {
                return cached.records
            }
        }

        val (skills, skillWarnings) = skillDiscovery.discoverGlobalRecordsWithWarnings()
        val (mcpServers, mcpWarnings) = mcpDiscovery.discoverGlobalRecordsWithWarnings()
        val (instructions, instructionWarnings) = instructionDiscovery.discoverGlobalRecordsWithWarnings()
        val (configs, configWarnings) = configDiscovery.discoverGlobalRecordsWithWarnings()
        val records = GlobalRecords(
            skills = skills,
            mcpServers = mcpServers,
            instructions = instructions,
            configs = configs,
            warnings = skillWarnings + mcpWarnings + instructionWarnings + configWarnings,
        )
        synchronized(cache) {
            if (cacheGeneration == generation && records.warnings.none { it.capability != "config" || it.message.startsWith("Discovery failed:") }) {
                globalCache = CachedGlobalRecords(generation, nowNanos(), records)
            }
        }
        return records
    }

    /** Returns only already-discovered records; safe for the initial UI render without discovery. */
    fun cachedGlobalConfigsForAgent(agentId: String): AgentEnvironment? {
        if (!isAgentVisible(agentId)) return AgentEnvironment(agentId, emptyList(), emptyList(), emptyList())
        val cached = globalCache?.takeIf { it.generation == generation } ?: return null
        return globalConfigsForAgent(agentId, cached.records)
    }

    /**
     * Everything global that belongs to [agentId] - skills, MCP servers, instructions and configs - so an
     * installed agent without any session still has its environment. For an agent with sessions the
     * project environments repeat the global items; the aggregation drops those duplicates.
     */
    private fun globalConfigsForAgent(agentId: String, global: GlobalRecords): AgentEnvironment {
        val environment = ProjectEnvironment(
            projectId = "global:$agentId",
            agentIds = sortedSetOf(agentId),
            skills = skillDiscovery.normalize(global.skills.filter { it.shared || it.agentId == null || it.agentId == agentId }),
            mcpServers = mcpDiscovery.normalize(global.mcpServers.filter { it.agentId == agentId }),
            instructions = instructionDiscovery.normalize(global.instructions.filter { agentId in it.agentIds }),
            configs = global.configs.filter { it.agentId == agentId },
            warnings = global.warnings.filter { it.agentId == null || it.agentId == agentId },
        )
        return forAgent(environment, agentId)
    }

    /** Global inventory is available even before an agent has any indexed sessions. */
    fun globalConfigsForAgent(agentId: String): AgentEnvironment {
        if (!isAgentVisible(agentId)) return AgentEnvironment(agentId, emptyList(), emptyList(), emptyList())
        return globalConfigsForAgent(agentId, globalRecords())

    }

    fun forAgent(environment: ProjectEnvironment, agentId: String): AgentEnvironment = AgentEnvironment(
        agentId = agentId,
        skills = environment.skills.filter { agentId in it.compatibleAgents },
        mcpServers = environment.mcpServers.filter { server -> server.sources.any { it.agentId == agentId } },
        instructions = environment.instructions.filter { agentId in it.agentIds },
        configs = environment.configs.filter { it.agentId == agentId },
        warnings = environment.warnings.filter { it.agentId == null || it.agentId == agentId },
    )

    private data class GlobalRecords(
        val skills: List<SkillSourceRecord>,
        val mcpServers: List<RawMcpServer>,
        val instructions: List<InstructionSource>,
        val configs: List<AgentConfigSource>,
        val warnings: List<EnvironmentWarning>,
    )

    private data class CachedEnvironment(val environment: ProjectEnvironment, val createdAtNanos: Long)

    private data class CachedGlobalRecords(val generation: Long, val createdAtNanos: Long, val records: GlobalRecords)

    private data class CacheKey(
        val projectId: String,
        val path: String?,
        val gitRoot: String?,
        val agentIds: Set<String>,
    )

    private data class CacheSnapshot(
        val environment: ProjectEnvironment?,
        val generation: Long,
    )

    companion object {
        private const val MAX_CACHE_ENTRIES = 256
        private val CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5)
        private val LOG = Logger.getLogger(ProjectEnvironmentDiscoveryService::class.java.name)
    }
}
