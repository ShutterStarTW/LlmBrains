package com.shutterstar.agenthub.environment.skills.sync

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.AgentSettingsState
import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupSweeper
import com.shutterstar.agenthub.environment.skills.sync.execution.StoredBackupRecord
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationCandidate
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationOrchestrator
import com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyCandidate
import com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyOutcome
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationOutcome
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoResult
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoPreview
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.model.ConflictResolution
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncPlan
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTargetResult
import com.shutterstar.agenthub.environment.skills.sync.model.SyncError
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncTargetOutcome
import com.shutterstar.agenthub.environment.skills.sync.model.SyncWarning
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateService
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import com.shutterstar.agenthub.environment.skills.sync.target.AntigravitySkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.ClaudeSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.ClineSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.CodexSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.CopilotSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.CursorSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.GrokSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.KiloSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.JunieSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.KimiSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.MimoSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.VibeSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.KiroSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.OmpSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.OpenCodeSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.QwenSkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import java.util.UUID
import com.shutterstar.agenthub.storage.AgentHubHome
import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.AgentRuntime

internal data class PreparedSkillSync(
    val planResult: SkillSyncPlanResult,
    val scope: SkillScope,
    val project: DiscoveredProject?,
    val backupRoot: Path,
    /** Non-empty only for a [SkillSyncRequest.PromoteSkill] — see [SkillSyncApplicationService.execute]. */
    val alsoShareWith: Set<String> = emptySet(),
)

