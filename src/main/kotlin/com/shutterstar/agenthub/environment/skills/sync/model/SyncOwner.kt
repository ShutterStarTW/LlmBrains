package com.shutterstar.agenthub.environment.skills.sync.model

/**
 * Spec §17: who appears to be managing a target, distinct from whether its *content* currently
 * matches canonical. [com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver]
 * only ever assigns [AGENTHUB] (a verified [com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget]
 * record), [MANUAL] (something occupies the path — a directory, symlink or junction — that
 * doesn't verify as AgentHub's), [NATIVE] (the agent reads the shared source directly - see
 * [com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.NATIVE] - so there is
 * no per-agent path for anyone to own), or [UNKNOWN] (nothing to attribute, or observation itself failed).
 * [EXTERNAL] is spec'd but deliberately unassigned by anything in this codebase today (§18: "may
 * detect external managers without controlling them") — there is no concrete third-party manager
 * signature (e.g. a `skillshare`/`skill-manager` marker file) to recognize yet, and fabricating one
 * without a real integration to test against would just be a guess dressed up as detection. The
 * value exists so a future concrete [com.shutterstar.agenthub.environment.skills.sync.SkillSyncEngine]-external
 * check has somewhere to report to without another enum migration.
 */
enum class SyncOwner {
    AGENTHUB,
    EXTERNAL,
    MANUAL,
    NATIVE,
    UNKNOWN,
}
