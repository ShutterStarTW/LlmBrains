package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.UserFacingError
import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.PreparedSkillSync
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService
import com.shutterstar.agenthub.environment.skills.sync.execution.StoredBackupRecord
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationCandidate
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationDetector
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationOutcome
import com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyCandidate
import com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyDetector
import com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyOutcome
import com.shutterstar.agenthub.environment.skills.sync.migration.duplicateOwner
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncTargetOutcome
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoPreview
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates target selection, background planning, confirmation and serialized execution. */
internal class SkillMutationController(
    private val project: Project,
    private val service: SkillSyncApplicationService,
    private val executor: Executor,
    private val deliver: (() -> Unit) -> Unit,
    /** Receives the operation feedback; `sticky` messages (problems) must stay until the next operation replaces them. */
    private val statusSink: (String, Boolean) -> Unit,
    private val refresh: (((SkillOccurrenceRow) -> Boolean)?) -> Unit,
    private val notify: (String, String, NotificationType) -> Unit = { _, _, _ -> },
    private val bulkStateChanged: (Boolean) -> Unit = {},
    /** Read per operation: whether plans/previews are shown for approval ("Skill Sync Settings"). */
    private val reviewPlan: () -> Boolean = {
        runCatching { service.currentSettings().reviewPlanBeforeApplying }.getOrDefault(true)
    },
    /** Shows every agent that can receive the skill and returns the agents that should be shared afterwards, or null if cancelled. */
    private val chooseShareTargets: (options: List<ShareOption>, groupTitle: String, groupedIds: Set<String>) -> Set<String>? = { options, groupTitle, groupedIds ->
        val review = reviewPlan()
        val tail = if (review) " You review the plan before anything changes." else ""
        val shared = options.filter { it.shared }
        val locked = shared.filterNot { it.removable }
        SkillTargetMultiSelectDialog(
            project,
            options.map { it.agentId },
            AgentHubUiComponents::displayName,
            AgentHubUiComponents::faviconFor,
            dialogTitle = "Share Skill",
            okButtonText = if (review) "Review Plan" else "Apply",
            initiallyChecked = shared.mapTo(mutableSetOf()) { it.agentId },
            // Unchecking everything is a valid request only when something is shared to begin with.
            allowEmpty = shared.isNotEmpty(),
            hint = when {
                shared.isEmpty() -> "Pick the agents to share this skill with.$tail"
                locked.isEmpty() -> "Checked agents already have this skill. Unchecking one stops sharing with it. " +
                    tail.trim()
                else -> "Checked agents already have this skill. Unchecking one stops sharing with it; greyed-out ones " +
                    "are shared in a way AgentHub can't remove, so they stay.$tail"
            },
            requireChange = true,
            lockedIds = locked.mapTo(mutableSetOf()) { it.agentId },
            groupTitle = groupTitle,
            groupedIds = groupedIds,
        ).takeIf { it.showAndGet() }?.selectedTargetIds
    },
    /**
     * The "Share…" checklist for a skill that is not in the shared folder yet: [locked] agents (the
     * source, and those reading the shared folder directly) come pre-checked and unchangeable, the
     * notes say why. Returns the full checked set (locked included), or null if cancelled.
     */
    private val choosePromoteTargets: (
        targets: List<String>,
        locked: Set<String>,
        notes: Map<String, String>,
        hint: String,
        groupTitle: String,
        groupedIds: Set<String>,
        warningHtml: String?,
    ) -> Set<String>? =
        { targets, locked, notes, hint, groupTitle, groupedIds, warningHtml ->
            SkillTargetMultiSelectDialog(
                project,
                targets,
                AgentHubUiComponents::displayName,
                AgentHubUiComponents::faviconFor,
                dialogTitle = "Share Skill",
                okButtonText = if (reviewPlan()) "Review Plan" else "Apply",
                initiallyChecked = locked,
                allowEmpty = true,
                hint = hint,
                lockedIds = locked,
                lockedNotes = notes,
                groupTitle = groupTitle,
                groupedIds = groupedIds,
                warningHtml = warningHtml,
            ).takeIf { it.showAndGet() }?.selectedTargetIds
        },
    private val confirm: (PreparedSkillSync) -> Boolean = { prepared ->
        SkillSyncPreviewDialog(project, SkillSyncPreviewModel.from(prepared, AgentHubUiComponents::displayName)).showAndGet()
    },
    private val chooseBackup: (List<StoredBackupRecord>) -> StoredBackupRecord? = { records ->
        SkillRestoreBackupDialog(project, records, AgentHubUiComponents::displayName, AgentHubUiComponents::faviconFor)
            .takeIf { it.showAndGet() }?.selectedRecord
    },
    /** Compares the opened skill with another version and returns what to do about it; null = cancelled or just looked. */
    private val chooseVersionAction: (skillTitle: String, sides: VersionSides) -> VersionChoice? = { skillTitle, sides ->
        SkillVersionDialog(project, skillTitle, sides, AgentHubUiComponents::displayName, reviewPlan()).takeIf { it.showAndGet() }?.result
    },
    private val confirmUndo: (UndoPreview) -> Boolean = { preview ->
        SkillUndoPreviewDialog(project, preview, AgentHubUiComponents::displayName).showAndGet()
    },
    /** The by-directory plan of a bulk operation; shown after the selection when plan review is on. */
    private val confirmBulkPlan: (SkillBulkPlanModel) -> Boolean = { plan -> SkillBulkPlanDialog(project, plan).showAndGet() },
    private val chooseBulkCandidates: (List<BulkMigrationCandidate>) -> List<BulkMigrationCandidate>? = { candidates ->
        SkillBulkMigrationDialog(project, candidates, reviewPlan()).takeIf { it.showAndGet() }?.selectedCandidates
    },
    private val chooseRedundantCopies: (List<RedundantCopyWork>) -> List<RedundantCopyWork>? = { work ->
        SkillRedundantCopyDialog(project, work, reviewPlan()).takeIf { it.showAndGet() }?.selectedWork
    },
) : AutoCloseable {
    private val busy = AtomicBoolean()
    @Volatile private var closed = false

    /** The most recent multi-target share result per skill instance, so Retry Failed Targets knows what to re-plan. */
    private val lastShareEverywhereResults = ConcurrentHashMap<String, SkillSyncResult>()

    /** Agents the last "Share with…" stopped sharing with: a failure there is not a share to retry. */
    private val lastStoppedAgents = ConcurrentHashMap<String, Set<String>>()
    private val failedBulkTargets = ConcurrentHashMap<String, Set<String>>()
    private val cancelBulk = AtomicBoolean()

    /**
     * The "Share with…" checklist. It opens with the agents that actually have this skill from the
     * shared source right now — read from the disk, the same view as the Agents tab, not from
     * AgentHub's ownership records (links and copies made earlier or by hand have none) — and what
     * the user changes is what happens: newly checked agents are shared with, and a shared agent
     * that gets unchecked has its sharing stopped, both in one reviewed operation. Agents whose
     * sharing AgentHub can't remove (not managed, or needing repair) are shown checked and locked.
     */
    private fun status(message: String, sticky: Boolean = false) = statusSink(message, sticky)

    /** A failure or warning: stays visible until the next operation. */
    private fun problem(message: String) = status(message, sticky = true)

    fun share(row: SkillOccurrenceRow) {
        if (closed || !busy.compareAndSet(false, true)) return
        val targets = service.shareTargetIds()
        if (targets.isEmpty()) {
            busy.set(false)
            status("No supported installed agents are available")
            return
        }
        status("Checking how this skill is shared…")
        submit {
            val observed = runCatching { service.targetStatuses(row.skill, row.context.scope, row.context.project) }
                .getOrDefault(emptyList())
            val managed = runCatching { service.managedTargetIds(row.skill, row.context.scope, row.context.project) }
                .getOrDefault(emptySet())
            val manageExisting = runCatching { service.currentSettings().manageExistingTargets }.getOrDefault(false)
            deliver {
                // Reset before prepare() re-acquires it - both run consecutively on the EDT.
                busy.set(false)
                if (closed) return@deliver
                val options = ShareOptions.of(targets, observed, managed, manageExisting)
                val currentlyShared = options.filter { it.shared }.mapTo(mutableSetOf()) { it.agentId }
                val selected = chooseShareTargets(options, sharedDirectoryTitle(row.context.scope), sharedDirectoryAgents(targets))
                if (selected == null) {
                    status("Share cancelled; no files changed")
                    return@deliver
                }
                val change = ShareChange.of(currentlyShared, selected)
                if (change.isEmpty) {
                    status("Sharing is unchanged. Use Resync / Repair on an agent to refresh it.")
                    return@deliver
                }
                prepare(
                    create = { service.prepareUpdateSharing(row.skill, change.share, change.stop, row.context.scope, row.context.project) },
                    onResult = { result ->
                        lastShareEverywhereResults[resultKey(row)] = result
                        lastStoppedAgents[resultKey(row)] = change.stop
                    },
                )
            }
        }
    }

    /** Failed *share* targets from the most recent multi-target share for this skill instance, if any. */
    fun failedTargetIds(row: SkillOccurrenceRow): Set<String> {
        val stopped = lastStoppedAgents[resultKey(row)].orEmpty()
        return lastShareEverywhereResults[resultKey(row)]?.targetResults.orEmpty()
            .filter { it.outcome == SyncTargetOutcome.FAILED && it.agentId !in stopped }
            .mapTo(mutableSetOf()) { it.agentId }
    }

    /** Re-discovers and re-plans, scoped to only the agents that failed the last share. */
    fun retryFailedTargets(row: SkillOccurrenceRow) {
        if (closed || busy.get()) return
        val failed = failedTargetIds(row)
        if (failed.isEmpty()) return
        prepare(
            create = { service.prepareRetryFailedTargets(row.skill, failed, row.context.scope, row.context.project) },
            onResult = { result ->
                lastShareEverywhereResults[resultKey(row)] = result
                lastStoppedAgents.remove(resultKey(row))
            },
        )
    }

    /** The collection heading for agents that read the shared skills directory directly. */
    private fun sharedDirectoryTitle(scope: SkillScope) =
        if (scope == SkillScope.GLOBAL) "Uses the global shared directory" else "Uses the project shared directory"

    private fun sharedDirectoryAgents(targets: List<String>): Set<String> =
        AgentCapabilityRegistry.agentIdsSupportingSharedSkills().filterTo(mutableSetOf()) { it in targets }

    private fun resultKey(row: SkillOccurrenceRow) = "${row.context.key}:${row.skill.identity.id}"

    fun promote(row: SkillOccurrenceRow) {
        val sourcePath = runCatching { Path.of(row.source.path) }.getOrNull() ?: return
        // Discovery can list one folder under every agent that scans it (e.g. ~/.claude/skills also
        // under Cursor); the promotion must run as the agent that owns the directory.
        val sameFolder = row.skill.sources.filter { it.path == row.source.path }.mapNotNull { it.agentId }.distinct()
        val sourceAgentId = service.owningAgentId(sourcePath, sameFolder, row.context.scope, row.context.project)
            ?: row.source.agentId ?: return
        promoteFrom(row, sourceAgentId, sourcePath, askAlsoShare = true)
    }

    /**
     * The one comparison flow: shows the opened skill against another version with the real diff
     * ([SkillVersionDialog]) and acts on the answer through the normal prepare/preview/confirm
     * pipeline - resolving an agent copy against the shared source, or promoting the chosen copy.
     * The comparison itself only reads; a copy that couldn't be compared safely can't be resolved.
     */
    fun resolveVersions(row: SkillOccurrenceRow, sides: VersionSides) {
        if (closed || busy.get()) return
        val choice = chooseVersionAction(row.title, sides) ?: return
        val promote = choice.promote
        val replaceSource = choice.replaceSource
        val replaceTarget = choice.replaceTarget
        when {
            replaceSource != null && replaceTarget?.agentId != null -> prepare {
                service.prepareReplaceCopy(
                    row.skill,
                    replaceSource.path,
                    replaceTarget.agentId,
                    replaceTarget.path,
                    row.context.scope,
                    row.context.project,
                )
            }
            promote != null -> {
                val agentId = promote.agentId ?: return
                promoteFrom(row, agentId, promote.path, askAlsoShare = false)
            }
            choice.resolution != null && choice.agentId != null -> prepare {
                service.prepareResolveConflict(
                    row.skill,
                    choice.agentId,
                    choice.resolution,
                    row.context.scope,
                    row.context.project,
                    choice.newDirectoryName,
                )
            }
        }
    }

    private fun samePath(path: String, other: Path): Boolean = runCatching {
        Path.of(path).toAbsolutePath().normalize() == other.toAbsolutePath().normalize()
    }.getOrDefault(false)

    private fun promoteFrom(row: SkillOccurrenceRow, sourceAgentId: String, sourcePath: Path, askAlsoShare: Boolean) {
        if (closed || busy.get()) return
        if (sourceAgentId !in service.adapterTargetIds()) {
            status("Sync adapter is not available for this agent")
            return
        }
        // Skip the extra dialog when there's nothing else installed to offer sharing with.
        val otherTargets = service.shareTargetIds().filter { it != sourceAgentId }
        // A vendor-provided copy may be promoted, but the user is told what that means before reviewing the plan.
        val systemSource = row.skill.sources.any { it.system && samePath(it.path, sourcePath) }
        val alsoShareWith = if (!askAlsoShare || (otherTargets.isEmpty() && !systemSource)) emptySet() else {
            val sourceName = AgentHubUiComponents.displayName(sourceAgentId)
            // Agents that read the shared folder directly need nothing created: shown checked and locked.
            val direct = AgentCapabilityRegistry.agentIdsSupportingSharedSkills().filter { it in otherTargets }
            val notes = direct.associateWith { "reads the shared folder directly" } + (sourceAgentId to "current location")
            val hint = "This skill moves into the shared skills folder; the copy in $sourceName is replaced by a link or " +
                "copy of it (backed up first). Agents that read that folder directly are already covered. " +
                "Pick more agents to share it with." + if (reviewPlan()) " You review the plan before anything changes." else ""
            val selected = choosePromoteTargets(
                listOf(sourceAgentId) + otherTargets,
                notes.keys,
                notes,
                hint,
                sharedDirectoryTitle(row.context.scope),
                direct.toSet() - sourceAgentId,
                if (systemSource) systemSkillWarningHtml(sourceName, JBUI.scale(SYSTEM_WARNING_WIDTH)) else null,
            ) ?: return
            selected - notes.keys
        }
        val skillId = row.skill.identity.id
        // The promoted occurrence itself keeps its old (non-shared) path and key; the row that now
        // reflects the "Shared" state is the freshly created canonical source, so select that one
        // once the post-promote refresh arrives instead of staying on the now-stale selection.
        prepare(selectAfterRefresh = { it.skill.identity.id == skillId && it.source.shared }) {
            service.preparePromote(row.skill, sourceAgentId, sourcePath, row.context.scope, row.context.project, alsoShareWith)
        }
    }

    /**
     * Refreshes a stale managed copy or recreates a broken managed link for one agent. This also
     * covers what a separate "Repair" action would do — [SkillSyncApplicationService.prepareRepair]
     * and [SkillSyncApplicationService.prepareResync] route through the exact same planning logic
     * for a single target agent, differing only in the audit/plan label, so one button suffices.
     */
    fun resync(row: SkillOccurrenceRow, targetAgentId: String) {
        if (closed || busy.get()) return
        prepare { service.prepareResync(row.skill, targetAgentId, row.context.scope, row.context.project) }
    }

    fun startSharing(row: SkillOccurrenceRow, targetAgentId: String) {
        if (closed || busy.get()) return
        prepare { service.prepareShare(row.skill, targetAgentId, row.context.scope, row.context.project) }
    }

    fun stopSharing(row: SkillOccurrenceRow, targetAgentId: String) {
        if (closed || busy.get()) return
        prepare { service.prepareStopSharing(row.skill, targetAgentId, row.context.scope, row.context.project) }
    }

    /** Removes one agent's own link/copy of a shared skill the agent reads directly anyway (reviewed, backed up first). */
    fun removeRedundantCopy(row: SkillOccurrenceRow, targetAgentId: String) {
        if (closed || busy.get()) return
        prepare { service.prepareRemoveRedundantCopy(row.skill, targetAgentId, row.context.scope, row.context.project) }
    }

    /** Repairs every agent currently managed by AgentHub for this skill, in one plan. */
    fun repairAll(row: SkillOccurrenceRow) {
        if (closed || busy.get()) return
        prepare { service.prepareWholeSkillRepair(row.skill, row.context.scope, row.context.project) }
    }

    /** Reviews and migrates duplicates one skill at a time; cancellation is checked between skills. */
    fun migrateDuplicates(skills: List<AgentSkill>, context: SkillBrowserContext) {
        if (closed || busy.get()) return
        val candidates = bulkDetector(context.project).detect(skills)
        if (candidates.isEmpty()) {
            status("No identical duplicate skills are ready to migrate")
            return
        }
        val selected = chooseBulkCandidates(candidates) ?: return
        if (selected.isEmpty()) return
        if (reviewPlan()) {
            val plan = SkillBulkPlanModel.migration(
                selected,
                service.sharedSkillsDirectory(context.scope, context.project),
                service.backupDirectory(),
                AgentHubUiComponents::displayName,
            )
            if (!confirmBulkPlan(plan)) {
                status("Migration cancelled; no files changed")
                return
            }
        }
        if (!busy.compareAndSet(false, true)) return
        cancelBulk.set(false)
        bulkStateChanged(true)
        status("Migrating ${selected.size} duplicate skills…")
        submit {
            val outcomes = mutableListOf<BulkMigrationOutcome>()
            val unexpectedFailure = runCatching {
                selected.forEachIndexed { index, candidate ->
                    if (cancelBulk.get() || closed) return@forEachIndexed
                    deliver { if (!closed) status("Migrating ${index + 1} of ${selected.size}: ${candidate.skill.name}") }
                    val candidateOutcomes = runCatching {
                        service.migrateBulkCandidates(listOf(candidate), context.scope, context.project)
                    }.getOrElse { error ->
                        listOf(
                            BulkMigrationOutcome(
                                candidate.skill.identity.id,
                                candidate.skill.name,
                                promoteSucceeded = false,
                                promoteMessage = UserFacingError.describe("Could not migrate the skill", error),
                                shareResults = emptyList(),
                            ),
                        )
                    }
                    outcomes += candidateOutcomes
                }
            }.exceptionOrNull()
            deliver {
                busy.set(false)
                bulkStateChanged(false)
                if (closed) return@deliver
                val failures = outcomes.count { outcome ->
                    !outcome.promoteSucceeded || outcome.promoteMessage != null || outcome.shareResults.any { !it.succeeded }
                }
                outcomes.forEach { outcome ->
                    val failedAgents = outcome.shareResults.filterNot { it.succeeded }.mapTo(mutableSetOf()) { it.agentId }
                    if (failedAgents.isEmpty()) failedBulkTargets.remove(outcome.skillId)
                    else failedBulkTargets[outcome.skillId] = failedAgents
                }
                val message = when {
                    unexpectedFailure != null -> UserFacingError.describe("Bulk migration stopped", unexpectedFailure)
                    cancelBulk.get() -> "Bulk migration cancelled after the current skill; ${outcomes.size} completed"
                    failures == 0 -> "Bulk migration completed for ${outcomes.size} skills"
                    else -> "Bulk migration completed with $failures failed or partial skills; refresh and retry them"
                }
                status(message, sticky = unexpectedFailure != null || cancelBulk.get() || failures > 0)
                notify(
                    "Skill migration",
                    message,
                    if (failures == 0 && !cancelBulk.get() && unexpectedFailure == null) {
                        NotificationType.INFORMATION
                    } else {
                        NotificationType.WARNING
                    },
                )
                refresh(null)
            }
        }
    }

    private fun owning(project: DiscoveredProject?, strict: Boolean) = duplicateOwner(
        { path, candidates, scope, proj ->
            runCatching { service.owningAgentId(Path.of(path), candidates, scope, proj, exactPrimaryOnly = strict) }.getOrNull()
        },
        project,
        strict,
    )

    /** The duplicates the "Migrate duplicates" button would list: identical copies in installed agents, none shared yet. */
    private fun bulkDetector(project: DiscoveredProject?) = BulkMigrationDetector(service::isInstalled, owning(project, strict = false))

    fun migratableCount(skills: List<AgentSkill>, context: SkillBrowserContext): Int =
        bulkDetector(context.project).detect(skills).size

    /** What "Clean up redundant copies" would list: shared skills that an installed agent reading the shared folder also keeps its own copy of. */
    fun redundantWork(groups: List<SkillGroup>): List<RedundantCopyWork> = groups.flatMap { group ->
        RedundantCopyDetector(
            service::isInstalled,
            owning(group.context.project, strict = true),
            isLink = ::isLinkEntry,
            canLink = service::canReplaceCopyWithLink,
        )
            .detect(group.skills)
            .map { RedundantCopyWork(it, group.context) }
    }

    fun redundantCopyCount(groups: List<SkillGroup>): Int = redundantWork(groups).size

    /** Reviews and removes the redundant per-agent copies of shared skills, one skill at a time; every removal is backed up. */
    fun cleanUpRedundantCopies(groups: List<SkillGroup>) {
        if (closed || busy.get()) return
        val work = redundantWork(groups)
        if (work.isEmpty()) {
            status("No redundant copies of shared skills were found")
            return
        }
        val selected = chooseRedundantCopies(work) ?: return
        if (selected.isEmpty()) return
        if (reviewPlan() && !confirmBulkPlan(SkillBulkPlanModel.cleanup(selected, service.backupDirectory()))) {
            status("Clean-up cancelled; no files changed")
            return
        }
        if (!busy.compareAndSet(false, true)) return
        cancelBulk.set(false)
        bulkStateChanged(true)
        status("Cleaning up ${selected.size} skills…")
        submit {
            val outcomes = mutableListOf<RedundantCopyOutcome>()
            val unexpectedFailure = runCatching {
                selected.forEachIndexed { index, item ->
                    if (cancelBulk.get() || closed) return@forEachIndexed
                    val skill = item.candidate.skill
                    deliver { if (!closed) status("Cleaning up ${index + 1} of ${selected.size}: ${skill.name}") }
                    outcomes += runCatching {
                        service.removeRedundantCopies(item.candidate, item.context.scope, item.context.project)
                    }.getOrElse { error ->
                        RedundantCopyOutcome(
                            skill.identity.id,
                            skill.name,
                            emptyList(),
                            emptyMap(),
                            (item.candidate.agentIds + item.candidate.convertAgentIds).associateWith { UserFacingError.describe("Could not clean up the skill", error) },
                        )
                    }
                }
            }.exceptionOrNull()
            deliver {
                busy.set(false)
                bulkStateChanged(false)
                if (closed) return@deliver
                val removedCopies = outcomes.sumOf { it.removed.size + it.converted.size }
                val touchedSkills = outcomes.count { it.removed.isNotEmpty() || it.converted.isNotEmpty() }
                val failures = outcomes.count { it.failed.isNotEmpty() }
                val message = when {
                    unexpectedFailure != null -> UserFacingError.describe("Clean-up stopped", unexpectedFailure)
                    cancelBulk.get() -> "Clean-up cancelled after the current skill; $removedCopies redundant copies removed"
                    failures == 0 -> "Cleaned up $removedCopies redundant copies in $touchedSkills skills (backed up first)"
                    else -> "Cleaned up $removedCopies redundant copies; $failures skills had failures — refresh and try again"
                }
                status(message, sticky = unexpectedFailure != null || cancelBulk.get() || failures > 0)
                notify(
                    "Skill clean-up",
                    message,
                    if (failures == 0 && !cancelBulk.get() && unexpectedFailure == null) NotificationType.INFORMATION else NotificationType.WARNING,
                )
                refresh(null)
            }
        }
    }

    /** Requests a safe stop after the candidate currently being migrated. */
    fun cancelBulkMigration() {
        if (busy.get()) {
            cancelBulk.set(true)
            status("Bulk migration will stop after the current skill")
        }
    }

    fun failedBulkMigrationCount(): Int = failedBulkTargets.values.sumOf { it.size }

    /** Rebuilds a normal preview for the next failed bulk-share target set from fresh discovery. */
    fun retryBulkFailures(skills: List<AgentSkill>, context: SkillBrowserContext) {
        if (closed || busy.get()) return
        val retry = failedBulkTargets.entries.firstNotNullOfOrNull { (skillId, agents) ->
            skills.firstOrNull { it.identity.id == skillId && it.sources.any { source -> source.shared } }?.let { it to agents }
        } ?: run {
            status("Refresh the Skills view before retrying failed migrations")
            return
        }
        val (skill, agents) = retry
        prepare(
            create = { service.prepareShareToSelected(skill, agents, context.scope, context.project) },
            onResult = { result ->
                val stillFailed = result.targetResults.filter { it.outcome == SyncTargetOutcome.FAILED }
                    .mapTo(mutableSetOf()) { it.agentId }
                if (stillFailed.isEmpty()) failedBulkTargets.remove(skill.identity.id)
                else failedBulkTargets[skill.identity.id] = stillFailed
            },
        )
    }

    /**
     * Restores one on-disk backup, independent of any in-memory result — unlike Undo, this works
     * even after an IDE restart. Whatever currently sits at the target is backed up first.
     */
    fun restoreBackup(row: SkillOccurrenceRow) {
        if (closed || !busy.compareAndSet(false, true)) return
        status("Looking up available backups…")
        submit {
            val records = runCatching { service.restorableBackups(row.skill, row.context.scope, row.context.project) }
            deliver {
                if (closed) {
                    busy.set(false)
                    return@deliver
                }
                records.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) {
                            busy.set(false)
                            status("No backups are available for this skill")
                            return@fold
                        }
                        val selected = chooseBackup(list)
                        if (selected == null) {
                            busy.set(false)
                            status("Restore cancelled; no files changed")
                        } else {
                            executeRestore(selected)
                        }
                    },
                    onFailure = { error ->
                        busy.set(false)
                        problem(UserFacingError.describe("Could not look up backups", error))
                    },
                )
            }
        }
    }

    /** Reverses a past operation purely from disk — see [SkillSyncApplicationService.undoOperation]. */
    fun undoOperation(operationId: String) {
        if (closed || !busy.compareAndSet(false, true)) return
        status("Preparing undo preview…")
        submit {
            val preview = runCatching { service.previewUndoOperation(operationId) }
            deliver {
                if (closed) { busy.set(false); return@deliver }
                preview.fold(
                    onSuccess = { prepared ->
                        if (prepared == null) {
                            busy.set(false)
                            status("Nothing to undo for this operation")
                        } else if (reviewPlan() && !confirmUndo(prepared)) {
                            busy.set(false)
                            status("Undo cancelled; no files changed")
                        } else {
                            executeUndo(operationId)
                        }
                    },
                    onFailure = { error -> busy.set(false); problem(UserFacingError.describe("Could not prepare undo", error)) },
                )
            }
        }
    }

    private fun executeUndo(operationId: String) {
        status("Undoing operation…")
        submit {
            val result = runCatching { service.undoOperation(operationId) }
            deliver {
                busy.set(false)
                if (closed) return@deliver
                result.fold(
                    onSuccess = { undone ->
                        val succeeded = undone?.errors?.isEmpty() == true
                        if (succeeded) status("Operation undone") else problem("Undo did not complete; review synchronization history")
                        notify(
                            "Skill synchronization",
                            if (succeeded) "The selected synchronization operation was undone." else "Undo could not be completed safely.",
                            if (succeeded) NotificationType.INFORMATION else NotificationType.WARNING,
                        )
                        refresh(null)
                    },
                    onFailure = { error -> problem(UserFacingError.describe("Undo failed", error)) },
                )
            }
        }
    }

    private fun executeRestore(record: StoredBackupRecord) {
        status("Restoring backup…")
        submit {
            val result = runCatching { service.executeRestoreBackup(record) }
            deliver {
                busy.set(false)
                if (closed) return@deliver
                result.fold(
                    onSuccess = { completed ->
                        val succeeded = completed.status == SyncOperationStatus.SUCCESS
                        if (succeeded) status("Backup restored") else problem("Restore did not complete; review synchronization history")
                        notify(
                            "Skill backup",
                            if (succeeded) "The selected backup was restored." else "The backup could not be restored safely.",
                            if (succeeded) NotificationType.INFORMATION else NotificationType.ERROR,
                        )
                        refresh(null)
                    },
                    onFailure = { error -> problem(UserFacingError.describe("Restore failed", error)) },
                )
            }
        }
    }

    private fun prepare(
        onResult: (SkillSyncResult) -> Unit = {},
        selectAfterRefresh: ((SkillOccurrenceRow) -> Boolean)? = null,
        create: () -> PreparedSkillSync,
    ) {
        if (closed || !busy.compareAndSet(false, true)) return
        status("Preparing synchronization preview…")
        submit {
            val prepared = runCatching(create)
            deliver {
                if (closed) {
                    busy.set(false)
                    return@deliver
                }
                prepared.fold(
                    onSuccess = { plan ->
                        if (!reviewPlan()) {
                            // No preview step: apply the plan as computed, unless it has nothing to do.
                            val preview = SkillSyncPreviewModel.from(plan, AgentHubUiComponents::displayName)
                            if (preview.canApply) {
                                execute(plan, onResult, selectAfterRefresh)
                            } else {
                                busy.set(false)
                                status(preview.warnings.firstOrNull() ?: "Nothing to change; no files changed")
                            }
                        } else if (confirm(plan)) {
                            execute(plan, onResult, selectAfterRefresh)
                        } else {
                            busy.set(false)
                            status("Synchronization cancelled; no files changed")
                        }
                    },
                    onFailure = { error ->
                        busy.set(false)
                        problem(UserFacingError.describe("Could not prepare synchronization", error))
                    },
                )
            }
        }
    }

    private fun execute(
        prepared: PreparedSkillSync,
        onResult: (SkillSyncResult) -> Unit = {},
        selectAfterRefresh: ((SkillOccurrenceRow) -> Boolean)? = null,
    ) {
        status("Applying reviewed synchronization plan…")
        submit {
            val result = runCatching { service.execute(prepared) }
            deliver {
                busy.set(false)
                if (closed) return@deliver
                result.fold(
                    onSuccess = { completed ->
                        onResult(completed)
                        status(
                            when {
                                completed.recoveryRequired -> "Synchronization failed · Recovery required"
                                completed.status == SyncOperationStatus.SUCCESS -> "Synchronization completed"
                                completed.status == SyncOperationStatus.PARTIAL_SUCCESS -> "Synchronization partially completed"
                                else -> "Synchronization failed; no reviewed change completed"
                            },
                            sticky = completed.recoveryRequired || completed.status != SyncOperationStatus.SUCCESS,
                        )
                        val projectNotice = if (prepared.scope == SkillScope.PROJECT &&
                            completed.status != SyncOperationStatus.FAILED
                        ) " Review the project’s Git changes before committing." else ""
                        val (message, type) = when {
                            completed.recoveryRequired -> "Synchronization failed and manual recovery may be required." to NotificationType.ERROR
                            completed.status == SyncOperationStatus.SUCCESS -> "Synchronization completed.$projectNotice" to NotificationType.INFORMATION
                            completed.status == SyncOperationStatus.PARTIAL_SUCCESS -> "Synchronization completed only for some targets.$projectNotice" to NotificationType.WARNING
                            else -> "Synchronization failed; no reviewed change completed." to NotificationType.ERROR
                        }
                        notify("Skill synchronization", message, type)
                        refresh(selectAfterRefresh)
                    },
                    onFailure = { error -> problem(UserFacingError.describe("Synchronization failed", error)) },
                )
            }
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            busy.set(false)
            status("Synchronization is unavailable")
        }
    }

    override fun close() {
        closed = true
        cancelBulk.set(true)
    }
}

