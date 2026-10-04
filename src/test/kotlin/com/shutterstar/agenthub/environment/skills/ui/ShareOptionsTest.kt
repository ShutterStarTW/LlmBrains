package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ShareOptionsTest {
    private fun observed(agentId: String, status: SkillTargetStatus, managed: Boolean = false) =
        ObservedSkillTarget(agentId, Path.of("/skills/$agentId/review"), status, ownershipVerified = managed)

    private fun option(options: List<ShareOption>, agentId: String) = options.single { it.agentId == agentId }

    @Test fun `a managed link or copy is shared and removable`() {
        val options = ShareOptions.of(
            listOf("claude", "codex"),
            listOf(observed("claude", SkillTargetStatus.LINKED, managed = true), observed("codex", SkillTargetStatus.COPIED, managed = true)),
            managedIds = setOf("claude", "codex"),
        )

        assertTrue(options.all { it.shared && it.removable })
    }

    @Test fun `a link or copy AgentHub has no record of still counts as shared, but can't be removed`() {
        // Made by an earlier session (its ownership records were never persisted) or by hand.
        val options = ShareOptions.of(
            listOf("claude", "codex"),
            listOf(observed("claude", SkillTargetStatus.LINKED), observed("codex", SkillTargetStatus.IDENTICAL_UNMANAGED)),
            managedIds = emptySet(),
        )

        assertEquals(ShareOption("claude", shared = true, removable = false), option(options, "claude"))
        assertEquals(ShareOption("codex", shared = true, removable = false), option(options, "codex"))
    }

    @Test fun `an agent without the skill, or with a different or broken one, is not shared`() {
        val options = ShareOptions.of(
            listOf("claude", "codex", "cursor", "kiro"),
            listOf(
                observed("claude", SkillTargetStatus.NOT_AVAILABLE),
                observed("codex", SkillTargetStatus.DIFFERENT),
                observed("cursor", SkillTargetStatus.BROKEN_LINK),
            ),
            managedIds = emptySet(),
        )

        assertTrue(options.none { it.shared }, "$options")
        assertTrue(options.none { it.removable })
        assertFalse(option(options, "kiro").shared, "no observation at all")
    }

    @Test fun `an agent AgentHub manages but that no longer looks shared stays shared but locked`() {
        // A recorded managed target whose link broke: unchecking it can't plan a removal.
        val options = ShareOptions.of(
            listOf("claude"),
            listOf(observed("claude", SkillTargetStatus.BROKEN_LINK, managed = true)),
            managedIds = setOf("claude"),
        )

        assertEquals(ShareOption("claude", shared = true, removable = false), options.single())
    }

    @Test fun `the option list follows the requested targets, not the observations`() {
        val options = ShareOptions.of(
            listOf("codex", "claude"),
            listOf(observed("claude", SkillTargetStatus.LINKED, managed = true), observed("other", SkillTargetStatus.LINKED, managed = true)),
            managedIds = emptySet(),
        )

        assertEquals(listOf("codex", "claude"), options.map { it.agentId })
    }

    @Test fun `with manage existing on, an unmanaged link or identical copy becomes removable`() {
        val options = ShareOptions.of(
            listOf("claude", "codex"),
            listOf(observed("claude", SkillTargetStatus.LINKED), observed("codex", SkillTargetStatus.IDENTICAL_UNMANAGED)),
            managedIds = emptySet(),
            manageExisting = true,
        )

        assertTrue(options.all { it.shared && it.removable }, "$options")
    }

    @Test fun `with manage existing on, a different or broken target is still neither shared nor removable`() {
        val options = ShareOptions.of(
            listOf("claude", "codex"),
            listOf(observed("claude", SkillTargetStatus.DIFFERENT), observed("codex", SkillTargetStatus.BROKEN_LINK)),
            managedIds = emptySet(),
            manageExisting = true,
        )

        assertTrue(options.none { it.shared || it.removable }, "$options")
    }

    @Test fun `manage existing off keeps unmanaged sharing locked`() {
        val options = ShareOptions.of(
            listOf("claude"),
            listOf(observed("claude", SkillTargetStatus.LINKED)),
            managedIds = emptySet(),
            manageExisting = false,
        )

        assertEquals(ShareOption("claude", shared = true, removable = false), options.single())
    }

    @Test fun `an agent that reads the shared source directly (NATIVE) is always shared and never removable`() {
        val options = ShareOptions.of(
            listOf("codex"),
            listOf(observed("codex", SkillTargetStatus.NATIVE)),
            managedIds = emptySet(),
        )

        assertEquals(ShareOption("codex", shared = true, removable = false), options.single())
    }

    @Test fun `manage existing on still can't remove a NATIVE target - there is nothing on disk to remove`() {
        val options = ShareOptions.of(
            listOf("codex"),
            listOf(observed("codex", SkillTargetStatus.NATIVE)),
            managedIds = emptySet(),
            manageExisting = true,
        )

        assertEquals(ShareOption("codex", shared = true, removable = false), options.single())
    }
}
