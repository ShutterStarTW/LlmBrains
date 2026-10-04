package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.discovery.ProviderBackedDiscovery
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

class SkillDiscoveryService(
    providers: List<SkillProvider> = listOf(
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
    /** Only installed agents are discovered; the shared `.agents/skills` root needs one compatible installed agent. */
    private val isAgentVisible: (String) -> Boolean = { true },
) : ProviderBackedDiscovery<SkillProvider, SkillSourceRecord>(
    providers, isAgentVisible, "skill", "SkillDiscovery", SkillProvider::agentId, "shared",
) {
    fun discoverGlobal(): List<AgentSkill> = normalize(discoverGlobalRecords())

    fun discoverProject(project: DiscoveredProject): List<AgentSkill> = normalize(discoverProjectRecords(project))

    fun discover(project: DiscoveredProject): List<AgentSkill> =
        normalize(discoverGlobalRecords() + discoverProjectRecords(project))

    override fun discoverGlobalFrom(provider: SkillProvider) = provider.discoverGlobal()

    override fun discoverProjectFrom(provider: SkillProvider, project: DiscoveredProject) =
        provider.discoverProject(project)

    override fun isVisible(provider: SkillProvider): Boolean =
        provider.agentId?.let(isAgentVisible)
            ?: AgentCapabilityRegistry.agentIdsSupportingSharedSkills().any(isAgentVisible)

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
        // Several agents scan the same compatibility folder, but only its owner knows it is vendor-synced
        // (e.g. ~/.claude/skills/synced): the folder is a system one for every agent that lists it.
        val systemPaths = sortedRecords.filter(SkillSourceRecord::system).mapTo(hashSetOf()) { it.path }
        val compatibleAgents = sortedRecords.flatMapTo(linkedSetOf()) { record ->
            if (record.shared) {
                AgentCapabilityRegistry.agentIdsSupportingSharedSkills().filter(isAgentVisible)
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
                    system = record.system || record.path in systemPaths,
                    realPath = if (sortedRecords.size > 1) {
                        runCatching { Path.of(record.path).toRealPath().toString() }.getOrNull()
                    } else {
                        null
                    },
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
}
