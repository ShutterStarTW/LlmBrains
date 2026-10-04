package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SkillOwnershipStateServiceTest {
    @Test
    fun `record then managedTarget returns the same target`() {
        val service = SkillOwnershipStateService()
        val target = ManagedTarget("claude", "/path", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp")

        service.record("skill-1", target)

        assertEquals(target, service.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `record twice for the same pair replaces rather than duplicates`() {
        val service = SkillOwnershipStateService()
        service.record("skill-1", ManagedTarget("claude", "/first", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp1"))
        service.record("skill-1", ManagedTarget("claude", "/second", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp2"))

        assertEquals(1, service.state.entries.size)
        assertEquals("/second", service.managedTarget("skill-1", "claude")?.path)
    }

    @Test
    fun `remove clears the recorded target`() {
        val service = SkillOwnershipStateService()
        service.record("skill-1", ManagedTarget("claude", "/path", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp"))

        service.remove("skill-1", "claude")

        assertNull(service.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `state loaded from persistence (simulating an IDE restart) serves queries without record ever being called`() {
        val target = ManagedTarget("claude", "/path/to/skill", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp")
        val persisted = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), "skill-1", target)

        val service = SkillOwnershipStateService()
        service.loadState(persisted)

        assertEquals(target, service.managedTarget("skill-1", "claude"))
    }
}
