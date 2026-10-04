package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SkillOwnershipStateMapperTest {
    @TempDir lateinit var root: Path

    @Test
    fun `same skill and agent remain isolated by physical context`() {
        // Built via .host(), like production code, so the contextPath is already normalized -
        // matching what a freshly computed key looks like after the decode-side normalization.
        val projectA = SkillInstanceKey.host("skill-1", SkillScope.PROJECT, root.resolve("a/canonical"), root.resolve("a"))
        val projectB = SkillInstanceKey.host("skill-1", SkillScope.PROJECT, root.resolve("b/canonical"), root.resolve("b"))
        val first = ManagedTarget("claude", "/projects/a/.claude/skills/review", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "a", "op-a")
        val second = ManagedTarget("claude", "/projects/b/.claude/skills/review", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "b", "op-b")

        var state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), projectA, first)
        state = SkillOwnershipStateMapper.withRecorded(state, projectB, second)

        assertEquals(first, SkillOwnershipStateMapper.managedTarget(state, projectA, "claude"))
        assertEquals(second, SkillOwnershipStateMapper.managedTarget(state, projectB, "claude"))
        assertNull(SkillOwnershipStateMapper.managedTarget(state, "skill-1", "claude"))
    }

    @Test
    fun `host and WSL runtime ownership remain isolated`() {
        val canonical = root.resolve("shared/review")
        val host = SkillInstanceKey.host("skill-1", SkillScope.GLOBAL, canonical, runtimeId = "host")
        val wsl = SkillInstanceKey.host("skill-1", SkillScope.GLOBAL, canonical, runtimeId = "wsl:Ubuntu")
        val hostTarget = ManagedTarget("claude", "C:/Users/me/.claude/skills/review", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "host")
        val wslTarget = ManagedTarget("claude", "/home/me/.claude/skills/review", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "wsl")

        var state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), host, hostTarget)
        state = SkillOwnershipStateMapper.withRecorded(state, wsl, wslTarget)

        assertEquals(hostTarget, SkillOwnershipStateMapper.managedTarget(state, host, "claude"))
        assertEquals(wslTarget, SkillOwnershipStateMapper.managedTarget(state, wsl, "claude"))
    }

    @Test
    fun `managedTargetsFor(key) still matches after the persisted contextPath comes back denormalized`() {
        // Reproduces what a real IDE restart does on Windows: the persisted contextPath round
        // trips through IntelliJ's $USER_HOME$-style macro collapse/expand, which reconstructs the
        // platform's own (original-case, backslash) rendering rather than the lowercase/forward
        // -slash form SkillInstanceKey.host() produces - this used to make the "Managed agents"
        // section (and Resync/Stop Sharing gating) come back empty after every restart.
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File.separatorChar == '\\')
        val freshKey = SkillInstanceKey.host("skill-1", SkillScope.PROJECT, root.resolve("a/canonical"), root.resolve("a"))
        val denormalizedContextPath = freshKey.contextPath.replace('/', '\\').uppercase()
        val state = SkillOwnershipState(
            entries = mutableListOf(
                SkillOwnershipEntryState(
                    skillId = "skill-1",
                    runtimeId = freshKey.runtimeId,
                    scope = freshKey.scope.name,
                    contextPath = denormalizedContextPath,
                    agentId = "claude",
                    path = root.resolve("a/.claude/skills/review").toString(),
                    requestedMode = SkillSyncMode.COPY.name,
                    effectiveMode = EffectiveSyncMode.COPY.name,
                ),
            ),
        )

        assertEquals(1, SkillOwnershipStateMapper.managedTargetsFor(state, freshKey).size)
    }

    @Test
    fun `round-trip preserves every field, including a null fingerprint`() {
        val target = ManagedTarget("claude", "/path/to/skill", SkillSyncMode.COPY, EffectiveSyncMode.COPY, null)

        val state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), "skill-1", target)
        val decoded = SkillOwnershipStateMapper.managedTarget(state, "skill-1", "claude")

        assertEquals(target, decoded)
    }

    @Test
    fun `withRecorded replaces an existing entry for the same skill and agent instead of duplicating`() {
        val first = ManagedTarget("claude", "/first", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp1")
        val second = ManagedTarget("claude", "/second", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp2")

        var state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), "skill-1", first)
        state = SkillOwnershipStateMapper.withRecorded(state, "skill-1", second)

        assertEquals(1, state.entries.size)
        assertEquals(second, SkillOwnershipStateMapper.managedTarget(state, "skill-1", "claude"))
    }

    @Test
    fun `withRemoved clears only the matching entry`() {
        val claudeTarget = ManagedTarget("claude", "/claude", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp")
        val codexTarget = ManagedTarget("codex", "/codex", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp")
        var state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), "skill-1", claudeTarget)
        state = SkillOwnershipStateMapper.withRecorded(state, "skill-1", codexTarget)

        state = SkillOwnershipStateMapper.withRemoved(state, "skill-1", "claude")

        assertNull(SkillOwnershipStateMapper.managedTarget(state, "skill-1", "claude"))
        assertEquals(codexTarget, SkillOwnershipStateMapper.managedTarget(state, "skill-1", "codex"))
    }

    @Test
    fun `a corrupted mode string is skipped rather than throwing`() {
        val state = SkillOwnershipState(
            entries = mutableListOf(
                SkillOwnershipEntryState(
                    skillId = "skill-1",
                    agentId = "claude",
                    path = "/path",
                    requestedMode = "NOT_A_REAL_MODE",
                    effectiveMode = "SYMLINK",
                    lastFingerprint = "fp",
                ),
            ),
        )

        assertNull(SkillOwnershipStateMapper.managedTarget(state, "skill-1", "claude"))
    }

    @Test
    fun `entries under a mismatched schema version are ignored`() {
        val target = ManagedTarget("claude", "/path", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp")
        val state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), "skill-1", target)
            .copy(schemaVersion = 999)

        assertNull(SkillOwnershipStateMapper.managedTarget(state, "skill-1", "claude"))
    }

    @Test
    fun `managedTargetsFor returns every entry for a skill, filtering out other skills and corrupt entries`() {
        val claudeTarget = ManagedTarget("claude", "/claude", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "fp")
        val codexTarget = ManagedTarget("codex", "/codex", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp")
        var state = SkillOwnershipStateMapper.withRecorded(SkillOwnershipState(), "skill-1", claudeTarget)
        state = SkillOwnershipStateMapper.withRecorded(state, "skill-1", codexTarget)
        state = SkillOwnershipStateMapper.withRecorded(state, "skill-2", claudeTarget)

        val result = SkillOwnershipStateMapper.managedTargetsFor(state, "skill-1")

        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf(claudeTarget, codexTarget)))
    }

    @Test
    fun `managedTargetsFor an unrecorded skill returns empty`() {
        assertTrue(SkillOwnershipStateMapper.managedTargetsFor(SkillOwnershipState(), "skill-1").isEmpty())
    }
}
