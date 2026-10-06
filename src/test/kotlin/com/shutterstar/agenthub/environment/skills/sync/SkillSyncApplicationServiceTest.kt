package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.model.ConflictResolution
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncTargetOutcome
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateService
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillSyncApplicationServiceTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `an agent that reads the shared directory without a sync target is reported as native`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(root.resolve("claude"))),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            operationId = { "ui-operation" },
        )

        val statuses = facade.targetStatuses(skill(canonical), SkillScope.GLOBAL, null).associate { it.agentId to it.status }

        assertEquals(SkillTargetStatus.NATIVE, statuses["freebuff"])
        assertEquals(SkillTargetStatus.NOT_AVAILABLE, statuses["claude"])
    }

    @Test
    fun `preparing is read only and execution uses persistent collaborators`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val targetRoot = root.resolve("claude")
        val targetPath = targetRoot.resolve("review")
        val ownership = SkillOwnershipStateService()
        val audit = SkillSyncAuditStateService()
        val settings = SkillSyncSettingsStateService().apply {
            update(SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY))
        }
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = ownership,
            auditTrail = audit,
            settings = settings,
            sharedSkillDirectory = SharedSkillProvider(root),
            operationId = { "ui-operation" },
        )

        val prepared = facade.prepareShare(skill(canonical), "claude", SkillScope.GLOBAL, null)

        assertFalse(Files.exists(targetPath), "Dry-run preparation must not mutate the target")
        assertTrue(prepared.planResult.plan.steps.isNotEmpty())
        assertEquals(SkillTargetStatus.NOT_AVAILABLE, facade.targetStatuses(skill(canonical), SkillScope.GLOBAL, null).single { it.agentId == "claude" }.status)

        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; targets=${result.targetResults}")
        assertTrue(Files.exists(targetPath.resolve("SKILL.md")))
        assertEquals(SkillTargetStatus.COPIED, facade.targetStatuses(skill(canonical), SkillScope.GLOBAL, null).single { it.agentId == "claude" }.status)
        assertEquals("ui-operation", ownership.managedTarget(requireNotNull(result.instanceKey), "claude")?.operationId)
        assertEquals("ui-operation", audit.recentEntries().single().operationId)
    }

    @Test
    fun `updateSettings persists and currentSettings reflects it for subsequently prepared requests`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val targetRoot = root.resolve("claude")
        val settingsService = SkillSyncSettingsStateService()
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = settingsService,
            sharedSkillDirectory = SharedSkillProvider(root),
        )

        assertEquals(SkillSyncMode.SYMLINK, facade.currentSettings().preferredSyncMode, "default before any settings dialog use")

        facade.updateSettings(SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY))

        assertEquals(SkillSyncMode.COPY, facade.currentSettings().preferredSyncMode)
        assertEquals(SkillSyncMode.COPY, settingsService.current().preferredSyncMode, "written through to the persistent service, not just an in-memory copy")
        val prepared = facade.prepareShare(skill(canonical), "claude", SkillScope.GLOBAL, null)
        assertTrue(
            prepared.planResult.plan.steps.none { it is SkillSyncStep.CreateLink },
            "the new COPY default should produce a managed copy, never a link step",
        )
        assertTrue(prepared.planResult.plan.steps.any { it is SkillSyncStep.CopySkill })
    }

    @Test
    fun `backupBeforeReplacement = false stops the engine from writing a restorable backup for a real replacement`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val targetRoot = root.resolve("claude")
        writeSkillMd(targetRoot.resolve("review"), "content") // pre-existing identical content triggers a backup+replace plan
        val settingsService = SkillSyncSettingsStateService().apply {
            update(SkillSyncSettings(backupBeforeReplacement = false))
        }
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = settingsService,
            sharedSkillDirectory = SharedSkillProvider(root),
        )
        val theSkill = skill(canonical)

        val prepared = facade.prepareShare(theSkill, "claude", SkillScope.GLOBAL, null)
        assertTrue(prepared.planResult.plan.steps.none { it is SkillSyncStep.BackupExisting })

        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; targets=${result.targetResults}")
        assertTrue(facade.restorableBackups(theSkill, SkillScope.GLOBAL, null).isEmpty(), "no backup should have been written to disk")
    }

    @Test
    fun `share targets are limited to detected installed adapters`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val facade = SkillSyncApplicationService(
            targets = mapOf(
                "claude" to Target(root.resolve("claude"), "claude"),
                "kiro" to Target(root.resolve("kiro"), "kiro"),
            ),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            detectedInstalledAgentIds = { setOf("kiro", "unsupported") },
        )

        assertEquals(listOf("kiro"), facade.shareTargetIds())
        assertEquals(setOf("claude", "kiro"), facade.adapterTargetIds())
        val prepared = facade.prepareShareEverywhere(skill(canonical), SkillScope.GLOBAL, null)
        assertEquals(listOf("kiro"), prepared.planResult.planningRequest.targets.map { it.agentId })
    }

    @Test
    fun `an unknown detection offers no share targets instead of every capable agent`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(root.resolve("claude"), "claude")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            detectedInstalledAgentIds = { emptySet() },
        )

        assertTrue(facade.shareTargetIds().isEmpty())
        assertTrue(facade.prepareShareEverywhere(skill(canonical), SkillScope.GLOBAL, null).planResult.plan.steps.isEmpty())
    }

    @Test
    fun `operations naming an agent that is no longer installed produce no steps and never touch the target`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val claudeRoot = root.resolve("claude")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude"), "kiro" to Target(root.resolve("kiro"), "kiro")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            isAgentInstalled = { it != "claude" },
        )
        val theSkill = skill(canonical)

        val prepared = listOf(
            facade.prepareShare(theSkill, "claude", SkillScope.GLOBAL, null),
            facade.prepareResync(theSkill, "claude", SkillScope.GLOBAL, null),
            facade.prepareRepair(theSkill, "claude", SkillScope.GLOBAL, null),
            facade.prepareStopSharing(theSkill, "claude", SkillScope.GLOBAL, null),
            facade.prepareShareToSelected(theSkill, setOf("claude", "kiro"), SkillScope.GLOBAL, null),
            facade.prepareUpdateSharing(theSkill, setOf("claude"), emptySet(), SkillScope.GLOBAL, null),
        )

        prepared.forEach { plan ->
            assertTrue(plan.planResult.plan.steps.isEmpty(), "no steps for a hidden agent: ${plan.planResult.plan}")
            assertTrue(plan.planResult.plan.warnings.single().message.contains("not installed"))
        }
        assertFalse(Files.exists(claudeRoot.resolve("review")))
        assertTrue(
            facade.prepareShare(theSkill, "kiro", SkillScope.GLOBAL, null).planResult.plan.steps.isNotEmpty(),
            "an installed agent is still planned normally",
        )
    }

    @Test
    fun `a runtime that disallows mutations gets a no-op plan and a failed result`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val targetRoot = root.resolve("claude")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            runtimeMutationAllowed = { false },
        )

        val prepared = facade.prepareShare(skill(canonical), "claude", SkillScope.GLOBAL, null)
        val result = facade.execute(prepared)

        assertTrue(prepared.planResult.plan.steps.isEmpty())
        assertTrue(prepared.planResult.plan.warnings.single().message.contains("not available"))
        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertFalse(Files.exists(targetRoot.resolve("review")))
    }

    @Test
    fun `prepareShareToSelected shares only the chosen subset - the primitive behind the multi-select Share with… button`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val claudeRoot = root.resolve("claude")
        val kiroRoot = root.resolve("kiro")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude"), "kiro" to Target(kiroRoot, "kiro")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
        )

        val prepared = facade.prepareShareToSelected(skill(canonical), setOf("kiro"), SkillScope.GLOBAL, null)
        assertEquals(listOf("kiro"), prepared.planResult.planningRequest.targets.map { it.agentId })

        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertTrue(Files.exists(kiroRoot.resolve("review").resolve("SKILL.md")))
        assertFalse(Files.exists(claudeRoot.resolve("review")), "an unselected installed target must be left untouched")
    }

    @Test
    fun `prepareRetryFailedTargets scopes a fresh plan to only the requested agents and actually shares to them`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val claudeRoot = root.resolve("claude")
        val facade = SkillSyncApplicationService(
            targets = mapOf(
                "claude" to Target(claudeRoot, "claude"),
                "kiro" to Target(root.resolve("kiro"), "kiro"),
            ),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
        )

        val retryPrepared = facade.prepareRetryFailedTargets(skill(canonical), setOf("claude"), SkillScope.GLOBAL, null)

        assertEquals(listOf("claude"), retryPrepared.planResult.planningRequest.targets.map { it.agentId })
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(retryPrepared).status)
        assertTrue(Files.exists(claudeRoot.resolve("review").resolve("SKILL.md")))
    }

    @Test
    fun `prepareWholeSkillRepair fixes a drifted managed copy without naming a target agent`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical content")
        val targetRoot = root.resolve("claude")
        val targetPath = writeSkillMd(targetRoot.resolve("review"), "stale content")
        val ownership = SkillOwnershipStateService().apply {
            record(
                SkillInstanceKey.host("review", SkillScope.GLOBAL, canonical),
                ManagedTarget("claude", targetPath.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, SkillFingerprint().calculate(targetPath)),
            )
        }
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = ownership,
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
        )

        val repairPrepared = facade.prepareWholeSkillRepair(skill(canonical), SkillScope.GLOBAL, null)

        assertTrue(repairPrepared.planResult.plan.steps.any { it.agentId == "claude" })
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(repairPrepared).status)
        assertEquals("canonical content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `resync and stop sharing prepare through the persistent facade, and managed target ids reflect ownership`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical content")
        val targetRoot = root.resolve("claude")
        val targetPath = writeSkillMd(targetRoot.resolve("review"), "stale content")
        val ownership = SkillOwnershipStateService().apply {
            record(
                SkillInstanceKey.host("review", SkillScope.GLOBAL, canonical),
                ManagedTarget("claude", targetPath.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, SkillFingerprint().calculate(targetPath)),
            )
        }
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = ownership,
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
        )
        val theSkill = skill(canonical)

        assertEquals(setOf("claude"), facade.managedTargetIds(theSkill, SkillScope.GLOBAL, null))

        val resyncPrepared = facade.prepareResync(theSkill, "claude", SkillScope.GLOBAL, null)
        assertTrue(resyncPrepared.planResult.plan.steps.isNotEmpty(), "A stale managed copy should need a refresh")
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(resyncPrepared).status)
        assertEquals("canonical content", Files.readString(targetPath.resolve("SKILL.md")))

        val stopSharingPrepared = facade.prepareStopSharing(theSkill, "claude", SkillScope.GLOBAL, null)
        assertTrue(stopSharingPrepared.planResult.plan.steps.isNotEmpty())
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(stopSharingPrepared).status)
        assertFalse(Files.exists(targetPath), "Stop Sharing must remove the managed target")
        assertTrue(facade.managedTargetIds(theSkill, SkillScope.GLOBAL, null).isEmpty())

        val history = facade.historyFor(theSkill, SkillScope.GLOBAL, null)
        assertEquals(listOf(SyncAction.STOP_SHARING, SyncAction.RESYNC), history.map { it.action }, "Most recent first")
        assertTrue(history.all { it.result == SyncOperationStatus.SUCCESS })

        val backups = facade.restorableBackups(theSkill, SkillScope.GLOBAL, null)
        assertEquals(2, backups.size, "Resync's pre-refresh backup and Stop Sharing's pre-removal backup")
        val restoreResult = facade.executeRestoreBackup(backups.first { it.backup.operationId == stopSharingPrepared.planResult.plan.operationId })
        assertEquals(SyncOperationStatus.SUCCESS, restoreResult.status)
        assertEquals("canonical content", Files.readString(targetPath.resolve("SKILL.md")), "Restore Backup must undo Stop Sharing's removal")
        assertEquals(SyncAction.RESTORE_BACKUP, facade.historyFor(theSkill, SkillScope.GLOBAL, null).first().action)
    }

    @Test
    fun `execute chains PromoteSkill alsoShareWith into a rediscover-then-share, merged into one result`() {
        val claudeRoot = root.resolve("claude")
        val sourcePath = writeSkillMd(claudeRoot.resolve("review"), "content")
        val kiroRoot = root.resolve("kiro")
        val canonicalPath = root.resolve(".agents").resolve("skills").resolve("review")
        val preSourceSkill = AgentSkill(
            SkillIdentity("skill-1"),
            "review",
            null,
            SkillScope.GLOBAL,
            listOf(SkillSource("claude", sourcePath.toString(), SkillScope.GLOBAL, false, "fixture")),
            setOf("claude"),
            SkillConsistency.SINGLE_SOURCE,
        )
        val postPromoteSkill = preSourceSkill.copy(
            sources = preSourceSkill.sources + SkillSource(null, canonicalPath.toString(), SkillScope.GLOBAL, true, "fixture"),
        )
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude"), "kiro" to Target(kiroRoot, "kiro")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            rediscoverForMigration = { _, _ -> listOf(postPromoteSkill) },
        )

        val prepared = facade.preparePromote(preSourceSkill, "claude", sourcePath, SkillScope.GLOBAL, null, alsoShareWith = setOf("kiro"))
        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; targets=${result.targetResults}")
        assertTrue(Files.exists(kiroRoot.resolve("review").resolve("SKILL.md")), "the chained share must have actually run")
        // targetResults keeps the promote's own "claude" entry and gains "kiro" from the chained share.
        assertEquals(setOf("claude", "kiro"), result.targetResults.map { it.agentId }.toSet())
        assertEquals(SyncTargetOutcome.CHANGED, result.targetResults.first { it.agentId == "kiro" }.outcome)
        // The merged view only adds the share's outcome for display; the promote's own operation
        // id, applied steps and instance key still describe the promote alone (see doc comment on
        // SkillSyncApplicationService.mergedWithAlsoShareWith) - the share itself is a separate,
        // individually undoable audit entry (a multi-target share, hence SHARE_EVERYWHERE).
        assertEquals(prepared.planResult.plan.operationId, result.operationId)
        val history = facade.historyFor(postPromoteSkill, SkillScope.GLOBAL, null)
        assertEquals(listOf(SyncAction.SHARE_EVERYWHERE, SyncAction.PROMOTE), history.map { it.action })
    }

    @Test
    fun `an alsoShareWith target with no adapter warns instead of failing the merged result, and does not block the rest`() {
        // The chained share goes through ShareSkillEverywhere (one plan covering every requested
        // target), which - unlike the single-target ShareSkill BulkMigrationOrchestrator uses for
        // its own fan-out - treats an agent with no registered adapter as a plan-level warning and
        // simply excludes it, rather than failing the whole multi-target share.
        val claudeRoot = root.resolve("claude")
        val sourcePath = writeSkillMd(claudeRoot.resolve("review"), "content")
        val kiroRoot = root.resolve("kiro")
        val canonicalPath = root.resolve(".agents").resolve("skills").resolve("review")
        val preSourceSkill = AgentSkill(
            SkillIdentity("skill-1"),
            "review",
            null,
            SkillScope.GLOBAL,
            listOf(SkillSource("claude", sourcePath.toString(), SkillScope.GLOBAL, false, "fixture")),
            setOf("claude"),
            SkillConsistency.SINGLE_SOURCE,
        )
        val facade = SkillSyncApplicationService(
            // No adapter registered for "cursor".
            targets = mapOf("claude" to Target(claudeRoot, "claude"), "kiro" to Target(kiroRoot, "kiro")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            rediscoverForMigration = { _, _ ->
                listOf(
                    preSourceSkill.copy(
                        sources = preSourceSkill.sources + SkillSource(null, canonicalPath.toString(), SkillScope.GLOBAL, true, "fixture"),
                    ),
                )
            },
        )

        val prepared = facade.preparePromote(preSourceSkill, "claude", sourcePath, SkillScope.GLOBAL, null, alsoShareWith = setOf("kiro", "cursor"))
        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; targets=${result.targetResults}")
        assertTrue(Files.exists(kiroRoot.resolve("review").resolve("SKILL.md")))
        assertEquals(setOf("claude", "kiro"), result.targetResults.map { it.agentId }.toSet(), "cursor has no adapter, so it never gets a step or a target result")
    }

    @Test
    fun `a vendor copy in a sub-folder of the agent root can be promoted`() {
        val claudeRoot = root.resolve("claude")
        val sourcePath = writeSkillMd(claudeRoot.resolve("synced").resolve("bucket").resolve("review"), "content")
        val skill = AgentSkill(
            SkillIdentity("skill-1"),
            "review",
            null,
            SkillScope.GLOBAL,
            listOf(SkillSource("claude", sourcePath.toString(), SkillScope.GLOBAL, false, "fixture", system = true)),
            setOf("claude"),
            SkillConsistency.SINGLE_SOURCE,
        )
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
        )

        val prepared = facade.preparePromote(skill, "claude", sourcePath, SkillScope.GLOBAL, null)
        val result = facade.execute(prepared)

        assertTrue(prepared.planResult.plan.steps.isNotEmpty(), prepared.planResult.plan.warnings.toString())
        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}")
        assertTrue(Files.exists(root.resolve(".agents").resolve("skills").resolve("review").resolve("SKILL.md")))
        assertTrue(Files.exists(sourcePath.resolve("SKILL.md")), "the vendor location is a copy of the shared skill again")
    }

    @Test
    fun `a failed promote is returned as-is without attempting alsoShareWith`() {
        val claudeRoot = root.resolve("claude")
        // No source directory on disk: the promote's own fingerprint check fails immediately.
        val preSourceSkill = AgentSkill(
            SkillIdentity("skill-1"),
            "review",
            null,
            SkillScope.GLOBAL,
            listOf(SkillSource("claude", claudeRoot.resolve("review").toString(), SkillScope.GLOBAL, false, "fixture")),
            setOf("claude"),
            SkillConsistency.SINGLE_SOURCE,
        )
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            rediscoverForMigration = { _, _ -> error("Must not rediscover after a failed promote") },
        )

        val prepared = facade.preparePromote(preSourceSkill, "claude", claudeRoot.resolve("review"), SkillScope.GLOBAL, null, alsoShareWith = setOf("kiro"))
        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertTrue(result.targetResults.isEmpty())
    }

    @Test
    fun `prepareResolveConflict plans and executes KEEP_CANONICAL through the facade`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical content")
        val claudeRoot = root.resolve("claude")
        val targetPath = writeSkillMd(claudeRoot.resolve("review"), "conflicting content")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
        )
        val canonicalSkill = skill(canonical)
        val conflictingSkill = canonicalSkill.copy(
            sources = canonicalSkill.sources + SkillSource("claude", targetPath.toString(), SkillScope.GLOBAL, false, "fixture"),
            consistency = SkillConsistency.DIFFERENT,
        )

        val prepared = facade.prepareResolveConflict(conflictingSkill, "claude", ConflictResolution.KEEP_CANONICAL, SkillScope.GLOBAL, null)
        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertEquals("canonical content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `prepareUpdateSharing shares the newly checked agent and stops the unchecked one in one reviewed operation`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical content")
        val claudeRoot = root.resolve("claude")
        val kiroRoot = root.resolve("kiro")
        val facade = updateSharingFacade(claudeRoot, kiroRoot)
        val theSkill = skill(canonical)
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(facade.prepareShareToSelected(theSkill, setOf("claude"), SkillScope.GLOBAL, null)).status)
        assertEquals(setOf("claude"), facade.managedTargetIds(theSkill, SkillScope.GLOBAL, null))

        val prepared = facade.prepareUpdateSharing(theSkill, setOf("kiro"), setOf("claude"), SkillScope.GLOBAL, null)

        assertEquals(SyncAction.UPDATE_SHARING, prepared.planResult.action)
        assertTrue(prepared.planResult.plan.steps.any { it.agentId == "claude" && it is SkillSyncStep.RemoveExisting })
        assertTrue(prepared.planResult.plan.steps.any { it.agentId == "kiro" && it is SkillSyncStep.CopySkill })
        assertEquals(setOf("claude", "kiro"), prepared.planResult.planningRequest.targets.map { it.agentId }.toSet())
        assertTrue(Files.exists(claudeRoot.resolve("review")), "preparing is a dry run")
        assertFalse(Files.exists(kiroRoot.resolve("review")), "preparing is a dry run")

        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; targets=${result.targetResults}")
        assertFalse(Files.exists(claudeRoot.resolve("review")), "the unchecked agent's sharing is removed")
        assertEquals("canonical content", Files.readString(kiroRoot.resolve("review").resolve("SKILL.md")))
        assertEquals(setOf("kiro"), facade.managedTargetIds(theSkill, SkillScope.GLOBAL, null))
        val history = facade.historyFor(theSkill, SkillScope.GLOBAL, null)
        assertEquals(listOf(SyncAction.UPDATE_SHARING, SyncAction.SHARE_EVERYWHERE), history.map { it.action }, "one entry for the whole change")
        assertEquals(setOf("claude", "kiro"), history.first().affectedAgents)
    }

    @Test
    fun `unchecking every shared agent stops all sharing and the preview says the managed copy is removed`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val claudeRoot = root.resolve("claude")
        val facade = updateSharingFacade(claudeRoot, root.resolve("kiro"))
        val theSkill = skill(canonical)
        facade.execute(facade.prepareShareToSelected(theSkill, setOf("claude"), SkillScope.GLOBAL, null))

        val prepared = facade.prepareUpdateSharing(theSkill, emptySet(), setOf("claude"), SkillScope.GLOBAL, null)
        val preview = com.shutterstar.agenthub.environment.skills.ui.SkillSyncPreviewModel.from(prepared) { it }

        assertTrue(preview.targets.single().plannedChange.contains("remove the AgentHub-managed link or copy"), preview.targets.single().plannedChange)
        assertEquals("Update sharing for review", preview.title)
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertFalse(Files.exists(claudeRoot.resolve("review")))
        assertTrue(facade.managedTargetIds(theSkill, SkillScope.GLOBAL, null).isEmpty())
    }

    @Test
    fun `an agent named as both shared and stopped is only shared, never removed`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val claudeRoot = root.resolve("claude")
        val facade = updateSharingFacade(claudeRoot, root.resolve("kiro"))
        val theSkill = skill(canonical)
        facade.execute(facade.prepareShareToSelected(theSkill, setOf("claude"), SkillScope.GLOBAL, null))

        val prepared = facade.prepareUpdateSharing(theSkill, setOf("claude"), setOf("claude"), SkillScope.GLOBAL, null)

        // Sharing an already shared copy re-copies it (backup, replace, copy) — the point is that
        // the agent is planned as *shared*, not stopped: its plan ends in a copy, never in a bare removal.
        val claudeSteps = prepared.planResult.plan.steps.filter { it.agentId == "claude" }
        assertTrue(claudeSteps.any { it is SkillSyncStep.CopySkill }, "steps=$claudeSteps")
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertTrue(Files.exists(claudeRoot.resolve("review").resolve("SKILL.md")))
        assertEquals(setOf("claude"), facade.managedTargetIds(theSkill, SkillScope.GLOBAL, null))
    }

    @Test
    fun `an update naming no agents plans nothing and says so`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val facade = updateSharingFacade(root.resolve("claude"), root.resolve("kiro"))

        val prepared = facade.prepareUpdateSharing(skill(canonical), emptySet(), emptySet(), SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.isEmpty())
        assertTrue(prepared.planResult.plan.warnings.single().message.contains("nothing to change"))
    }

    @Test
    fun `undoing an update restores the stopped agent and removes the newly shared one`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical content")
        val claudeRoot = root.resolve("claude")
        val kiroRoot = root.resolve("kiro")
        val facade = updateSharingFacade(claudeRoot, kiroRoot)
        val theSkill = skill(canonical)
        facade.execute(facade.prepareShareToSelected(theSkill, setOf("claude"), SkillScope.GLOBAL, null))
        val prepared = facade.prepareUpdateSharing(theSkill, setOf("kiro"), setOf("claude"), SkillScope.GLOBAL, null)
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)

        val undo = facade.undoOperation(prepared.planResult.plan.operationId)

        assertTrue(undo != null && undo.errors.isEmpty(), "undo=$undo")
        assertEquals("canonical content", Files.readString(claudeRoot.resolve("review").resolve("SKILL.md")), "the removed sharing comes back from its backup")
        assertFalse(Files.exists(kiroRoot.resolve("review")), "the newly created sharing is removed again")
    }

    @Test
    fun `history reports a backup that was removed instead of silently dropping Undo`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical content")
        val claudeRoot = root.resolve("claude")
        val kiroRoot = root.resolve("kiro")
        val facade = updateSharingFacade(claudeRoot, kiroRoot)
        val theSkill = skill(canonical)
        facade.execute(facade.prepareShareToSelected(theSkill, setOf("claude"), SkillScope.GLOBAL, null))
        val prepared = facade.prepareUpdateSharing(theSkill, setOf("kiro"), setOf("claude"), SkillScope.GLOBAL, null)
        facade.execute(prepared)
        val operationId = prepared.planResult.plan.operationId

        assertEquals(setOf(operationId), facade.undoAvailability(setOf(operationId)).undoable)

        Files.list(root.resolve("backups").resolve(operationId)).use { children ->
            children.filter { it.fileName.toString() != "journal.properties" }.forEach { it.toFile().deleteRecursively() }
        }

        val availability = facade.undoAvailability(setOf(operationId, "unknown-operation"))
        assertTrue(availability.undoable.isEmpty())
        assertEquals(setOf(operationId), availability.backupRemoved, "an operation without a journal is not reported as lacking a backup")
    }

    @Test
    fun `an identical copy AgentHub did not create is not removed by default, and the warning names the setting`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val claudeCopy = writeSkillMd(root.resolve("claude").resolve("review"), "content")
        val facade = manageExistingFacade(root.resolve("claude"), manageExisting = false)

        val prepared = facade.prepareUpdateSharing(skill(canonical), emptySet(), setOf("claude"), SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.isEmpty())
        assertTrue(prepared.planResult.plan.warnings.single().message.contains("Manage existing skills"), prepared.planResult.plan.warnings.toString())
        facade.execute(prepared)
        assertTrue(Files.exists(claudeCopy.resolve("SKILL.md")), "nothing may be removed while the setting is off")
    }

    @Test
    fun `with manage existing on, unchecking an identical copy removes it after a backup and Undo brings it back`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val claudeCopy = writeSkillMd(root.resolve("claude").resolve("review"), "content")
        val facade = manageExistingFacade(root.resolve("claude"), manageExisting = true)
        val theSkill = skill(canonical)

        val prepared = facade.prepareUpdateSharing(theSkill, emptySet(), setOf("claude"), SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.any { it is SkillSyncStep.BackupExisting })
        assertTrue(prepared.planResult.plan.steps.any { it is SkillSyncStep.RemoveExisting })
        assertTrue(prepared.planResult.plan.warnings.single().message.contains("not created by AgentHub"))
        assertTrue(Files.exists(claudeCopy), "preparing is a dry run")

        val result = facade.execute(prepared)

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; targets=${result.targetResults}")
        assertFalse(Files.exists(claudeCopy))
        assertEquals(1, facade.restorableBackups(theSkill, SkillScope.GLOBAL, null).size)

        val undo = facade.undoOperation(prepared.planResult.plan.operationId)

        assertTrue(undo != null && undo.errors.isEmpty(), "undo=$undo")
        assertEquals("content", Files.readString(claudeCopy.resolve("SKILL.md")))
    }

    @Test
    fun `a copy whose content differs from the shared skill is never removed, even with manage existing on`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "canonical")
        val diverged = writeSkillMd(root.resolve("claude").resolve("review"), "my own edits")
        val facade = manageExistingFacade(root.resolve("claude"), manageExisting = true)

        val prepared = facade.prepareUpdateSharing(skill(canonical), emptySet(), setOf("claude"), SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.isEmpty(), "diverged content must stay protected")
        facade.execute(prepared)
        assertEquals("my own edits", Files.readString(diverged.resolve("SKILL.md")))
    }

    @Test
    fun `removing an existing target is backed up even when backups before replacement are switched off`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        writeSkillMd(root.resolve("claude").resolve("review"), "content")
        val facade = manageExistingFacade(root.resolve("claude"), manageExisting = true, backupBeforeReplacement = false)

        val prepared = facade.prepareUpdateSharing(skill(canonical), emptySet(), setOf("claude"), SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.any { it is SkillSyncStep.BackupExisting }, "nothing AgentHub didn't create is removed without a way back")
    }

    @Test
    fun `with manage existing on, a symlink AgentHub did not create is removed and Undo recreates it`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val claudeRoot = root.resolve("claude")
        Files.createDirectories(claudeRoot)
        val link = claudeRoot.resolve("review")
        val created = runCatching { Files.createSymbolicLink(link, canonical) }.isSuccess
        Assumptions.assumeTrue(created, "creating symbolic links needs a privilege this machine does not grant")
        val facade = manageExistingFacade(claudeRoot, manageExisting = true)
        val theSkill = skill(canonical)

        val prepared = facade.prepareUpdateSharing(theSkill, emptySet(), setOf("claude"), SkillScope.GLOBAL, null)
        val backup = prepared.planResult.plan.steps.filterIsInstance<SkillSyncStep.BackupExisting>().single()

        assertEquals(EffectiveSyncMode.SYMLINK, backup.representation)
        assertEquals(canonical, backup.linkTarget)
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link is gone")
        assertTrue(Files.exists(canonical.resolve("SKILL.md")), "the shared skill itself is untouched")

        val undo = facade.undoOperation(prepared.planResult.plan.operationId)

        assertTrue(undo != null && undo.errors.isEmpty(), "undo=$undo")
        assertTrue(Files.isSymbolicLink(link), "Undo recreates the link")
    }

    @Test
    fun `with manage existing on, a Windows junction AgentHub did not create is removed without touching the shared skill, and Undo recreates it`() {
        Assumptions.assumeTrue(com.shutterstar.agenthub.OsDetector.isWindows(), "junctions are the Windows link kind")
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val claudeRoot = root.resolve("claude")
        Files.createDirectories(claudeRoot)
        val junction = claudeRoot.resolve("review")
        val created = com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy().createLink(canonical, junction)
        Assumptions.assumeTrue(
            created is com.shutterstar.agenthub.environment.skills.sync.link.LinkResult.Success,
            "could not create a junction here: $created",
        )
        val facade = manageExistingFacade(claudeRoot, manageExisting = true)
        val theSkill = skill(canonical)
        assertEquals(SkillTargetStatus.LINKED, facade.targetStatuses(theSkill, SkillScope.GLOBAL, null).single { it.agentId == "claude" }.status)

        val prepared = facade.prepareUpdateSharing(theSkill, emptySet(), setOf("claude"), SkillScope.GLOBAL, null)
        val backup = prepared.planResult.plan.steps.filterIsInstance<SkillSyncStep.BackupExisting>().single()

        assertEquals(EffectiveSyncMode.JUNCTION, backup.representation)
        assertEquals(canonical, backup.linkTarget)
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertFalse(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the junction is gone")
        assertEquals("content", Files.readString(canonical.resolve("SKILL.md")), "removing the junction must never delete the shared skill")

        val undo = facade.undoOperation(prepared.planResult.plan.operationId)

        assertTrue(undo != null && undo.errors.isEmpty(), "undo=$undo")
        assertTrue(Files.isSameFile(junction, canonical), "Undo recreates the junction to the shared skill")
    }

    @Test
    fun `an identical copy of a skill the agent reads from the shared folder is removed behind a backup and Undo restores it`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val codexCopy = writeSkillMd(root.resolve("codex").resolve("review"), "content")
        // The "Manage existing skills" setting is off: the button press is the consent.
        val facade = redundantCopyFacade(root.resolve("codex"))

        val prepared = facade.prepareRemoveRedundantCopy(skill(canonical), "codex", SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.any { it is SkillSyncStep.BackupExisting })
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertFalse(Files.exists(codexCopy), "the agent's own copy is gone")
        assertEquals("content", Files.readString(canonical.resolve("SKILL.md")), "the shared skill is untouched")

        val undo = facade.undoOperation(prepared.planResult.plan.operationId)

        assertTrue(undo != null && undo.errors.isEmpty(), "undo=$undo")
        assertTrue(Files.exists(codexCopy.resolve("SKILL.md")), "Undo brings the copy back")
    }

    @Test
    fun `a redundant symlink is only unlinked, the shared skill it points to stays`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val codexRoot = root.resolve("codex")
        Files.createDirectories(codexRoot)
        val link = codexRoot.resolve("review")
        Assumptions.assumeTrue(
            runCatching { Files.createSymbolicLink(link, canonical) }.isSuccess,
            "creating symbolic links needs a privilege this machine does not grant",
        )
        val facade = redundantCopyFacade(codexRoot)

        val prepared = facade.prepareRemoveRedundantCopy(skill(canonical), "codex", SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.warnings.single().message.contains("only the link"))
        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link is gone")
        assertEquals("content", Files.readString(canonical.resolve("SKILL.md")), "the shared skill is untouched")
    }

    @Test
    fun `a redundant Windows junction is only unlinked, the shared skill it points to stays`() {
        Assumptions.assumeTrue(com.shutterstar.agenthub.OsDetector.isWindows(), "junctions are the Windows link kind")
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val codexRoot = root.resolve("codex")
        Files.createDirectories(codexRoot)
        val junction = codexRoot.resolve("review")
        val created = com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy().createLink(canonical, junction)
        Assumptions.assumeTrue(
            created is com.shutterstar.agenthub.environment.skills.sync.link.LinkResult.Success,
            "could not create a junction here: $created",
        )
        val facade = redundantCopyFacade(codexRoot)

        val prepared = facade.prepareRemoveRedundantCopy(skill(canonical), "codex", SkillScope.GLOBAL, null)

        assertEquals(SyncOperationStatus.SUCCESS, facade.execute(prepared).status)
        assertFalse(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the junction is gone")
        assertEquals("content", Files.readString(canonical.resolve("SKILL.md")), "removing the junction must never delete the shared skill")
    }

    @Test
    fun `a copy that differs from the shared skill is never removed as redundant`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        val codexCopy = writeSkillMd(root.resolve("codex").resolve("review"), "my own edits")
        val facade = redundantCopyFacade(root.resolve("codex"))

        val prepared = facade.prepareRemoveRedundantCopy(skill(canonical), "codex", SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.isEmpty())
        assertTrue(prepared.planResult.plan.warnings.single().message.contains("differs"))
        assertTrue(Files.exists(codexCopy.resolve("SKILL.md")))
    }

    @Test
    fun `an agent that does not read the shared folder keeps its own copy`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        writeSkillMd(root.resolve("claude").resolve("review"), "content")
        val facade = manageExistingFacade(root.resolve("claude"), manageExisting = true)

        val prepared = facade.prepareRemoveRedundantCopy(skill(canonical), "claude", SkillScope.GLOBAL, null)

        assertTrue(prepared.planResult.plan.steps.isEmpty())
    }

    @Test
    fun `removeRedundantCopies reports what was removed per agent`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        writeSkillMd(root.resolve("codex").resolve("review"), "content")
        val facade = redundantCopyFacade(root.resolve("codex"))
        val candidate = com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyCandidate(skill(canonical), listOf("codex"))

        val outcome = facade.removeRedundantCopies(candidate, SkillScope.GLOBAL, null)

        assertEquals(listOf("codex"), outcome.removed)
        assertTrue(outcome.skipped.isEmpty() && outcome.failed.isEmpty(), "outcome=$outcome")
    }

    @Test
    fun `removeRedundantCopies also runs the share for agents whose identical copy is to become a link`() {
        val canonical = writeSkillMd(root.resolve("shared/review"), "content")
        writeSkillMd(root.resolve("claude").resolve("review"), "content")
        val facade = manageExistingFacade(root.resolve("claude"), manageExisting = false)
        val candidate = com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyCandidate(
            skill(canonical), emptyList(), convertAgentIds = listOf("claude"),
        )

        val outcome = facade.removeRedundantCopies(candidate, SkillScope.GLOBAL, null)

        assertEquals(listOf("claude"), outcome.converted)
        assertTrue(outcome.removed.isEmpty() && outcome.failed.isEmpty(), "outcome=$outcome")
    }

    @Test
    fun `a copy can only be swapped for a link by an adapter that links while links are the preferred mode`() {
        // Both fixtures here are copy-mode, link-less adapters.
        assertFalse(manageExistingFacade(root.resolve("claude"), manageExisting = false).canReplaceCopyWithLink("claude"))
        assertFalse(redundantCopyFacade(root.resolve("codex")).canReplaceCopyWithLink("claude"))
    }

    private fun redundantCopyFacade(codexRoot: Path) = SkillSyncApplicationService(
        targets = mapOf("codex" to Target(codexRoot, "codex")),
        backupRoot = root.resolve("backups"),
        ownershipStore = SkillOwnershipStateService(),
        auditTrail = SkillSyncAuditStateService(),
        settings = SkillSyncSettingsStateService().apply { update(SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY)) },
        sharedSkillDirectory = SharedSkillProvider(root),
    )

    private fun manageExistingFacade(claudeRoot: Path, manageExisting: Boolean, backupBeforeReplacement: Boolean = true) =
        SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService().apply {
                update(
                    SkillSyncSettings(
                        preferredSyncMode = SkillSyncMode.COPY,
                        backupBeforeReplacement = backupBeforeReplacement,
                        manageExistingTargets = manageExisting,
                    ),
                )
            },
            sharedSkillDirectory = SharedSkillProvider(root),
        )

    private fun updateSharingFacade(claudeRoot: Path, kiroRoot: Path) = SkillSyncApplicationService(
        targets = mapOf("claude" to Target(claudeRoot, "claude"), "kiro" to Target(kiroRoot, "kiro")),
        backupRoot = root.resolve("backups"),
        ownershipStore = SkillOwnershipStateService(),
        auditTrail = SkillSyncAuditStateService(),
        // Copies, not links: the assertions below are about which agents are shared, not link mechanics.
        settings = SkillSyncSettingsStateService().apply { update(SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY)) },
        sharedSkillDirectory = SharedSkillProvider(root),
    )

    private fun skill(path: Path) = AgentSkill(
        SkillIdentity("review"),
        "review",
        null,
        SkillScope.GLOBAL,
        listOf(SkillSource(null, path.toString(), SkillScope.GLOBAL, true, "fixture", "Review")),
        emptySet(),
        SkillConsistency.SINGLE_SOURCE,
    )

    private class Target(
        private val root: Path,
        override val agentId: String = "claude",
    ) : SkillSyncTarget {

        override fun globalSkillDirectory(): Path = root

        override fun projectSkillDirectory(project: DiscoveredProject): Path = root

        override fun supportsLinkedSkills(): Boolean = false
    }
}
