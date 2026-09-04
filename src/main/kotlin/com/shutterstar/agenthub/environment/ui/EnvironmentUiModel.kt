package com.shutterstar.agenthub.environment.ui

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class EnvironmentSummary(
    val skillCount: Int,
    val skillConflictCount: Int,
    val mcpServerCount: Int,
    val mcpConflictCount: Int,
    val instructionCount: Int,
    val warningCount: Int,
)

data class EnvironmentRow(
    val title: String,
    val detail: String,
)

enum class SkillFilter {
    ALL,
    SHARED,
    CONFLICTS,
}

data class AgentEnvironmentSummary(
    val globalSkillCount: Int,
    val projectSkillCount: Int,
    val skillConflictCount: Int,
    val mcpServerCount: Int,
    val mcpConflictCount: Int,
    val instructionCount: Int,
)

data class EnvironmentComparison(
    val agents: List<ComparisonAgent>,
    val rows: List<ComparisonRow>,
)

data class ComparisonAgent(
    val id: String,
    val name: String,
)

data class ComparisonRow(
    val category: String,
    val name: String,
    val agentIds: Set<String>,
    val shared: Boolean = false,
    val conflict: Boolean = false,
)

object EnvironmentUiModel {
    fun summary(environment: ProjectEnvironment): EnvironmentSummary = EnvironmentSummary(
        skillCount = environment.skills.size,
        skillConflictCount = environment.skills.count { it.consistency == SkillConsistency.DIFFERENT },
        mcpServerCount = environment.mcpServers.size,
        mcpConflictCount = environment.mcpServers.count { it.consistency == McpConsistency.DIFFERENT },
        instructionCount = environment.instructions.size,
        warningCount = environment.warnings.size,
    )

    fun skillRows(
        environment: AgentEnvironment,
        agentId: String,
        filter: SkillFilter = SkillFilter.ALL,
    ): List<EnvironmentRow> = applySkillFilter(environment.skills, filter).flatMap { skill ->
        skill.sources
            .filter { it.agentId == null || it.agentId == agentId }
            .map { source ->
                val locationLabel = source.projectName
                    ?.let { "$it · ${source.scope.label()}" }
                    ?: source.scope.label()
                EnvironmentRow(
                    title = source.displayTitle ?: skill.name,
                    detail = "$locationLabel · ${skill.consistency.label()} · ${source.path}",
                )
            }
    }.sortedByTitle()

    private fun applySkillFilter(skills: List<AgentSkill>, filter: SkillFilter): List<AgentSkill> = when (filter) {
        SkillFilter.ALL -> skills
        SkillFilter.SHARED -> skills.filter { skill -> skill.sources.any { it.shared } }
        SkillFilter.CONFLICTS -> skills.filter { it.consistency == SkillConsistency.DIFFERENT }
    }

    fun mcpRows(
        environment: AgentEnvironment,
        agentId: String,
    ): List<EnvironmentRow> = environment.mcpServers.flatMap { server ->
        server.sources
            .filter { it.agentId == agentId }
            .map { source ->
                val projectLabel = source.projectName ?: "Global"
                EnvironmentRow(
                    title = server.name,
                    detail = "$projectLabel · ${server.transport.name} · ${server.consistency.label()}",
                )
            }
    }.sortedByTitle()

    fun instructionRows(
        environment: AgentEnvironment,
    ): List<EnvironmentRow> = environment.instructions.map { source ->
        val projectLabel = source.projectName ?: "Global"
        EnvironmentRow(
            title = "$projectLabel — ${fileName(source.path)}",
            detail = source.path,
        )
    }.sortedByTitle()

    fun warningRows(
        environment: AgentEnvironment,
        agentName: (String) -> String,
    ): List<EnvironmentRow> = environment.warnings.map { warning ->
        val source = warning.agentId?.let(agentName) ?: "Shared"
        EnvironmentRow(
            title = "${capabilityLabel(warning.capability)} · $source",
            detail = "${warning.scope.replaceFirstChar(Char::uppercase)} · ${warning.message}",
        )
    }.sortedByTitle()

