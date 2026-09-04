package com.shutterstar.agenthub.environment.persistence

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.mcp.model.McpSource
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import java.time.Instant

object EnvironmentIndexStateMapper {
    fun encode(
        environments: Map<String, ProjectEnvironment>,
        refreshedAt: Instant,
    ): EnvironmentIndexState = EnvironmentIndexState(
        projects = environments.map { (projectId, environment) -> encodeProject(projectId, environment, refreshedAt) }
            .toMutableList(),
    )

    fun decode(state: EnvironmentIndexState): Map<String, ProjectEnvironment> {
        if (state.schemaVersion != EnvironmentIndexState.CURRENT_SCHEMA_VERSION) return emptyMap()

        return state.projects
            .mapNotNull(::decodeProject)
            .associateBy { it.projectId }
    }

    fun encodeProject(
        projectId: String,
        environment: ProjectEnvironment,
        refreshedAt: Instant,
    ) = EnvironmentIndexProjectState(
        projectId = projectId,
        refreshedAtEpochMillis = refreshedAt.toEpochMilli(),
        agentIds = environment.agentIds.toMutableList(),
        skills = environment.skills.map(::encodeSkill).toMutableList(),
        mcpServers = environment.mcpServers.map(::encodeMcpServer).toMutableList(),
        instructions = environment.instructions.map(::encodeInstruction).toMutableList(),
        warnings = environment.warnings.map(::encodeWarning).toMutableList(),
    )

    private fun encodeSkill(skill: AgentSkill) = EnvironmentIndexSkillState(
        identityId = skill.identity.id,
        name = skill.name,
        description = skill.description,
        scope = skill.scope.name,
        consistency = skill.consistency.name,
        compatibleAgents = skill.compatibleAgents.toMutableList(),
        sources = skill.sources.map { source ->
            EnvironmentIndexSkillSourceState(
                agentId = source.agentId,
                path = source.path,
                scope = source.scope.name,
                shared = source.shared,
                fingerprint = source.fingerprint,
                displayTitle = source.displayTitle,
                projectName = source.projectName,
            )
        }.toMutableList(),
    )

    private fun encodeMcpServer(server: McpServer) = EnvironmentIndexMcpServerState(
        id = server.id,
        name = server.name,
        transport = server.transport.name,
        environmentVariableNames = server.environmentVariableNames.toMutableList(),
        scope = server.scope.name,
        consistency = server.consistency.name,
        sources = server.sources.map { source ->
            EnvironmentIndexMcpSourceState(
                agentId = source.agentId,
                configPath = source.configPath,
                sourceName = source.sourceName,
                projectName = source.projectName,
            )
        }.toMutableList(),
    )

    private fun encodeInstruction(instruction: InstructionSource) = EnvironmentIndexInstructionState(
        path = instruction.path,
        scope = instruction.scope.name,
        type = instruction.type.name,
        agentIds = instruction.agentIds.toMutableList(),
        projectName = instruction.projectName,
    )

    private fun encodeWarning(warning: EnvironmentWarning) = EnvironmentIndexWarningState(
        capability = warning.capability,
        agentId = warning.agentId,
        scope = warning.scope,
        message = warning.message,
    )

    private fun decodeProject(project: EnvironmentIndexProjectState): ProjectEnvironment? {
        if (project.projectId.isBlank()) return null

        return ProjectEnvironment(
            projectId = project.projectId,
            agentIds = project.agentIds.toSortedSet(),
            skills = project.skills.mapNotNull(::decodeSkill),
            mcpServers = project.mcpServers.mapNotNull(::decodeMcpServer),
            instructions = project.instructions.mapNotNull(::decodeInstruction),
            warnings = project.warnings.mapNotNull(::decodeWarning),
        )
    }

    private fun decodeSkill(skill: EnvironmentIndexSkillState): AgentSkill? {
        if (skill.identityId.isBlank()) return null
        val scope = enumOrNull<SkillScope>(skill.scope) ?: return null
        val consistency = enumOrNull<SkillConsistency>(skill.consistency) ?: return null

        return AgentSkill(
            identity = SkillIdentity(skill.identityId),
            name = skill.name,
            description = skill.description,
            scope = scope,
            sources = skill.sources.mapNotNull(::decodeSkillSource),
            compatibleAgents = skill.compatibleAgents.toSet(),
            consistency = consistency,
        )
    }

    private fun decodeSkillSource(source: EnvironmentIndexSkillSourceState): SkillSource? {
        val scope = enumOrNull<SkillScope>(source.scope) ?: return null
        if (source.path.isBlank()) return null

        return SkillSource(
            agentId = source.agentId,
            path = source.path,
            scope = scope,
            shared = source.shared,
            fingerprint = source.fingerprint,
            displayTitle = source.displayTitle,
            projectName = source.projectName,
        )
    }

    private fun decodeMcpServer(server: EnvironmentIndexMcpServerState): McpServer? {
        if (server.id.isBlank()) return null
        val transport = enumOrNull<McpTransport>(server.transport) ?: return null
        val scope = enumOrNull<McpScope>(server.scope) ?: return null
        val consistency = enumOrNull<McpConsistency>(server.consistency) ?: return null

        return McpServer(
            id = server.id,
            name = server.name,
            transport = transport,
            command = null,
            args = emptyList(),
            url = null,
            environmentVariableNames = server.environmentVariableNames.toSortedSet(),
            sources = server.sources.mapNotNull(::decodeMcpSource),
            scope = scope,
            consistency = consistency,
        )
    }

    private fun decodeMcpSource(source: EnvironmentIndexMcpSourceState): McpSource? {
        if (source.agentId.isBlank() || source.configPath.isBlank()) return null

        return McpSource(
            agentId = source.agentId,
            configPath = source.configPath,
            sourceName = source.sourceName,
            projectName = source.projectName,
        )
    }

    private fun decodeInstruction(instruction: EnvironmentIndexInstructionState): InstructionSource? {
        if (instruction.path.isBlank()) return null
        val scope = enumOrNull<InstructionScope>(instruction.scope) ?: return null
        val type = enumOrNull<InstructionType>(instruction.type) ?: return null

        return InstructionSource(
            path = instruction.path,
            scope = scope,
            agentIds = instruction.agentIds.toSet(),
            type = type,
            projectName = instruction.projectName,
        )
    }

    private fun decodeWarning(warning: EnvironmentIndexWarningState): EnvironmentWarning? {
        if (warning.capability.isBlank() || warning.scope.isBlank() || warning.message.isBlank()) return null
        return EnvironmentWarning(
            capability = warning.capability,
            agentId = warning.agentId,
            scope = warning.scope,
            message = warning.message,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()
}