/**
 * What a "Share with…" checklist answer changes: agents newly checked are shared with, agents that
 * were shared and got unchecked are stopped. Agents left as they were (still shared, or never
 * shared and still unchecked) are not touched — healing a drifted copy is Resync / Repair's job.
 */
internal data class ShareChange(val share: Set<String>, val stop: Set<String>) {
    val isEmpty: Boolean get() = share.isEmpty() && stop.isEmpty()

    companion object {
        fun of(currentlyShared: Set<String>, selected: Set<String>) =
            ShareChange(share = selected - currentlyShared, stop = currentlyShared - selected)
    }
}

/** One row of the "Share with…" checklist: does the agent have the shared skill, and can AgentHub take that away again? */
internal data class ShareOption(val agentId: String, val shared: Boolean, val removable: Boolean)

internal object ShareOptions {
    /**
     * Statuses in which the agent already has the shared skill: a link to it, a managed copy, an
     * identical copy, or - [SkillTargetStatus.NATIVE] - the agent reads the shared source directly,
     * so nothing was ever created for it (and per [REMOVABLE_STATUSES] below, never can be removed).
     */
    private val SHARED_STATUSES = setOf(
        SkillTargetStatus.LINKED,
        SkillTargetStatus.COPIED,
        SkillTargetStatus.IDENTICAL_UNMANAGED,
        SkillTargetStatus.NATIVE,
    )