/** Application-scoped composition root and serialization boundary for skill mutations. */
@Service(Service.Level.APP)
internal class SkillSyncApplicationService(
    /** Null: the targets of the current runtime (the host, or the WSL distro's home in WSL mode). */
    targets: Map<String, SkillSyncTarget>? = null,
    private val backupRoot: Path = AgentHubStorage.home()?.backups()
        ?: Path.of(PathManager.getSystemPath()).resolve("agenthub/skill-backups"),
    private val ownershipStore: SkillOwnershipStateService = SkillOwnershipStateService.getInstance(),
    private val auditTrail: SkillSyncAuditStateService = SkillSyncAuditStateService.getInstance(),
    private val settings: SkillSyncSettingsStateService = SkillSyncSettingsStateService.getInstance(),
    sharedSkillDirectory: SharedSkillProvider? = null,
    private val operationId: () -> String = { UUID.randomUUID().toString() },
    private val mutationHome: AgentHubHome? = AgentHubStorage.home(),
    /** Null only when the settings service is unavailable; an unknown detection is an empty set, not "all". */
    private val detectedInstalledAgentIds: () -> Set<String>? = {
        runCatching { AgentSettingsState.getInstance().visibleAgentIds() }.getOrNull()
    },
    private val isAgentInstalled: (String) -> Boolean = { agentId ->
        runCatching { AgentSettingsState.getInstance().isAgentVisible(agentId) }.getOrDefault(true)
    },
    private val rediscoverForMigration: (SkillScope, DiscoveredProject?) -> List<AgentSkill> = { scope, project ->
        val discovery = SkillDiscoveryService(isAgentVisible = isAgentInstalled)
        when (scope) {
            SkillScope.GLOBAL -> discovery.discoverGlobal()
            SkillScope.PROJECT -> project?.let(discovery::discoverProject).orEmpty()
        }
    },
) {

    private fun <T> mutate(action: () -> T): T {
        fun checked(): T {
            ownershipStore.checkWritable()
            auditTrail.checkWritable()
            return action()
        }
        return mutationHome?.let { home ->
            if (home.prepare()) home.withLock("skill-mutations") { checked() } else checked()
        } ?: checked()
    }

    /**
     * What depends on where the agents run: the sync targets, the shared skill root and the engine built on it.
     * Rebuilt when the runtime changes; fixed when a caller (a test) injects its own targets or shared root.
     */
    private class RuntimeParts(
        val targets: Map<String, SkillSyncTarget>,
        val sharedSkillDirectory: SharedSkillProvider,
        val engine: SkillSyncEngine,
    )

    private fun buildParts(targets: Map<String, SkillSyncTarget>, shared: SharedSkillProvider) = RuntimeParts(
        targets,
        shared,
        SkillSyncEngine(ownershipStore = ownershipStore, auditTrail = auditTrail, sharedSkillDirectory = shared),
    )

    private val fixedParts: RuntimeParts? =
        if (targets != null || sharedSkillDirectory != null) {
            buildParts(targets ?: defaultTargets(), sharedSkillDirectory ?: SharedSkillProvider())
        } else {
            null
        }
    private val scopedParts = AgentRuntime.scoped { buildParts(defaultTargets(), SharedSkillProvider()) }
    private val parts: RuntimeParts get() = fixedParts ?: scopedParts.get()
    private val targetMap: Map<String, SkillSyncTarget> get() = parts.targets
    private val engine: SkillSyncEngine get() = parts.engine

    private fun planForRuntime(
        request: SkillSyncRequest,
        skill: AgentSkill,
        operationId: String,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        installedAgentIds: Set<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupBeforeReplacement: Boolean,
    ): SkillSyncPlanResult {
        // The UI only offers installed agents, but a stale dialog or a direct call must not
        // start an operation on an agent whose CLI was uninstalled in the meantime.
        val hidden = (explicitTargets(request) + installedAgentIds).filterNot(isAgentInstalled)
        if (hidden.isNotEmpty()) {
            return noOpPlan(request, skill, operationId, scope, notInstalledMessage(hidden))
        }
        return engine.planner.plan(
            request,
            skill,
            operationId,
            targetsByAgentId,
            installedAgentIds,
            scope,
            project,
            backupBeforeReplacement,
            manageExistingTargets = settings.current().manageExistingTargets,
        )
    }

    private fun explicitTargets(request: SkillSyncRequest): Set<String> = when (request) {
        is SkillSyncRequest.PromoteSkill -> request.alsoShareWith
        is SkillSyncRequest.ReplaceCopy -> setOf(request.targetAgentId)
        is SkillSyncRequest.ShareSkill -> setOf(request.targetAgentId)
        is SkillSyncRequest.ShareSkillEverywhere -> emptySet()
        is SkillSyncRequest.UpdateSharing -> request.shareAgentIds
        is SkillSyncRequest.StopSharing -> setOf(request.targetAgentId)
        is SkillSyncRequest.RemoveRedundantCopy -> setOf(request.targetAgentId)
        is SkillSyncRequest.RepairSkill -> setOfNotNull(request.targetAgentId)
        is SkillSyncRequest.ResyncSkill -> setOf(request.targetAgentId)
        is SkillSyncRequest.ResolveConflict -> setOf(request.targetAgentId)
    }

    private fun notInstalledMessage(agentIds: List<String>): String =
        "${agentIds.sorted().joinToString(", ")} is not installed according to the latest detection; " +
            "run Detect installed agents and try again."

    /** A plan with no steps and a single warning — nothing is executed, the preview just explains why. */
    private fun noOpPlan(
        request: SkillSyncRequest,
        skill: AgentSkill,
        operationId: String,
        scope: SkillScope,
        message: String,
    ): SkillSyncPlanResult {
        val referencePath = when (request) {
            is SkillSyncRequest.PromoteSkill -> request.sourcePath
            is SkillSyncRequest.ReplaceCopy -> request.targetPath
            else -> skill.sources.firstOrNull { it.shared }?.path
                ?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: skill.sources.firstOrNull()?.path?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: Path.of(".")
        }
        val action = when (request) {
            is SkillSyncRequest.PromoteSkill -> SyncAction.PROMOTE
            is SkillSyncRequest.ReplaceCopy -> SyncAction.REPLACE_COPY
            is SkillSyncRequest.RemoveRedundantCopy -> SyncAction.REMOVE_REDUNDANT_COPY
            is SkillSyncRequest.ShareSkill -> SyncAction.SHARE
            is SkillSyncRequest.ShareSkillEverywhere -> SyncAction.SHARE_EVERYWHERE
            is SkillSyncRequest.StopSharing -> SyncAction.STOP_SHARING
            is SkillSyncRequest.UpdateSharing -> SyncAction.UPDATE_SHARING
            is SkillSyncRequest.RepairSkill -> SyncAction.REPAIR
            is SkillSyncRequest.ResyncSkill -> SyncAction.RESYNC
            is SkillSyncRequest.ResolveConflict -> SyncAction.RESOLVE_CONFLICT
        }
        val warning = SyncWarning(null, message)
        return SkillSyncPlanResult(
            SkillSyncPlan(operationId, request.skillId, referencePath, emptyList(), listOf(warning)),
            SkillSyncPlanningRequest(operationId, request.skillId, referencePath, null, emptyList()),
            action,
            scope,
        )
    }

    fun adapterTargetIds(): Set<String> = targetMap.keys

    /**
     * Which of [candidateAgentIds] a skill directory at [skillPath] really belongs to. Several
     * agents read each other's folders (Cursor, Grok and OpenCode also scan `~/.claude/skills`), so
     * discovery can report one directory under all of them; only the agent whose own skill root is
     * that directory (or, failing that, one of its compatibility roots) can be promoted from it.
     */
    fun owningAgentId(
        skillPath: Path,
        candidateAgentIds: Collection<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
        /** Only an agent whose own skill directory is exactly the skill's parent counts (no sub-folders, no compatibility roots). */
        exactPrimaryOnly: Boolean = false,
    ): String? {
        val parent = runCatching { skillPath.toAbsolutePath().normalize().parent }.getOrNull() ?: return null
        fun Path?.matches() = this != null && runCatching { toAbsolutePath().normalize() == parent }.getOrDefault(false)
        // A folder below the root (e.g. skills/synced/<id>/name) still belongs to that root's agent.
        fun Path?.covers() = this != null && runCatching { parent.startsWith(toAbsolutePath().normalize()) }.getOrDefault(false)
        val candidates = candidateAgentIds.mapNotNull { id -> targetMap[id]?.let { id to it } }
        fun primary(target: SkillSyncTarget): Path? = when (scope) {
            SkillScope.GLOBAL -> target.globalSkillDirectory()
            SkillScope.PROJECT -> project?.let(target::projectSkillDirectory)
        }
        fun alternates(target: SkillSyncTarget): List<Path> = when (scope) {
            SkillScope.GLOBAL -> target.alternateGlobalSkillDirectories()
            SkillScope.PROJECT -> project?.let(target::alternateProjectSkillDirectories).orEmpty()
        }
        val exact = candidates.firstOrNull { (_, target) -> primary(target).matches() }?.first
        if (exactPrimaryOnly) return exact
        return exact
            ?: candidates.firstOrNull { (_, target) -> primary(target).covers() }?.first
            ?: candidates.firstOrNull { (_, target) -> alternates(target).any { it.matches() || it.covers() } }?.first
    }

    /** Read-only: the persisted defaults a settings UI would show. */
    fun currentSettings(): SkillSyncSettings = settings.current()

    /** Persists new defaults - takes effect on the next prepared request, never retroactively. */
    fun updateSettings(newSettings: SkillSyncSettings) = settings.update(newSettings)

    /**
     * Every agent that supports skills at all, not just the ones with a [SkillSyncTarget] adapter
     * implemented yet — planning already degrades an unsupported target to a "not a supported sync
     * target" warning with zero applicable steps, so listing it is safe and keeps the dropdown
     * honest about which of the 10 mapped agents could receive this skill.
     */
    fun shareTargetIds(): List<String> {
        val capable = AgentCapabilityRegistry.agentIdsSupportingSkills()
        val detected = detectedInstalledAgentIds() ?: return capable.sorted()
        return capable.filter { it in detected }.sorted()
    }

    /**
     * The one place a prepared operation is assembled: every `prepare*` differs only in its request and
     * the agents it touches. Settings are read once, so a single operation never mixes two snapshots.
     */
    private fun prepare(
        request: SkillSyncRequest,
        skill: AgentSkill,
        agentIds: Set<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
        alwaysBackup: Boolean = false,
        alsoShareWith: Set<String> = emptySet(),
    ): PreparedSkillSync = PreparedSkillSync(
        planForRuntime(
            request,
            skill,
            operationId(),
            targetMap,
            agentIds,
            scope,
            project,
            backupBeforeReplacement = alwaysBackup || settings.current().backupBeforeReplacement,
        ),
        scope,
        project,
        backupRoot,
        alsoShareWith = alsoShareWith,
    )

    fun prepareShare(
        skill: AgentSkill,
        targetAgentId: String,
        scope: SkillScope,
        project: DiscoveredProject?,
        alwaysBackup: Boolean = false,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.ShareSkill(skill.identity.id, targetAgentId, settings.current().preferredSyncMode),
        skill,
        setOf(targetAgentId),
        scope,
        project,
        alwaysBackup = alwaysBackup,
    )

    /** Every installed target at once - the "share everywhere" special case of [prepareShareToSelected]. */
    fun prepareShareEverywhere(
        skill: AgentSkill,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepareShareToSelected(skill, shareTargetIds().toSet(), scope, project)

    /**
     * Shares to exactly the given targets in one plan/execute (one operation id, one audit entry
     * covering every agent in [targetAgentIds]) - the primitive behind both the "Share with…"
     * button's multi-select and [prepareShareEverywhere]/[prepareRetryFailedTargets].
     */
    fun prepareShareToSelected(
        skill: AgentSkill,
        targetAgentIds: Set<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.ShareSkillEverywhere(skill.identity.id, settings.current().preferredSyncMode),
        skill,
        targetAgentIds,
        scope,
        project,
    )

    /**
     * The "Share with…" checklist as one plan: shares with [shareAgentIds] and stops sharing with
     * [stopAgentIds] — agents that were shared and got unchecked — in one operation id, one preview,
     * one audit entry and one Undo. Only ever call this with agents that were actually shown in the
     * checklist: stopping is limited to what the caller names, never inferred from ownership.
     */
    fun prepareUpdateSharing(
        skill: AgentSkill,
        shareAgentIds: Set<String>,
        stopAgentIds: Set<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.UpdateSharing(skill.identity.id, shareAgentIds, stopAgentIds, settings.current().preferredSyncMode),
        skill,
        shareAgentIds,
        scope,
        project,
    )

    fun preparePromote(
        skill: AgentSkill,
        sourceAgentId: String,
        sourcePath: Path,
        scope: SkillScope,
        project: DiscoveredProject?,
        alsoShareWith: Set<String> = emptySet(),
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.PromoteSkill(
            skill.identity.id,
            sourceAgentId,
            sourcePath,
            scope,
            alsoShareWith = alsoShareWith,
            mode = settings.current().preferredSyncMode,
        ),
        skill,
        setOf(sourceAgentId),
        scope,
        project,
        alsoShareWith = alsoShareWith,
    )

    /**
     * Re-plans a Share Everywhere scoped to only [failedAgentIds] — [SkillSyncRequestPlanner.plan] always
     * observes live filesystem state, so this is inherently a fresh discovery, not a replay of the
     * stale plan that failed. Only meaningful for a Share Everywhere retry: single-target actions
     * (Share/Resync/Repair/Stop Sharing) already retry by simply invoking that same action again
     * for the one agent that failed.
     */
    fun prepareRetryFailedTargets(
        skill: AgentSkill,
        failedAgentIds: Set<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepareShareToSelected(skill, failedAgentIds, scope, project)

    /**
     * Overwrites one agent's copy with another version's content - the way to settle two differing
     * copies when neither should become (or replace) the shared skill. Always backed up, so undoable.
     */
    fun prepareReplaceCopy(
        skill: AgentSkill,
        sourcePath: Path,
        targetAgentId: String,
        targetPath: Path,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.ReplaceCopy(skill.identity.id, sourcePath, targetAgentId, targetPath, scope),
        skill,
        setOf(targetAgentId),
        scope,
        project,
        alwaysBackup = true,
    )

    /**
     * `KEEP_CANONICAL`/`KEEP_TARGET`/`KEEP_BOTH` (never `CANCEL` — the UI just closes the dialog for
     * that choice without calling this at all). The caller is expected to have already shown the
     * user the conflicting content (e.g. via a diff dialog) before picking a resolution; this only
     * plans/executes the chosen one through the same preview-first pipeline as every other action.
     */
    fun prepareResolveConflict(
        skill: AgentSkill,
        targetAgentId: String,
        resolution: ConflictResolution,
        scope: SkillScope,
        project: DiscoveredProject?,
        newDirectoryName: String? = null,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.ResolveConflict(skill.identity.id, targetAgentId, resolution, newDirectoryName),
        skill,
        setOf(targetAgentId),
        scope,
        project,
    )

    fun prepareStopSharing(
        skill: AgentSkill,
        targetAgentId: String,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.StopSharing(skill.identity.id, targetAgentId),
        skill,
        setOf(targetAgentId),
        scope,
        project,
    )

    /** True when the latest detection says [agentId]'s CLI is installed (the same gate every mutation uses). */
    fun isInstalled(agentId: String): Boolean = isAgentInstalled(agentId)

    /** Removes [targetAgentId]'s own link/copy of a shared skill it reads directly; see [SkillSyncRequest.RemoveRedundantCopy]. */
    fun prepareRemoveRedundantCopy(
        skill: AgentSkill,
        targetAgentId: String,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.RemoveRedundantCopy(skill.identity.id, targetAgentId),
        skill,
        setOf(targetAgentId),
        scope,
        project,
        alwaysBackup = true,
    )

    /** True when an identical copy kept by [agentId] can be swapped for a link: it has a sync adapter that links, and links are the preferred mode. */
    fun canReplaceCopyWithLink(agentId: String): Boolean =
        targetMap[agentId]?.supportsLinkedSkills() == true && settings.current().preferredSyncMode == SkillSyncMode.SYMLINK

    /** Cleans up one candidate, agent by agent, each as its own operation (one failing agent does not stop the rest). */
    fun removeRedundantCopies(candidate: RedundantCopyCandidate, scope: SkillScope, project: DiscoveredProject?): RedundantCopyOutcome {
        val removed = mutableListOf<String>()
        val converted = mutableListOf<String>()
        val skipped = mutableMapOf<String, String>()
        val failed = mutableMapOf<String, String>()
        candidate.convertAgentIds.forEach { agentId ->
            val prepared = runCatching { prepareShare(candidate.skill, agentId, scope, project, alwaysBackup = true) }
                .getOrElse { error -> failed[agentId] = error.message ?: error.javaClass.simpleName; return@forEach }
            val result = runCatching { execute(prepared) }
                .getOrElse { error -> failed[agentId] = error.message ?: error.javaClass.simpleName; return@forEach }
            when {
                result.status == SyncOperationStatus.FAILED || result.status == SyncOperationStatus.ROLLED_BACK ->
                    failed[agentId] = result.errors.joinToString { it.message }.ifBlank { "Could not replace the copy with a link." }
                result.appliedSteps.isEmpty() ->
                    skipped[agentId] = prepared.planResult.plan.warnings.joinToString { it.message }.ifBlank { "Nothing to replace." }
                else -> converted += agentId
            }
        }
        candidate.agentIds.forEach { agentId ->
            val prepared = runCatching { prepareRemoveRedundantCopy(candidate.skill, agentId, scope, project) }
                .getOrElse { error -> failed[agentId] = error.message ?: error.javaClass.simpleName; return@forEach }
            val result = runCatching { execute(prepared) }
                .getOrElse { error -> failed[agentId] = error.message ?: error.javaClass.simpleName; return@forEach }
            when {
                result.status == SyncOperationStatus.FAILED || result.status == SyncOperationStatus.ROLLED_BACK ->
                    failed[agentId] = result.errors.joinToString { it.message }.ifBlank { "Could not remove the copy." }
                result.appliedSteps.isEmpty() ->
                    skipped[agentId] = prepared.planResult.plan.warnings.joinToString { it.message }.ifBlank { "Nothing to remove." }
                else -> removed += agentId
            }
        }
        return RedundantCopyOutcome(candidate.skill.identity.id, candidate.skill.name, removed, skipped, failed, converted)
    }

    fun prepareResync(
        skill: AgentSkill,
        targetAgentId: String,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.ResyncSkill(skill.identity.id, targetAgentId),
        skill,
        setOf(targetAgentId),
        scope,
        project,
    )

    fun prepareRepair(
        skill: AgentSkill,
        targetAgentId: String,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.RepairSkill(skill.identity.id, targetAgentId),
        skill,
        setOf(targetAgentId),
        scope,
        project,
    )

    /** Repairs every agent [SkillSyncOperationRunner.managedTargetIds] currently reports as managed, in one plan. */
    fun prepareWholeSkillRepair(
        skill: AgentSkill,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): PreparedSkillSync = prepare(
        SkillSyncRequest.RepairSkill(skill.identity.id, targetAgentId = null),
        skill,
        targetMap.keys,
        scope,
        project,
    )

    /** UI action-gating hint only — see [SkillSyncOperationRunner.managedTargetIds]. */
    fun managedTargetIds(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?): Set<String> =
        engine.runner.managedTargetIds(skill, scope, project)

    fun historyFor(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?): List<SyncAuditEntry> =
        engine.runner.historyFor(skill, scope, project)

    fun targetStatuses(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?): List<ObservedSkillTarget> {
        val installed = shareTargetIds().toSet()
        return engine.runner.targetStatuses(
            skill,
            scope,
            project,
            targetMap.filterKeys { it in installed },
            settings.current().preferredSyncMode,
            // An installed agent that reads the shared directory but has no sync target (e.g. Freebuff).
            directReaderIds = AgentCapabilityRegistry.agentIdsSupportingSharedSkills().filterTo(mutableSetOf()) { it in installed },
        )
    }

    fun previewUndoOperation(operationId: String): UndoPreview? =
        engine.runner.previewUndoOperation(operationId, backupRoot)

    /** See [SkillSyncOperationRunner.undoOperation] — reverses a past operation purely from disk. */
    @Synchronized
    fun undoOperation(operationId: String): UndoResult? =
        mutate { engine.runner.undoOperation(operationId, backupRoot) }

    /**
     * Executes what [com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationDetector]
     * only proposes: promote then rediscover-then-share per candidate, isolating one skill's
     * failure from the rest. Each individual promote/share still goes through [execute] above, so
     * it gets the exact same backup/ownership/audit/retention behavior as a manual action.
     */
    @Synchronized
    fun migrateBulkCandidates(
        candidates: List<BulkMigrationCandidate>,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): List<BulkMigrationOutcome> = BulkMigrationOrchestrator(this, rediscoverForMigration).migrate(candidates, scope, project)

    /** The shared skills folder for a scope (for plan previews); null for a project scope without a project. */
    fun sharedSkillsDirectory(scope: SkillScope, project: DiscoveredProject?): Path? =
        if (scope == SkillScope.GLOBAL) parts.sharedSkillDirectory.resolveGlobalDirectory()
        else project?.let(parts.sharedSkillDirectory::resolveProjectDirectory)

    /** Where backups made by mutations are kept (for plan previews). */
    fun backupDirectory(): Path = backupRoot

    fun restorableBackups(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?): List<StoredBackupRecord> =
        engine.runner.restorableBackups(skill, scope, project, backupRoot)

    fun skillIdsWithBackups(skills: List<AgentSkill>, scope: SkillScope, project: DiscoveredProject?): Set<String> =
        engine.runner.skillIdsWithBackups(skills, scope, project, backupRoot)

    fun undoAvailability(operationIds: Set<String>): UndoAvailability =
        engine.runner.undoAvailability(operationIds, backupRoot)

    @Synchronized
    fun executeRestoreBackup(record: StoredBackupRecord): SkillSyncResult {
        val result = mutate {
            val restored = engine.runner.restoreBackup(record, backupRoot, operationId())
            runCatching { BackupSweeper.sweep(backupRoot) }
            restored
        }
        return result
    }

    @Synchronized
    fun execute(prepared: PreparedSkillSync): SkillSyncResult {
        val result = mutate {
            val executed = engine.runner.execute(
                prepared.planResult,
                targetMap,
                prepared.scope,
                prepared.project,
                backupRoot,
            )
            // Best-effort: a retention sweep failure must never mask the actual sync result above.
            runCatching { BackupSweeper.sweep(backupRoot) }
            executed
        }
        if (prepared.alsoShareWith.isEmpty() || result.status != SyncOperationStatus.SUCCESS) return result
        return result.mergedWithAlsoShareWith(prepared)
    }

    /**
     * Orderly `PromoteSkill.alsoShareWith` workflow: a share plan for those agents cannot be built
     * up front (the canonical source doesn't exist until the promote above actually runs), so this
     * rediscovers the freshly promoted skill and runs one [prepareShareToSelected]/[execute] for
     * every requested target together (its own operation id, backup and audit entry - a separate,
     * individually undoable history row from the promote). The returned result only folds that
     * share's [SkillSyncResult.targetResults]/[SkillSyncResult.status] into the promote's own for a
     * single combined status line; [SkillSyncResult.appliedSteps]/[SkillSyncResult.operationId]
     * still describe only the promote itself, exactly like
     * [migration.BulkMigrationOrchestrator]'s own promote-then-share.
     */
    private fun SkillSyncResult.mergedWithAlsoShareWith(prepared: PreparedSkillSync): SkillSyncResult {
        val skillId = prepared.planResult.plan.skillId
        val freshSkill = rediscoverForMigration(prepared.scope, prepared.project).firstOrNull { it.identity.id == skillId }
            ?: return copy(
                errors = errors + SyncError(null, "Promoted, but the skill could not be rediscovered to share it further; use Share with… manually."),
            )
        val shareResult = execute(prepareShareToSelected(freshSkill, prepared.alsoShareWith, prepared.scope, prepared.project))
        return copy(
            status = if (shareResult.status == SyncOperationStatus.SUCCESS) status else SyncOperationStatus.PARTIAL_SUCCESS,
            targetResults = targetResults + shareResult.targetResults,
            errors = errors + shareResult.errors,
        )
    }

    companion object {
        fun getInstance(): SkillSyncApplicationService = service()

        private fun defaultTargets(userHome: Path = AgentRuntime.userHome()): Map<String, SkillSyncTarget> =
            listOf(
                AntigravitySkillSyncTarget(userHome),
                ClaudeSkillSyncTarget(userHome),
                ClineSkillSyncTarget(userHome),
                CodexSkillSyncTarget(userHome),
                CopilotSkillSyncTarget(),
                CursorSkillSyncTarget(userHome),
                GrokSkillSyncTarget(userHome),
                JunieSkillSyncTarget(userHome),
                KiloSkillSyncTarget(userHome),
                KimiSkillSyncTarget(userHome),
                KiroSkillSyncTarget(userHome),
                MimoSkillSyncTarget(userHome),
                OmpSkillSyncTarget(userHome),
                OpenCodeSkillSyncTarget(userHome),
                QwenSkillSyncTarget(userHome),
                VibeSkillSyncTarget(userHome),
            ).associateBy(SkillSyncTarget::agentId)
    }
}
