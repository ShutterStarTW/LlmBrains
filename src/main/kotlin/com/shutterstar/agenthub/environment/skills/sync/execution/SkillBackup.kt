package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import java.nio.file.Path
import java.time.Instant

data class SkillBackup(
    val id: String,
    val originalPath: Path,
    val backupPath: Path,
    val createdAt: Instant,
    val operationId: String,
    val representation: EffectiveSyncMode = EffectiveSyncMode.COPY,
    val linkTarget: Path? = null,
)
