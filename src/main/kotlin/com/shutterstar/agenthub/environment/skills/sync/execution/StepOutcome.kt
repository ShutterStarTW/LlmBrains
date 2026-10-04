package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.Path

internal sealed interface StepOutcome {
    data class Success(val backup: SkillBackup? = null) : StepOutcome

    data class Failure(val message: String) : StepOutcome
}

internal data class StepExecutionContext(
    val operationId: String,
    val skillId: String,
    val backupRoot: Path,
    val instanceKey: SkillInstanceKey = SkillInstanceKey.legacy(skillId),
)