    private fun List<EnvironmentRow>.sortedByTitle(): List<EnvironmentRow> =
        sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EnvironmentRow::title))

    fun agentSummary(environment: AgentEnvironment): AgentEnvironmentSummary = AgentEnvironmentSummary(
        globalSkillCount = environment.skills.count { it.scope == SkillScope.GLOBAL },
        projectSkillCount = environment.skills.count { it.scope == SkillScope.PROJECT },
        skillConflictCount = environment.skills.count { it.consistency == SkillConsistency.DIFFERENT },
        mcpServerCount = environment.mcpServers.size,
        mcpConflictCount = environment.mcpServers.count { it.consistency == McpConsistency.DIFFERENT },
        instructionCount = environment.instructions.size,
    )

    fun comparison(
        environment: ProjectEnvironment,
        agentName: (String) -> String,
    ): EnvironmentComparison {
        val agents = environment.agentIds
            .map { agentId -> ComparisonAgent(agentId, agentName(agentId)) }
            .sortedBy { it.name.lowercase() }
        val rows = buildList {
            environment.skills.forEach { skill ->
                val displayName = skill.sources.firstOrNull()?.displayTitle ?: skill.name
                add(
                    ComparisonRow(
                        category = "Skill",
                        name = "$displayName (${skill.scope.label()})",
                        agentIds = skill.compatibleAgents.intersect(environment.agentIds),
                        shared = skill.sources.any { it.shared },
                        conflict = skill.consistency == SkillConsistency.DIFFERENT,
                    ),
                )
            }
            environment.mcpServers.forEach { server ->
                add(
                    ComparisonRow(
                        category = "MCP",
                        name = "${server.name} (${server.scope.name.lowercase().replaceFirstChar(Char::uppercase)})",
                        agentIds = server.sources.mapTo(sortedSetOf()) { it.agentId },
                        conflict = server.consistency == McpConsistency.DIFFERENT,
                    ),
                )
            }
            environment.instructions.forEach { source ->
                add(
                    ComparisonRow(
                        category = "Instruction",
                        name = "${fileName(source.path)} (${source.scope.label()})",
                        agentIds = source.agentIds,
                    ),
                )
            }
            environment.warnings.forEach { warning ->
                val sharedLabel = if (warning.agentId == null) " · Shared" else ""
                add(
                    ComparisonRow(
                        category = "Warning",
                        name = "${capabilityLabel(warning.capability)} · " +
                            "${warning.scope.replaceFirstChar(Char::uppercase)}$sharedLabel · ${warning.message}",
                        agentIds = setOfNotNull(warning.agentId).intersect(environment.agentIds),
                    ),
                )
            }
        }
        val sortedRows = rows.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, ComparisonRow::category)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ComparisonRow::name),
        )
        return EnvironmentComparison(agents, sortedRows)
    }

    private fun SkillScope.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)

    private fun InstructionScope.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)

    private fun SkillConsistency.label(): String = when (this) {
        SkillConsistency.IDENTICAL -> "Consistent"
        SkillConsistency.DIFFERENT -> "Different contents"
        SkillConsistency.SINGLE_SOURCE -> "Single source"
    }

    private fun McpConsistency.label(): String = when (this) {
        McpConsistency.IDENTICAL -> "Consistent"
        McpConsistency.DIFFERENT -> "Different configuration"
        McpConsistency.SINGLE_SOURCE -> "Single source"
    }

    private fun capabilityLabel(capability: String): String = when (capability.lowercase()) {
        "mcp" -> "MCP"
        else -> capability.replaceFirstChar(Char::uppercase)
    }

    private fun fileName(path: String): String = try {
        Path.of(path).fileName?.toString() ?: path
    } catch (_: InvalidPathException) {
        path
    }
}
