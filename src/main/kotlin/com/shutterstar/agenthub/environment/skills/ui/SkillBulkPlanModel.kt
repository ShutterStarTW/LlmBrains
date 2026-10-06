package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationCandidate
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import java.nio.file.Path

/** What happens inside one directory when a bulk operation is applied. */
internal enum class BulkPlanChange(val caption: String) {
    RECEIVES_SHARED("Receives the shared skills (moved here)"),
    MOVED_TO_SHARED("Skills move out to the shared folder and are replaced by a link"),
    LINKED("Own copies are replaced by links to the shared skills"),
    LINK_REMOVED("Links to the shared skills are removed (the shared skills are never touched)"),
    COPY_REMOVED("Identical copies are removed (backed up first)"),
    COPY_TO_LINK("Identical copies are replaced by links to the shared skills (backed up first)"),
}

/** One directory in a bulk plan: [path], the [change] made there, the agents that own it and the skills affected. */
internal data class BulkPlanDirectory(
    val path: String,
    val change: BulkPlanChange,
    val agentIds: List<String>,
    val skills: List<String>,
)

/**
 * The plan of a bulk operation (duplicate migration, redundant-copy clean-up) organized by
 * DIRECTORY, not by skill: every folder that gets something moved in, replaced or removed, with the
 * skills involved. Pure data — building it reads nothing from disk.
 */
internal data class SkillBulkPlanModel(
    val title: String,
    val applyLabel: String,
    val summary: String,
    val directories: List<BulkPlanDirectory>,
    val backupPath: String?,
    val warnings: List<String>,
) {
    companion object {
        fun migration(
            candidates: List<BulkMigrationCandidate>,
            sharedDirectory: Path?,
            backupDirectory: Path?,
            displayName: (String) -> String,
        ): SkillBulkPlanModel {
            val entries = mutableListOf<Entry>()
            val warnings = mutableListOf<String>()
            candidates.forEach { candidate ->
                val skill = candidate.skill
                val promote = candidate.requests.filterIsInstance<SkillSyncRequest.PromoteSkill>().firstOrNull()
                val linkedIds = candidate.requests.filterIsInstance<SkillSyncRequest.ShareSkill>().map { it.targetAgentId }
                promote?.let { entries += Entry(parentOf(it.sourcePath.toString()), BulkPlanChange.MOVED_TO_SHARED, it.sourceAgentId, skill.name) }
                linkedIds.forEach { agentId ->
                    skill.sources.firstOrNull { it.agentId == agentId && !it.shared }?.let {
                        entries += Entry(parentOf(it.path), BulkPlanChange.LINKED, agentId, skill.name)
                    }
                }
                val promotedPath = promote?.sourcePath?.toString()
                val system = skill.sources.filter { it.system && (it.agentId in linkedIds || it.path == promotedPath) }
                    .mapNotNull { it.agentId }.distinct().sorted()
                if (system.isNotEmpty()) {
                    warnings += "${skill.name}: includes a vendor-provided copy (${system.joinToString(", ", transform = displayName)}). " +
                        "It is replaced by a link to the shared skill (backed up first), and the agent may restore it on its next update."
                }
            }
            sharedDirectory?.let { shared ->
                candidates.forEach { entries += Entry(shared.toString(), BulkPlanChange.RECEIVES_SHARED, null, it.skill.name) }
            }
            val count = candidates.size
            return SkillBulkPlanModel(
                title = "Migrate Duplicate Skills",
                applyLabel = if (count == 1) "Migrate 1 Skill" else "Migrate $count Skills",
                summary = "Identical copies are moved into the shared folder and the other agents are linked to them. " +
                    "Existing content is backed up first, and one failing skill does not stop the rest.",
                directories = group(entries),
                backupPath = backupDirectory?.toString(),
                warnings = warnings,
            )
        }

        fun cleanup(work: List<RedundantCopyWork>, backupDirectory: Path?): SkillBulkPlanModel {
            val entries = mutableListOf<Entry>()
            work.forEach { item ->
                val skill = item.candidate.skill
                val name = skill.name + if (item.context.project != null) " (${item.context.project.name})" else ""
                item.candidate.agentIds.forEach { agentId ->
                    val source = skill.sources.firstOrNull { it.agentId == agentId && !it.shared } ?: return@forEach
                    val change = if (agentId in item.candidate.linkedAgentIds) BulkPlanChange.LINK_REMOVED else BulkPlanChange.COPY_REMOVED
                    entries += Entry(parentOf(source.path), change, agentId, name)
                }
                item.candidate.convertAgentIds.forEach { agentId ->
                    val source = skill.sources.firstOrNull { it.agentId == agentId && !it.shared } ?: return@forEach
                    entries += Entry(parentOf(source.path), BulkPlanChange.COPY_TO_LINK, agentId, name)
                }
            }
            val count = work.size
            return SkillBulkPlanModel(
                title = "Clean Up Redundant Copies",
                applyLabel = if (count == 1) "Clean Up 1 Skill" else "Clean Up $count Skills",
                summary = "A link is only unlinked; a copy is removed after a backup. An identical copy of an agent that cannot " +
                    "read the shared folder is replaced by a link. Copies that differ are never touched.",
                directories = group(entries),
                backupPath = backupDirectory?.toString(),
                warnings = emptyList(),
            )
        }

        private class Entry(val path: String, val change: BulkPlanChange, val agentId: String?, val skill: String)

        private fun group(entries: List<Entry>): List<BulkPlanDirectory> =
            entries.groupBy { it.path to it.change }
                .map { (key, items) ->
                    BulkPlanDirectory(
                        path = key.first,
                        change = key.second,
                        agentIds = items.mapNotNull { it.agentId }.distinct().sorted(),
                        skills = items.map { it.skill }.distinct().sorted(),
                    )
                }
                .sortedWith(compareBy({ it.change.ordinal }, { it.path }))

        private fun parentOf(path: String): String = runCatching { Path.of(path).parent?.toString() }.getOrNull() ?: path
    }
}
