package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BulkMigrationOrchestratorTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `migrates promote then rediscover-then-share, and isolates a failing share from the rest`() {
        val claudeRoot = root.resolve("claude")
        val claudeSkillPath = writeSkillMd(claudeRoot.resolve("review"), "content")
        val codexRoot = root.resolve("codex")
        writeSkillMd(codexRoot.resolve("review"), "content")
        val cursorRoot = root.resolve("cursor")
        // No adapter registered for cursor below: sharing to it fails, must not affect the codex share.

        val staleSkill = AgentSkill(
            identity = SkillIdentity("skill-1"),
            name = "review",
            description = null,
            scope = SkillScope.GLOBAL,
            sources = listOf(
                SkillSource(agentId = "claude", path = claudeSkillPath.toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
                SkillSource(agentId = "codex", path = codexRoot.resolve("review").toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
                SkillSource(agentId = "cursor", path = cursorRoot.resolve("review").toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
            ),
            compatibleAgents = emptySet(),
            consistency = SkillConsistency.IDENTICAL,
        )
        val candidate = BulkMigrationDetector().detect(listOf(staleSkill)).single()
        val canonicalPath = root.resolve(".agents").resolve("skills").resolve("review")

        val facade = SkillSyncApplicationService(
            targets = mapOf(
                "claude" to Target(claudeRoot, "claude"),
                "codex" to Target(codexRoot, "codex"),
            ),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            rediscoverForMigration = { _, _ ->
                listOf(
                    staleSkill.copy(
                        sources = staleSkill.sources + SkillSource(
                            agentId = null,
                            path = canonicalPath.toString(),
                            scope = SkillScope.GLOBAL,
                            shared = true,
                            fingerprint = "fixture",
                        ),
                    ),
                )
            },
        )

        val outcome = facade.migrateBulkCandidates(listOf(candidate), SkillScope.GLOBAL, null).single()

        assertTrue(outcome.promoteSucceeded, "Promote message: ${outcome.promoteMessage}")
        assertTrue(Files.exists(canonicalPath.resolve("SKILL.md")), "Promote should create the canonical directory")
        assertEquals(
            setOf("codex" to true, "cursor" to false),
            outcome.shareResults.map { it.agentId to it.succeeded }.toSet(),
        )
        assertTrue(Files.exists(codexRoot.resolve("review").resolve("SKILL.md")), "codex share should have actually happened")
    }

    @Test
    fun `a failed promote skips sharing entirely and reports why`() {
        val claudeRoot = root.resolve("claude")
        // No source directory on disk: the promote's own fingerprint check fails immediately.
        val staleSkill = AgentSkill(
            identity = SkillIdentity("skill-1"),
            name = "review",
            description = null,
            scope = SkillScope.GLOBAL,
            sources = listOf(
                SkillSource(agentId = "claude", path = claudeRoot.resolve("review").toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
                SkillSource(agentId = "codex", path = root.resolve("codex/review").toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
            ),
            compatibleAgents = emptySet(),
            consistency = SkillConsistency.IDENTICAL,
        )
        val candidate = BulkMigrationDetector().detect(listOf(staleSkill)).single()
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(claudeRoot, "claude")),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService(),
            sharedSkillDirectory = SharedSkillProvider(root),
            rediscoverForMigration = { _, _ -> error("Must not rediscover after a failed promote") },
        )

        val outcome = facade.migrateBulkCandidates(listOf(candidate), SkillScope.GLOBAL, null).single()

        assertTrue(!outcome.promoteSucceeded)
        assertTrue(outcome.shareResults.isEmpty())
    }

    private class Target(
        private val root: Path,
        override val agentId: String,
    ) : SkillSyncTarget {
        override fun globalSkillDirectory(): Path = root
        override fun projectSkillDirectory(project: DiscoveredProject): Path = root
        override fun supportsLinkedSkills(): Boolean = false
    }
}
