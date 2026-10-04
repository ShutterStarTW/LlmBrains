package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.sync.link.CopyStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.link.UnixSymlinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore
import java.nio.file.Files

/** Executes exactly one [SkillSyncStep] against the real filesystem. */
internal class SkillSyncStepExecutor(
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
    private val linkStrategies: Map<EffectiveSyncMode, FileLinkStrategy> = mapOf(
        EffectiveSyncMode.SYMLINK to UnixSymlinkStrategy(),
        EffectiveSyncMode.JUNCTION to WindowsJunctionStrategy(),
    ),
    private val copyStrategy: FileLinkStrategy = CopyStrategy(),
    private val backupService: BackupService = BackupService(),
    private val ownershipStore: SyncOwnershipStore = InMemorySyncOwnershipStore(),
) {
    fun execute(step: SkillSyncStep, context: StepExecutionContext): StepOutcome = when (step) {
        is SkillSyncStep.CreateDirectory -> runCatching { Files.createDirectories(step.path) }
            .fold({ StepOutcome.Success() }, { StepOutcome.Failure(it.message ?: "Failed to create ${step.path}") })

        is SkillSyncStep.BackupExisting -> {
            val backup = backupService.backup(
                step.agentId,
                context.skillId,
                step.path,
                context.backupRoot,
                context.operationId,
                step.representation,
                step.linkTarget,
                context.instanceKey,
            )
            if (backup != null) {
                StepOutcome.Success(backup)
            } else {
                StepOutcome.Failure("Nothing to back up at ${step.path} — target may have changed since preview.")
            }
        }

        is SkillSyncStep.RemoveExisting -> runCatching { DirectoryDeleter.deleteRecursively(step.path) }
            .fold(
                {
                    ownershipStore.remove(context.instanceKey, step.agentId)
                    StepOutcome.Success()
                },
                { StepOutcome.Failure(it.message ?: "Failed to remove ${step.path}") },
            )

        is SkillSyncStep.CreateLink -> {
            val strategy = linkStrategies[step.effectiveMode]
            if (strategy == null) {
                StepOutcome.Failure("No link strategy available for ${step.effectiveMode}")
            } else {
                toOutcome(strategy.createLink(step.source, step.target))
            }
        }

        is SkillSyncStep.CopySkill -> toOutcome(copyStrategy.createLink(step.source, step.target))

        is SkillSyncStep.VerifyFingerprint -> {
            val actual = fingerprint.calculate(step.path)
            when {
                actual == null -> StepOutcome.Failure("Could not fingerprint ${step.path} for verification")
                actual == step.expectedFingerprint -> StepOutcome.Success()
                else -> StepOutcome.Failure("Fingerprint mismatch after sync at ${step.path}")
            }
        }

        is SkillSyncStep.WriteMetadata -> {
            val lastFingerprint = fingerprint.calculate(step.target)
            ownershipStore.record(
                context.instanceKey,
                ManagedTarget(
                    step.agentId,
                    step.target.toString(),
                    step.requestedMode,
                    step.mode,
                    lastFingerprint,
                    context.operationId,
                ),
            )
            StepOutcome.Success()
        }
    }

    /**
     * Executes a planned `RemoveExisting` + `CreateLink`/`CopySkill` pair as one install-then-swap
     * so the live skill path is never deleted before the replacement exists beside it.
     */
    fun replaceExisting(
        remove: SkillSyncStep.RemoveExisting,
        install: SkillSyncStep,
        context: StepExecutionContext,
    ): StepOutcome {
        val targetPath = when (install) {
            is SkillSyncStep.CreateLink -> install.target
            is SkillSyncStep.CopySkill -> install.target
            else -> return StepOutcome.Failure("Cannot atomically replace with $install")
        }
        if (remove.path.toAbsolutePath().normalize() != targetPath.toAbsolutePath().normalize()) {
            return StepOutcome.Failure("RemoveExisting path ${remove.path} does not match install target $targetPath")
        }

        val result = AtomicPathReplace.replace(targetPath) { staging ->
            when (install) {
                is SkillSyncStep.CreateLink -> {
                    val strategy = linkStrategies[install.effectiveMode]
                        ?: return@replace LinkResult.Failure("No link strategy available for ${install.effectiveMode}")
                    strategy.createLink(install.source, staging)
                }
                is SkillSyncStep.CopySkill -> copyStrategy.createLink(install.source, staging)
                else -> LinkResult.Failure("Cannot atomically replace with $install")
            }
        }
        return when (result) {
            is LinkResult.Success -> {
                ownershipStore.remove(context.instanceKey, remove.agentId)
                StepOutcome.Success()
            }
            is LinkResult.Failure -> StepOutcome.Failure(result.reason)
        }
    }

    private fun toOutcome(result: LinkResult): StepOutcome = when (result) {
        is LinkResult.Success -> StepOutcome.Success()
        is LinkResult.Failure -> StepOutcome.Failure(result.reason)
    }

}
