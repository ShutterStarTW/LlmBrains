package com.shutterstar.agenthub.environment.ui

import com.shutterstar.agenthub.environment.config.discovery.ConfigHighlightReader
import com.shutterstar.agenthub.environment.config.model.AgentConfigSource
import com.shutterstar.agenthub.environment.config.model.ConfigScope
import java.time.Instant

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
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
    val configCount: Int = 0,
)

enum class SkillFilter {
    ALL,
    SHARED,
    CONFLICTS,
}

/** Global/Project narrowing for either Environment table, where both scopes may be mixed. */
enum class ScopeFilter {
    ALL,
    GLOBAL,
    PROJECT,
}

data class AgentEnvironmentSummary(
    val globalSkillCount: Int,
    val projectSkillCount: Int,
    val skillConflictCount: Int,
    val mcpServerCount: Int,
    val mcpConflictCount: Int,
    val instructionCount: Int,
    val configCount: Int = 0,
)

data class EnvironmentComparison(
    val agents: List<ComparisonAgent>,
    val rows: List<ComparisonRow>,
    /**
     * Discovery warnings, already worded for display. Kept out of [rows]: a warning is not an
     * environment item, so the panel shows them in their own collapsible bar instead of the table.
     */
    val warnings: List<String> = emptyList(),
)

data class ComparisonAgent(
    val id: String,
    val name: String,
)

data class ComparisonRow(
    val category: String,
    val name: String,
    /** Project context: the agents that have this item. Agent context: empty (the agent is implied). */
    val agentIds: Set<String>,
    val shared: Boolean = false,
    val conflict: Boolean = false,
    val skillId: String? = null,
    /** Agent context: the project this occurrence belongs to, or "Global". Null in project context. */
    val location: String? = null,
    /** "Global" / "Project" — set in agent context for the [ScopeFilter]. */
    val scope: String? = null,
    /** The physical source path of this occurrence (skill directory, MCP config, instruction file). */
    val sourcePath: String? = null,
    val skillScope: SkillScope? = null,
    val detail: String? = null,
    val extraDetailLines: List<String> = emptyList(),
)

object EnvironmentUiModel {
    fun filterRows(
        comparison: EnvironmentComparison,
        type: String,
        status: SkillFilter,
        scope: ScopeFilter,
        agentId: String?,
    ): List<ComparisonRow> = comparison.rows.filter { row ->
        (type == "All" || row.category == type) &&
            when (status) {
                SkillFilter.ALL -> true
                SkillFilter.SHARED -> row.shared
                SkillFilter.CONFLICTS -> row.conflict
            } &&
            when (scope) {
                ScopeFilter.ALL -> true
                ScopeFilter.GLOBAL -> row.scope == "Global"
                ScopeFilter.PROJECT -> row.scope == "Project"
            } &&
            (agentId == null || agentId in row.agentIds)
    }

    fun summaryLabel(summary: EnvironmentSummary): String = buildList {
        if (summary.skillCount > 0) add("Skills ${summary.skillCount}${conflictSuffix(summary.skillConflictCount)}")
        if (summary.mcpServerCount > 0) add("MCP ${summary.mcpServerCount}${conflictSuffix(summary.mcpConflictCount)}")
        if (summary.instructionCount > 0) add("Instructions ${summary.instructionCount}")
        if (summary.configCount > 0) add("Config ${summary.configCount}")
        if (summary.warningCount > 0) add("Warnings ${summary.warningCount}")
    }.joinToString(" · ").ifEmpty { "No environment items" }

    fun agentSummaryLabel(summary: AgentEnvironmentSummary, warningCount: Int): String = buildList {
        if (summary.globalSkillCount > 0) add("Global Skills ${summary.globalSkillCount}")
        if (summary.projectSkillCount > 0) add("Project Skills ${summary.projectSkillCount}")
        if (summary.mcpServerCount > 0) add("MCP ${summary.mcpServerCount}")
        if (summary.instructionCount > 0) add("Instructions ${summary.instructionCount}")
        if (summary.configCount > 0) add("Config ${summary.configCount}")
        val conflicts = summary.skillConflictCount + summary.mcpConflictCount
        if (conflicts > 0) add("Conflicts $conflicts")
        if (warningCount > 0) add("Warnings $warningCount")
    }.joinToString(" · ").ifEmpty { "No environment items" }

