package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings

object SkillSyncSettingsStateMapper {
    fun toSettings(state: SkillSyncSettingsState): SkillSyncSettings {
        if (state.schemaVersion != SkillSyncSettingsState.CURRENT_SCHEMA_VERSION) return SkillSyncSettings()
        val mode = runCatching { SkillSyncMode.valueOf(state.preferredSyncMode) }.getOrDefault(SkillSyncMode.SYMLINK)
        return SkillSyncSettings(
            preferredSyncMode = mode,
            backupBeforeReplacement = state.backupBeforeReplacement,
            manageExistingTargets = state.manageExistingTargets,
            reviewPlanBeforeApplying = state.reviewPlanBeforeApplying,
        )
    }

    fun toState(settings: SkillSyncSettings): SkillSyncSettingsState = SkillSyncSettingsState(
        preferredSyncMode = settings.preferredSyncMode.name,
        backupBeforeReplacement = settings.backupBeforeReplacement,
        manageExistingTargets = settings.manageExistingTargets,
        reviewPlanBeforeApplying = settings.reviewPlanBeforeApplying,
    )
}
