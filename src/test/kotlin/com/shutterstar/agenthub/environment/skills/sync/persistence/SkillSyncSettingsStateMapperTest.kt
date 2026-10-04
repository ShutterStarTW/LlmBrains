package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkillSyncSettingsStateMapperTest {
    @Test
    fun `round-trip preserves every field`() {
        val settings = SkillSyncSettings(
            preferredSyncMode = SkillSyncMode.COPY,
            backupBeforeReplacement = false,
            manageExistingTargets = true,
        )

        val state = SkillSyncSettingsStateMapper.toState(settings)
        val decoded = SkillSyncSettingsStateMapper.toSettings(state)

        assertEquals(settings, decoded)
    }

    @Test
    fun `managing existing skills is off by default and for a state saved before the option existed`() {
        assertEquals(false, SkillSyncSettings().manageExistingTargets)
        assertEquals(false, SkillSyncSettingsStateMapper.toSettings(SkillSyncSettingsState()).manageExistingTargets)
    }

    @Test
    fun `an invalid mode string falls back to the default settings`() {
        val state = SkillSyncSettingsState(preferredSyncMode = "NOT_A_REAL_MODE")

        assertEquals(SkillSyncSettings(), SkillSyncSettingsStateMapper.toSettings(state))
    }

    @Test
    fun `a mismatched schema version falls back to the default settings`() {
        val state = SkillSyncSettingsState(schemaVersion = 999, preferredSyncMode = "COPY", backupBeforeReplacement = false)

        assertEquals(SkillSyncSettings(), SkillSyncSettingsStateMapper.toSettings(state))
    }
}