    /** Only a healthy link or copy that AgentHub verifiably manages can be removed by "Stop sharing". */
    private val REMOVABLE_STATUSES = setOf(SkillTargetStatus.LINKED, SkillTargetStatus.COPIED)

    /** With "manage existing skills" on, a link to the shared source or an identical copy AgentHub didn't create can go too. */
    private val EXISTING_REMOVABLE_STATUSES = setOf(SkillTargetStatus.LINKED, SkillTargetStatus.IDENTICAL_UNMANAGED)

    /**
     * Built from the *observed* disk state, so a link or copy made by an earlier session or by hand
     * still shows as shared; [managedIds] only adds agents AgentHub has a record for but that no
     * longer look shared (a broken or diverged managed target). Such agents and unmanaged ones are
     * shared but not [ShareOption.removable]: the planner refuses to delete what AgentHub doesn't own
     * — unless [manageExisting] is on, which makes an unmanaged link or identical copy removable too.
     */
    fun of(
        targets: List<String>,
        observed: List<ObservedSkillTarget>,
        managedIds: Set<String>,
        manageExisting: Boolean = false,
    ): List<ShareOption> {
        val byAgent = observed.associateBy { it.agentId }
        return targets.map { agentId ->
            val target = byAgent[agentId]
            ShareOption(
                agentId = agentId,
                shared = (target != null && target.status in SHARED_STATUSES) || agentId in managedIds,
                removable = target != null &&
                    (
                        (target.ownershipVerified && target.status in REMOVABLE_STATUSES) ||
                            (manageExisting && !target.ownershipVerified && target.status in EXISTING_REMOVABLE_STATUSES)
                        ),
            )
        }
    }
}

/** Wrapping width of the vendor-provided warning inside the promote dialog (unscaled px). */
private const val SYSTEM_WARNING_WIDTH = 340

/** The skills of one concrete context (global, or one project): an "all projects" view is several of these. */
internal data class SkillGroup(val skills: List<AgentSkill>, val context: SkillBrowserContext)

/** One shared skill with redundant per-agent copies, together with the context its sync has to run in. */
internal data class RedundantCopyWork(val candidate: RedundantCopyCandidate, val context: SkillBrowserContext)

/** True for a symlink or a Windows junction (the JDK reports a junction as "other", not as a symbolic link). */
private fun isLinkEntry(source: SkillSource): Boolean = runCatching {
    val path = Path.of(source.path)
    Files.isSymbolicLink(path) ||
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
}.getOrDefault(false)
