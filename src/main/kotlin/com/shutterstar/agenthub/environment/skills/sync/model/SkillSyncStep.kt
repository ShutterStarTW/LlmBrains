package com.shutterstar.agenthub.environment.skills.sync.model

import java.nio.file.Path

sealed interface SkillSyncStep {
    val agentId: String

    data class CreateDirectory(
        override val agentId: String,
        val path: Path,
    ) : SkillSyncStep

    data class BackupExisting(
        override val agentId: String,
        val path: Path,
        val representation: EffectiveSyncMode? = null,
        val linkTarget: Path? = null,
    ) : SkillSyncStep

    data class RemoveExisting(
        override val agentId: String,
        val path: Path,
    ) : SkillSyncStep

    data class CreateLink(
        override val agentId: String,
        val source: Path,
        val target: Path,
        val requestedMode: SkillSyncMode,
        val effectiveMode: EffectiveSyncMode,
    ) : SkillSyncStep

    data class CopySkill(
        override val agentId: String,
        val source: Path,
        val target: Path,
    ) : SkillSyncStep

    data class VerifyFingerprint(
        override val agentId: String,
        val path: Path,
        val expectedFingerprint: String,
    ) : SkillSyncStep

    data class WriteMetadata(
        override val agentId: String,
        val skillId: String,
        val target: Path,
        val mode: EffectiveSyncMode,
        val requestedMode: SkillSyncMode = if (mode == EffectiveSyncMode.COPY) SkillSyncMode.COPY else SkillSyncMode.SYMLINK,
    ) : SkillSyncStep
}
