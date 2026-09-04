package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectComparators
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import com.shutterstar.agenthub.projects.resolve.ResolvedProject
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger

data class ProjectDiscoveryWarning(
    val agentId: String,
    val message: String,
)

data class ProjectDiscoveryResult(
    val projects: List<DiscoveredProject>,
    val warnings: List<ProjectDiscoveryWarning>,
)

class ProjectDiscoveryService(
    private val providers: List<AgentProjectProvider> = AgentProjectProviders.all,
    private val projectResolver: ProjectResolver = ProjectResolver(),
    private val isAgentRelevant: (String) -> Boolean = { true },
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
) {
    fun discoverProjects(): List<DiscoveredProject> = discover().projects

    fun discover(): ProjectDiscoveryResult {
        val warnings = mutableListOf<ProjectDiscoveryWarning>()
        val availableProviders = providers.filter { provider ->
            if (!isAgentRelevant(provider.agentId)) {
                false
            } else {
                runCatching { provider.isAvailable() }.getOrElse { error ->
                    warnings += warning(provider, "Availability check failed", error)
                    false
                }
            }
        }
        if (availableProviders.isEmpty()) return ProjectDiscoveryResult(emptyList(), warnings)

        val executor = Executors.newFixedThreadPool(minOf(availableProviders.size, MAX_PROVIDER_THREADS))
        val futures = availableProviders.associateWith { provider ->
            executor.submit(Callable { provider.discover() })
        }
        val rawProjects = mutableListOf<RawAgentProject>()
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(providerTimeoutMillis)
        try {
            availableProviders.forEach { provider ->
                try {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0) throw TimeoutException()
                    val discovered = futures.getValue(provider).get(remainingNanos, TimeUnit.NANOSECONDS)
                    rawProjects += discovered.filter { it.agentId == provider.agentId }
                    LOG.fine("[ProjectDiscovery] ${provider.agentId}: discovered ${discovered.size} sessions")
                } catch (_: TimeoutException) {
                    futures.getValue(provider).cancel(true)
                    warnings += warning(provider, "Discovery timed out")
                } catch (error: ExecutionException) {
                    warnings += warning(provider, "Discovery failed", error.cause ?: error)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    warnings += warning(provider, "Discovery interrupted")
                }
            }
        } finally {
            executor.shutdownNow()
        }

        val projects = aggregate(deduplicate(rawProjects))
        LOG.fine("[ProjectResolver] Resolved ${rawProjects.size} sessions to ${projects.size} projects")
        return ProjectDiscoveryResult(projects, warnings)
    }

    private fun aggregate(rawProjects: List<RawAgentProject>): List<DiscoveredProject> {
        val resolved = rawProjects.map { raw -> ResolvedSession(raw, projectResolver.resolveProject(raw)) }
        return resolved.groupBy { it.project.identity.id }
            .map { (projectId, sessions) ->
                val representative = sessions.maxWithOrNull(
                    compareBy<ResolvedSession> { it.project.path != null }
                        .thenBy { it.project.identity.gitRemote != null }
                        .thenBy { activity(it.raw) },
                ) ?: error("A grouped project must contain at least one session")
                val agentProjects = sessions.groupBy { it.raw.agentId }
                    .map { (agentId, agentSessions) ->
                        val mappedSessions = agentSessions.map { it.toAgentSession() }
                            .sortedWith(ProjectComparators.agentSessionByRecency)
                        AgentProject(
                            agentId = agentId,
                            projectId = projectId,
                            sessionCount = mappedSessions.size,
                            lastActivity = mappedSessions.mapNotNull { it.updatedAt ?: it.startedAt }.maxOrNull(),
                            sessions = mappedSessions,
                        )
                    }
                    .sortedBy { it.agentId }
                DiscoveredProject(
                    identity = representative.project.identity,
                    name = representative.project.name,
                    path = representative.project.path,
                    gitRoot = representative.project.identity.gitRoot,
                    gitRemote = representative.project.identity.gitRemote,
                    currentBranch = representative.project.currentBranch,
                    agents = agentProjects,
                    lastActivity = agentProjects.mapNotNull { it.lastActivity }.maxOrNull(),
                )
            }
            .sortedWith(ProjectComparators.discoveredProjectByRecency)
    }

    private fun deduplicate(rawProjects: List<RawAgentProject>): List<RawAgentProject> =
        rawProjects.groupBy { it.agentId to it.sessionId }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<RawAgentProject> { !it.rawProjectPath.isNullOrBlank() }
                        .thenBy { activity(it) },
                ) ?: error("A duplicate group must contain at least one session")
            }

    private fun warning(
        provider: AgentProjectProvider,
        message: String,
        error: Throwable? = null,
    ): ProjectDiscoveryWarning {
        val suffix = error?.let { ": ${it.javaClass.simpleName}" }.orEmpty()
        LOG.log(Level.WARNING, "[ProjectDiscovery] ${provider.agentId}: $message$suffix")
        return ProjectDiscoveryWarning(provider.agentId, message + suffix)
    }

    private data class ResolvedSession(
        val raw: RawAgentProject,
        val project: ResolvedProject,
    ) {
        fun toAgentSession(): AgentSession = AgentSession(
            id = raw.sessionId,
            agentId = raw.agentId,
            projectPath = project.path,
            startedAt = raw.startedAt,
            updatedAt = raw.updatedAt,
            sourcePath = raw.sourcePath,
            title = raw.metadata["title"],
            nativeResumeId = raw.sessionId.takeIf { raw.agentId in NATIVE_RESUME_AGENT_IDS },
        )
    }

    companion object {
        private const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 30_000L
        private const val MAX_PROVIDER_THREADS = 4
        private val NATIVE_RESUME_AGENT_IDS = setOf("claude", "codex", "opencode")
        private val LOG = Logger.getLogger(ProjectDiscoveryService::class.java.name)

        private fun activity(raw: RawAgentProject): Instant = raw.updatedAt ?: raw.startedAt ?: Instant.MIN
    }
}
