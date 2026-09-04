package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.mcp.discovery.RawMcpServer
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.discovery.SkillSourceRecord
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.LinkedHashMap
import java.util.logging.Logger

class ProjectEnvironmentDiscoveryService(
    private val skillDiscovery: SkillDiscoveryService = SkillDiscoveryService(),
    private val mcpDiscovery: McpDiscoveryService = McpDiscoveryService(),
    private val instructionDiscovery: InstructionDiscoveryService = InstructionDiscoveryService(),
    private val persist: (projectId: String, environment: ProjectEnvironment) -> Unit = { _, _ -> },
) {
    private val cache = object : LinkedHashMap<CacheKey, ProjectEnvironment>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ProjectEnvironment>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private var cacheGeneration = 0L
    @Volatile private var globalCache: Pair<Long, GlobalRecords>? = null

    fun discover(project: DiscoveredProject): ProjectEnvironment {
        val agentIds = project.agents.mapTo(sortedSetOf()) { it.agentId }
        val cacheKey = CacheKey(
            projectId = project.identity.id,
            path = project.path,
            gitRoot = project.gitRoot,
            agentIds = agentIds,
        )
        val cacheSnapshot = synchronized(cache) {
            CacheSnapshot(cache[cacheKey], cacheGeneration)
        }
        cacheSnapshot.environment?.let { return it }

        val global = globalRecords()
        val (skillRecords, skillWarnings) = skillDiscovery.discoverProjectRecordsWithWarnings(project)
        val (mcpRecords, mcpWarnings) = mcpDiscovery.discoverProjectRecordsWithWarnings(project)
        val (instructionRecords, instructionWarnings) = instructionDiscovery.discoverProjectRecordsWithWarnings(project)
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
            warnings = (
                global.warnings + skillWarnings + mcpWarnings + instructionWarnings
                ).filter { warning -> warning.agentId == null || warning.agentId in agentIds },
        )
        val shouldPersist = synchronized(cache) {
            if (cacheSnapshot.generation == cacheGeneration) {
                cache[cacheKey] = environment
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
        globalCache?.let { (cachedGeneration, records) -> if (cachedGeneration == generation) return records }

        val (skills, skillWarnings) = skillDiscovery.discoverGlobalRecordsWithWarnings()
        val (mcpServers, mcpWarnings) = mcpDiscovery.discoverGlobalRecordsWithWarnings()
        val (instructions, instructionWarnings) = instructionDiscovery.discoverGlobalRecordsWithWarnings()
        val records = GlobalRecords(
            skills = skills,
            mcpServers = mcpServers,
            instructions = instructions,
            warnings = skillWarnings + mcpWarnings + instructionWarnings,
        )
        synchronized(cache) {
            if (cacheGeneration == generation) {
                globalCache = generation to records
            }
        }
        return records
    }

    fun forAgent(environment: ProjectEnvironment, agentId: String): AgentEnvironment = AgentEnvironment(
        agentId = agentId,
        skills = environment.skills.filter { agentId in it.compatibleAgents },
        mcpServers = environment.mcpServers.filter { server -> server.sources.any { it.agentId == agentId } },
        instructions = environment.instructions.filter { agentId in it.agentIds },
        warnings = environment.warnings.filter { it.agentId == null || it.agentId == agentId },
    )

    private data class GlobalRecords(
        val skills: List<SkillSourceRecord>,
        val mcpServers: List<RawMcpServer>,
        val instructions: List<InstructionSource>,
        val warnings: List<EnvironmentWarning>,
    )

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
        private val LOG = Logger.getLogger(ProjectEnvironmentDiscoveryService::class.java.name)
    }
}
