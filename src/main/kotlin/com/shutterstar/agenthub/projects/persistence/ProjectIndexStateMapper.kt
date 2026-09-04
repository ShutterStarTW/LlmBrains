package com.shutterstar.agenthub.projects.persistence

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectComparators
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import java.time.Instant

object ProjectIndexStateMapper {
    fun encode(
        projects: List<DiscoveredProject>,
        refreshedAt: Instant,
    ): ProjectIndexState = ProjectIndexState(
        refreshedAtEpochMillis = refreshedAt.toEpochMilli(),
        projects = projects.map(::encodeProject).toMutableList(),
    )

    fun decode(state: ProjectIndexState): List<DiscoveredProject> {
        if (state.schemaVersion != ProjectIndexState.CURRENT_SCHEMA_VERSION) return emptyList()

        return state.projects.mapNotNull(::decodeProject)
            .sortedWith(ProjectComparators.discoveredProjectByRecency)
    }

    private fun encodeProject(project: DiscoveredProject) = ProjectIndexProjectState(
        identityId = project.identity.id,
        name = project.name,
        path = project.path,
        gitRoot = project.gitRoot,
        gitRemote = project.gitRemote,
        currentBranch = project.currentBranch,
        agents = project.agents.map(::encodeAgent).toMutableList(),
    )

    private fun encodeAgent(agent: AgentProject) = ProjectIndexAgentState(
        agentId = agent.agentId,
        sessions = agent.sessions.map(::encodeSession).toMutableList(),
    )

    private fun encodeSession(session: AgentSession) = ProjectIndexSessionState(
        id = session.id,
        projectPath = session.projectPath,
        startedAtEpochMillis = session.startedAt?.toEpochMilli(),
        updatedAtEpochMillis = session.updatedAt?.toEpochMilli(),
        sourcePath = session.sourcePath,
        nativeResumeId = session.nativeResumeId,
    )

    private fun decodeProject(project: ProjectIndexProjectState): DiscoveredProject? {
        if (project.identityId.isBlank()) return null

        val agents = project.agents.mapNotNull { decodeAgent(it, project.identityId) }
            .sortedBy { it.agentId }
        val lastActivity = agents.mapNotNull { it.lastActivity }.maxOrNull()
        return DiscoveredProject(
            identity = ProjectIdentity(
                id = project.identityId,
                canonicalPath = project.path,
                gitRoot = project.gitRoot,
                gitRemote = project.gitRemote,
            ),
            name = project.name.ifBlank {
                project.path?.replace('\\', '/')?.substringAfterLast('/') ?: "Unknown Project"
            },
            path = project.path,
            gitRoot = project.gitRoot,
            gitRemote = project.gitRemote,
            currentBranch = project.currentBranch,
            agents = agents,
            lastActivity = lastActivity,
        )
    }

    private fun decodeAgent(
        agent: ProjectIndexAgentState,
        projectId: String,
    ): AgentProject? {
        if (agent.agentId.isBlank()) return null

        val sessions = agent.sessions.mapNotNull { decodeSession(it, agent.agentId) }
            .sortedWith(ProjectComparators.agentSessionByRecency)
        return AgentProject(
            agentId = agent.agentId,
            projectId = projectId,
            sessionCount = sessions.size,
            lastActivity = sessions.mapNotNull { it.updatedAt ?: it.startedAt }.maxOrNull(),
            sessions = sessions,
        )
    }

    private fun decodeSession(
        session: ProjectIndexSessionState,
        agentId: String,
    ): AgentSession? {
        if (session.id.isBlank()) return null

        return AgentSession(
            id = session.id,
            agentId = agentId,
            projectPath = session.projectPath,
            startedAt = session.startedAtEpochMillis?.let(Instant::ofEpochMilli),
            updatedAt = session.updatedAtEpochMillis?.let(Instant::ofEpochMilli),
            sourcePath = session.sourcePath,
            title = null,
            nativeResumeId = session.nativeResumeId,
        )
    }
}