    private fun conflictSuffix(count: Int): String = when (count) {
        0 -> ""
        1 -> " (1 conflict)"
        else -> " ($count conflicts)"
    }

    fun summary(environment: ProjectEnvironment): EnvironmentSummary = EnvironmentSummary(
        skillCount = environment.skills.size,
        skillConflictCount = environment.skills.count { it.consistency == SkillConsistency.DIFFERENT },
        mcpServerCount = environment.mcpServers.size,
        mcpConflictCount = environment.mcpServers.count { it.consistency == McpConsistency.DIFFERENT },
        instructionCount = environment.instructions.size,
        configCount = environment.configs.size,
        warningCount = environment.warnings.size,
    )

    /**
     * The agent-side counterpart of [comparison]: one row per *occurrence* (a skill directory, an
     * MCP config entry, an instruction file) of this agent across its projects, so the same item
     * found in two projects gets two rows with their own location and path. Rows never name the
     * agent — in agent context that is noise — and [ComparisonRow.agentIds] stays empty; the
     * third table column shows [ComparisonRow.location] (project name or "Global") instead.
     */
    fun agentComparison(
        environment: AgentEnvironment,
        agentId: String,
    ): EnvironmentComparison {
        val rows = buildList {
            environment.skills.forEach { skill ->
                skill.sources
                    .filter { source ->
                        source.agentId == agentId ||
                            (source.agentId == null && AgentCapabilityRegistry.capabilitiesFor(agentId).supportsSharedAgentSkills)
                    }
                    .distinctBy { it.realPath ?: it.path }
                    .forEach { source ->
                        add(
                            ComparisonRow(
                                category = "Skill",
                                name = source.displayTitle?.takeIf(String::isNotBlank) ?: skill.name,
                                agentIds = emptySet(),
                                shared = source.shared,
                                conflict = skill.consistency == SkillConsistency.DIFFERENT,
                                skillId = skill.identity.id,
                                location = source.projectName ?: "Global",
                                scope = source.scope.label(),
                                sourcePath = source.path,
                                skillScope = source.scope,
                                detail = skill.consistency.label(),
                            ),
                        )
                    }
            }
            environment.mcpServers.forEach { server ->
                server.sources
                    .filter { it.agentId == agentId }
                    .forEach { source ->
                        add(
                            ComparisonRow(
                                category = "MCP",
                                name = server.name,
                                agentIds = emptySet(),
                                conflict = server.consistency == McpConsistency.DIFFERENT,
                                location = source.projectName ?: "Global",
                                scope = server.scope.name.lowercase().replaceFirstChar(Char::uppercase),
                                sourcePath = source.configPath,
                                detail = "${server.transport.name} · ${server.consistency.label()}",
                            ),
                        )
                    }
            }
            environment.configs.filter { it.agentId == agentId }.forEach { add(configRow(it, false)) }
            environment.instructions.forEach { source ->
                add(
                    ComparisonRow(
                        category = "Instruction",
                        name = fileName(source.path),
                        agentIds = emptySet(),
                        location = source.projectName ?: "Global",
                        scope = source.scope.label(),
                        sourcePath = source.path,
                        detail = source.path,
                    ),
                )
            }
        }
        val sortedRows = rows.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, ComparisonRow::category)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ComparisonRow::name)
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.location.orEmpty() },
        )
        val warnings = environment.warnings.map { warning ->
            // Agent context: the agent is implied, so it is never named.
            "${capabilityLabel(warning.capability)} · ${warning.scope.replaceFirstChar(Char::uppercase)}" +
                "${sharedSuffix(warning.agentId)} · ${warning.message}"
        }
        return EnvironmentComparison(agents = emptyList(), rows = sortedRows, warnings = warnings)
    }

    fun agentSummary(environment: AgentEnvironment): AgentEnvironmentSummary = AgentEnvironmentSummary(
        globalSkillCount = environment.skills.count { it.scope == SkillScope.GLOBAL },
        projectSkillCount = environment.skills.count { it.scope == SkillScope.PROJECT },
        skillConflictCount = environment.skills.count { it.consistency == SkillConsistency.DIFFERENT },
        mcpServerCount = environment.mcpServers.size,
        mcpConflictCount = environment.mcpServers.count { it.consistency == McpConsistency.DIFFERENT },
        instructionCount = environment.instructions.size,
        configCount = environment.configs.size,
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
                        name = globalSuffixed(displayName, skill.scope == SkillScope.GLOBAL),
                        agentIds = skill.compatibleAgents.intersect(environment.agentIds),
                        shared = skill.sources.any { it.shared },
                        conflict = skill.consistency == SkillConsistency.DIFFERENT,
                        skillId = skill.identity.id,
                        scope = skill.scope.label(),
                        sourcePath = (skill.sources.firstOrNull { it.shared } ?: skill.sources.firstOrNull())?.path,
                        skillScope = skill.scope,
                        detail = skill.consistency.label(),
                    ),
                )
            }
            environment.mcpServers.forEach { server ->
                add(
                    ComparisonRow(
                        category = "MCP",
                        name = globalSuffixed(server.name, server.scope == McpScope.GLOBAL),
                        agentIds = server.sources.mapTo(sortedSetOf()) { it.agentId },
                        conflict = server.consistency == McpConsistency.DIFFERENT,
                        scope = server.scope.name.lowercase().replaceFirstChar(Char::uppercase),
                        sourcePath = server.sources.firstOrNull()?.configPath,
                        detail = "${server.transport.name} · ${server.consistency.label()}",
                    ),
                )
            }
            environment.configs.filter { it.agentId in environment.agentIds }.forEach { add(configRow(it, true)) }
            environment.instructions.forEach { source ->
                add(
                    ComparisonRow(
                        category = "Instruction",
                        name = globalSuffixed(fileName(source.path), source.scope == InstructionScope.GLOBAL),
                        agentIds = source.agentIds,
                        scope = source.scope.label(),
                        sourcePath = source.path,
                        detail = source.path,
                    ),
                )
            }
        }
        val sortedRows = rows.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, ComparisonRow::category)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ComparisonRow::name),
        )
        val warnings = environment.warnings.map { warning ->
            val agent = warning.agentId?.takeIf { it in environment.agentIds }?.let(agentName)
            listOfNotNull(
                agent,
                capabilityLabel(warning.capability),
                "${warning.scope.replaceFirstChar(Char::uppercase)}${sharedSuffix(warning.agentId)}",
                warning.message,
            ).joinToString(" · ")
        }
        return EnvironmentComparison(agents, sortedRows, warnings)
    }

    private fun configRow(source: AgentConfigSource, projectContext: Boolean): ComparisonRow = ComparisonRow(
        category = "Config",
        name = if (projectContext) globalSuffixed(fileName(source.path), source.scope == ConfigScope.GLOBAL) else fileName(source.path),
        agentIds = if (projectContext) setOf(source.agentId) else emptySet(),
        location = if (projectContext) null else source.projectName ?: "Global",
        scope = source.scope.name.lowercase().replaceFirstChar(Char::uppercase),
        sourcePath = source.path,
        extraDetailLines = buildList {
            add("Format: ${source.format.name} · Size: ${source.sizeBytes} bytes · Modified: ${Instant.ofEpochMilli(source.modifiedAtEpochMillis)}")
            ConfigHighlightReader.sanitize(source.agentId, source.highlights).forEach { add("${it.label}: ${it.value}") }
        },
    )

    private fun sharedSuffix(agentId: String?): String = if (agentId == null) " · Shared" else ""

    /**
     * The description shared by both Environment views: source path, scope/location and status,
     * then type-specific metadata. Agent ownership is shown in the table, never in this strip.
     * Only normalized metadata and sanitized config highlights — never MCP commands, URLs or values.
     */
    fun detailLines(row: ComparisonRow): List<String> {
        val meta = listOfNotNull(
            row.scope?.let { "Scope: $it" },
            row.location?.let { "Location: $it" },
            "Shared source".takeIf { row.category == "Skill" && row.shared },
            // Instruction rows repeat their path as `detail`; the path already has its own line.
            row.detail?.takeIf { it.isNotBlank() && it != row.sourcePath },
        )
        return listOfNotNull(
            row.sourcePath?.takeIf { it.isNotBlank() },
            meta.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        ) + row.extraDetailLines
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

    /** Project context mixes global and project items: only the global ones are marked. */
    private fun globalSuffixed(name: String, global: Boolean): String = if (global) "$name (Global)" else name

}
