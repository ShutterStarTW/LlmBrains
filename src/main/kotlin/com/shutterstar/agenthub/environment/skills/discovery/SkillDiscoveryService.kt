package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.discovery.ProviderDiscoverySupport
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Logger

class SkillDiscoveryService(
    private val providers: List<SkillProvider> = listOf(
        SharedSkillProvider(),
        AntigravitySkillProvider(),
        ClaudeSkillProvider(),
        ClineSkillProvider(),
        CodexSkillProvider(),
        CopilotSkillProvider(),
        CursorSkillProvider(),
        GrokSkillProvider(),
        KiroSkillProvider(),
        OpenCodeSkillProvider(),
        QwenSkillProvider(),
    ),
) {
    fun discoverGlobal(): List<AgentSkill> = normalize(discoverGlobalRecords())

    fun discoverProject(project: DiscoveredProject): List<AgentSkill> = normalize(discoverProjectRecords(project))

    fun discover(project: DiscoveredProject): List<AgentSkill> =
        normalize(discoverGlobalRecords() + discoverProjectRecords(project))

    fun discoverGlobalRecords(): List<SkillSourceRecord> = discoverGlobalRecordsWithWarnings().first

    fun discoverProjectRecords(project: DiscoveredProject): List<SkillSourceRecord> =
        discoverProjectRecordsWithWarnings(project).first

    fun discoverGlobalRecordsWithWarnings(): Pair<List<SkillSourceRecord>, List<EnvironmentWarning>> =
        collect("global") { it.discoverGlobal() }

    fun discoverProjectRecordsWithWarnings(
        project: DiscoveredProject,
    ): Pair<List<SkillSourceRecord>, List<EnvironmentWarning>> = collect("project") { it.discoverProject(project) }

    private fun collect(
        scope: String,
        discover: (SkillProvider) -> List<SkillSourceRecord>,
    ): Pair<List<SkillSourceRecord>, List<EnvironmentWarning>> = ProviderDiscoverySupport.collect(
        providers = providers,
        capability = "skill",
        scope = scope,
        agentId = SkillProvider::agentId,
        logFailure = ::logFailure,
        discover = discover,
    )

    private fun logFailure(agentId: String?, scope: String, errorType: String) {
        LOG.warning("[SkillDiscovery] ${agentId ?: "shared"} $scope discovery failed: $errorType")
    }

    fun normalize(records: List<SkillSourceRecord>): List<AgentSkill> =
        records
            .groupBy { record -> NormalizationKey(normalizeName(record.name), record.scope) }
            .mapNotNull { (key, groupedRecords) -> normalizeGroup(key, groupedRecords) }
            .sortedWith(compareBy<AgentSkill> { it.name.lowercase(Locale.ROOT) }.thenBy { it.scope })

    private fun normalizeGroup(
        key: NormalizationKey,
        records: List<SkillSourceRecord>,
    ): AgentSkill? {
        if (key.normalizedName.isBlank()) {
            return null
        }

        val sortedRecords = records
            .distinctBy { listOf(it.agentId, it.path, it.scope, it.shared) }
            .sortedWith(
                compareByDescending<SkillSourceRecord> { it.shared }
                    .thenBy { it.agentId.orEmpty() }
                    .thenBy { it.path.lowercase(Locale.ROOT) },
            )
        val compatibleAgents = sortedRecords.flatMapTo(linkedSetOf()) { record ->
            if (record.shared) {
                AgentCapabilityRegistry.agentIdsSupportingSharedSkills()
            } else {
                setOfNotNull(record.agentId)
            }
        }
        val consistency = when {
            sortedRecords.size == 1 -> SkillConsistency.SINGLE_SOURCE
            sortedRecords.map(SkillSourceRecord::fingerprint).distinct().size == 1 -> SkillConsistency.IDENTICAL
            else -> SkillConsistency.DIFFERENT
        }

        return AgentSkill(
            identity = SkillIdentity(createIdentity(key)),
            name = sortedRecords.first().name.trim(),
            description = sortedRecords.firstNotNullOfOrNull { it.description?.takeIf(String::isNotBlank) },
            scope = key.scope,
            sources = sortedRecords.map { record ->
                SkillSource(
                    agentId = record.agentId,
                    path = record.path,
                    scope = record.scope,
                    shared = record.shared,
                    fingerprint = record.fingerprint,
                    displayTitle = record.displayTitle,
                    projectName = record.projectName,
                )
            },
            compatibleAgents = compatibleAgents,
            consistency = consistency,
        )
    }

    private fun createIdentity(key: NormalizationKey): String {
        val input = "${key.scope.name}:${key.normalizedName}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizeName(name: String): String =
        name.trim().lowercase(Locale.ROOT)

    private data class NormalizationKey(
        val normalizedName: String,
        val scope: SkillScope,
    )

    companion object {
        private val LOG = Logger.getLogger(SkillDiscoveryService::class.java.name)
    }
}
