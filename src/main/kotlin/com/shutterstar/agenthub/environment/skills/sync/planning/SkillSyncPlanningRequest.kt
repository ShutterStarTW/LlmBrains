package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.Path

data class SkillSyncPlanningRequest(
    val operationId: String,
    val skillId: String,
    val canonicalPath: Path,
    val canonicalFingerprint: String?,
    val targets: List<ObservedSkillTarget>,
    val observedCanonicalFingerprint: String? = canonicalFingerprint,
    val instanceKey: SkillInstanceKey = SkillInstanceKey.legacy(skillId),
    /** Mirrors [com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings.backupBeforeReplacement]. */
    val backupBeforeReplacement: Boolean = true,
    /**
     * False for a promotion: the source is the agent's own copy that is about to become the shared
     * skill, so an agent that also reads the shared directory natively must still be observed as an
     * ordinary (identical, unmanaged) copy - not short-circuited to NATIVE, which would make it
     * look "not in the expected state" both when planning and when revalidating before execution.
     */
    val nativeShortCircuit: Boolean = true,
    /**
     * True for a promotion: the observed target is the very directory the user picked, which for a vendor-provided
     * skill is not `<agent skills root>/<name>` (e.g. `skills/.system/<name>`), so it is re-observed at that path.
     */
    val observedAtExactPath: Boolean = false,
)
