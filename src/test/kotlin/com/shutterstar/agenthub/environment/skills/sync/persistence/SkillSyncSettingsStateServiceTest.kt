package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkillSyncSettingsStateServiceTest {
    @Test
    fun `a fresh service returns the default settings`() {
        val service = SkillSyncSettingsStateService()

        assertEquals(SkillSyncSettings(), service.current())
    }

    @Test
    fun `update then current returns the same settings`() {
        val service = SkillSyncSettingsStateService()
        val settings = SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY, backupBeforeReplacement = false)

        service.update(settings)

        assertEquals(settings, service.current())
    }

    @Test
    fun `state loaded from persistence (simulating an IDE restart) serves queries without update ever being called`() {
        val settings = SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY, backupBeforeReplacement = false)
        val persisted = SkillSyncSettingsStateMapper.toState(settings)

        val service = SkillSyncSettingsStateService()
        service.loadState(persisted)

        assertEquals(settings, service.current())
    }
}
