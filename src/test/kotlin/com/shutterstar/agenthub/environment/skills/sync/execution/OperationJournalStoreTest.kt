package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OperationJournalStoreTest {
    @TempDir
    lateinit var backupRoot: Path

    @Test
    fun `round-trips reversal steps, verifications and a mix of present and absent managed targets`() {
        val journal = PersistedOperationJournal(
            operationId = "op-1",
            skillId = "skill-1",
            instanceKey = SkillInstanceKey("host", SkillScope.PROJECT, "/repo/project", "skill-1"),
            canonicalPath = Path.of("/shared/skills/review"),
            reversalSteps = listOf(
                PersistedReversalStep("claude", PersistedStepKind.CREATE_LINK, "/agents/claude/skills/review;v2", requiresBackup = true),
                PersistedReversalStep("codex", PersistedStepKind.REMOVE_EXISTING, "/agents/codex/skills/review|old", requiresBackup = true),
            ),
            verifications = listOf(
                PersistedVerification("claude", "/agents/claude/skills/review;v2", "fingerprint|abc"),
            ),
            previousManagedTargets = mapOf(
                "claude" to ManagedTarget("claude", "/agents/claude/skills/review;v2", SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "old|fingerprint", "op;0"),
                "codex" to null,
            ),
        )

        OperationJournalStore.write(journal, backupRoot)
        val loaded = OperationJournalStore.read(backupRoot, "op-1")

        assertEquals(journal, loaded)
    }

    @Test
    fun `reading a journal that was never written returns null`() {
        assertNull(OperationJournalStore.read(backupRoot, "never-written"))
    }
}
