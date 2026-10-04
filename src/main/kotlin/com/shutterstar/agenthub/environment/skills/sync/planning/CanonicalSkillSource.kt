package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.file.Path

data class CanonicalSkillSource(
    val skillId: String,
    val canonicalPath: Path,
    val scope: SkillScope,
    val fingerprint: String?,
)
