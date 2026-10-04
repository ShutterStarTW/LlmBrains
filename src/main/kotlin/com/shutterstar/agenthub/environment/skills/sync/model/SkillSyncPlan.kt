package com.shutterstar.agenthub.environment.skills.sync.model

import java.nio.file.Path

data class SkillSyncPlan(
    val operationId: String,
    val skillId: String,
    val canonicalPath: Path,
    val steps: List<SkillSyncStep>,
    val warnings: List<SyncWarning>,
)
