package com.shutterstar.agenthub.environment.skills.sync.ownership

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemorySyncOwnershipStoreTest {
    private val store = InMemorySyncOwnershipStore()

    @Test
    fun `record then lookup returns the same target`() {
        val target = ManagedTarget("claude", "/path/to/skill", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp")

        store.record("skill-1", target)

        assertEquals(target, store.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `lookup for an unrecorded pair returns null`() {
        assertNull(store.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `remove clears the recorded target`() {
        val target = ManagedTarget("claude", "/path/to/skill", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp")
        store.record("skill-1", target)

        store.remove("skill-1", "claude")

        assertNull(store.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `different skillId or agentId pairs never collide`() {
        val claudeTarget = ManagedTarget("claude", "/claude/path", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp1")
        val codexTarget = ManagedTarget("codex", "/codex/path", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp2")

        store.record("skill-1", claudeTarget)
        store.record("skill-1", codexTarget)
        store.record("skill-2", claudeTarget)

        assertEquals(claudeTarget, store.managedTarget("skill-1", "claude"))
        assertEquals(codexTarget, store.managedTarget("skill-1", "codex"))
        assertEquals(claudeTarget, store.managedTarget("skill-2", "claude"))
        assertNull(store.managedTarget("skill-2", "codex"))
    }

    @Test
    fun `managedTargetsFor returns every entry for a skill, filtering out other skills`() {
        val claudeTarget = ManagedTarget("claude", "/claude/path", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp1")
        val codexTarget = ManagedTarget("codex", "/codex/path", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp2")
        val otherSkillTarget = ManagedTarget("claude", "/other/path", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp3")
        store.record("skill-1", claudeTarget)
        store.record("skill-1", codexTarget)
        store.record("skill-2", otherSkillTarget)

        val result = store.managedTargetsFor("skill-1")

        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf(claudeTarget, codexTarget)))
    }

    @Test
    fun `managedTargetsFor an unrecorded skill returns empty`() {
        assertTrue(store.managedTargetsFor("skill-1").isEmpty())
    }
}
