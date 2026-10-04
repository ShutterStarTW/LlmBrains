package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.openapi.project.Project
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateMapper
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateService
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.writeSkillMd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

class SkillMutationReviewPlanTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `plan review is on by default and survives a round trip through persisted state`() {
        assertTrue(SkillSyncSettings().reviewPlanBeforeApplying)
        assertTrue(SkillSyncSettingsStateMapper.toSettings(SkillSyncSettingsState()).reviewPlanBeforeApplying)
        val off = SkillSyncSettings(reviewPlanBeforeApplying = false)
        assertFalse(SkillSyncSettingsStateMapper.toSettings(SkillSyncSettingsStateMapper.toState(off)).reviewPlanBeforeApplying)
    }

    @Test
    fun `with review on the plan is shown and declining changes nothing`() {
        val run = run(review = true, approve = false)
        assertEquals(1, run.confirmations)
        assertFalse(Files.exists(run.targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `with review off the plan is applied without being shown`() {
        val run = run(review = false, approve = false)
        assertEquals(0, run.confirmations)
        assertTrue(Files.exists(run.targetPath.resolve("SKILL.md")), "the plan must still be applied")
    }

    @Test
    fun `a successful operation reports a status that may fade, progress messages are not problems`() {
        val run = run(review = false, approve = true)
        assertEquals("Synchronization completed" to false, run.statuses.last())
        assertTrue(run.statuses.none { it.second }, "no step of a successful share is a sticky problem: ${run.statuses}")
    }

    private class Run(val confirmations: Int, val targetPath: Path, val statuses: List<Pair<String, Boolean>>)

    private fun run(review: Boolean, approve: Boolean): Run {
        val canonical = writeSkillMd(root.resolve("shared/review"), "# Review\n")
        val targetRoot = root.resolve("claude")
        val facade = SkillSyncApplicationService(
            targets = mapOf("claude" to Target(targetRoot)),
            backupRoot = root.resolve("backups"),
            ownershipStore = SkillOwnershipStateService(),
            auditTrail = SkillSyncAuditStateService(),
            settings = SkillSyncSettingsStateService().apply { update(SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY, reviewPlanBeforeApplying = review)) },
            sharedSkillDirectory = SharedSkillProvider(root),
        )
        val source = SkillSource(null, canonical.toString(), SkillScope.GLOBAL, true, "fixture", "Review")
        val skill = AgentSkill(SkillIdentity("review"), "review", null, SkillScope.GLOBAL, listOf(source), emptySet(), SkillConsistency.SINGLE_SOURCE)
        val context = SkillBrowserContext(SkillScope.GLOBAL)
        val row = SkillOccurrenceRow("row", "review", skill, source, context, emptySet(), "Global")
        var confirmations = 0
        val statuses = mutableListOf<Pair<String, Boolean>>()
        val controller = SkillMutationController(
            project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
                if (method.name == "isDisposed") false else if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            } as Project,
            service = facade,
            executor = { it.run() },
            deliver = { it() },
            statusSink = { message, sticky -> statuses += message to sticky },
            refresh = {},
            reviewPlan = { facade.currentSettings().reviewPlanBeforeApplying },
            confirm = { confirmations++; approve },
        )
        controller.startSharing(row, "claude")
        return Run(confirmations, targetRoot.resolve("review"), statuses)
    }

    private class Target(private val root: Path) : SkillSyncTarget {
        override val agentId: String = "claude"
        override fun globalSkillDirectory(): Path = root
        override fun projectSkillDirectory(project: DiscoveredProject): Path = root
        override fun supportsLinkedSkills(): Boolean = false
    }
}
