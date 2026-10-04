package com.shutterstar.agenthub.storage

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateService
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SharedSkillSyncTest {
    @TempDir lateinit var directory: Path

    @Test fun `should undo and restore migrated backups from another service instance`() {
        listOf("undo", "restore").forEach { mode ->
            val root = directory.resolve(mode)
            val canonical = root.resolve("shared/review")
            val target = root.resolve("claude/review")
            listOf(canonical, target).forEach {
                Files.createDirectories(it)
                Files.writeString(it.resolve("SKILL.md"), "original content")
            }
            val skill = AgentSkill(SkillIdentity("review"), "review", null, SkillScope.GLOBAL,
                listOf(SkillSource(null, canonical.toString(), SkillScope.GLOBAL, true, "fixture", "Review")),
                emptySet(), SkillConsistency.SINGLE_SOURCE)
            val ownership = SkillOwnershipStateService()
            val audit = SkillSyncAuditStateService()
            val settings = SkillSyncSettingsStateService().apply {
                update(SkillSyncSettings(preferredSyncMode = SkillSyncMode.COPY, manageExistingTargets = true))
            }
            val adapter = object : SkillSyncTarget {
                override val agentId = "claude"
                override fun globalSkillDirectory(): Path = target.parent
                override fun projectSkillDirectory(project: DiscoveredProject): Path = target.parent
                override fun supportsLinkedSkills() = false
            }
            val system = root.resolve("system")
            val oldBackups = system.resolve("agenthub/skill-backups")
            val old = SkillSyncApplicationService(
                targets = mapOf("claude" to adapter), backupRoot = oldBackups,
                ownershipStore = ownership, auditTrail = audit, settings = settings,
                sharedSkillDirectory = SharedSkillProvider(root), mutationHome = null,
            )
            val prepared = old.prepareUpdateSharing(skill, emptySet(), setOf("claude"), SkillScope.GLOBAL, null)
            assertEquals(SyncOperationStatus.SUCCESS, old.execute(prepared).status)
            assertFalse(Files.exists(target))
            val config = root.resolve("config")
            Files.createDirectories(config.resolve("options"))
            mapOf("AgentHubSkillOwnership" to ownership.state, "AgentHubSkillSyncAudit" to audit.state,
                "AgentHubSkillSyncSettings" to settings.state).forEach { (name, state) ->
                val xml = XmlSerializer.serialize(state).apply { setName("component"); setAttribute("name", name) }
                Files.writeString(config.resolve("options/$name.xml"), JDOMUtil.writeElement(org.jdom.Element("application").addContent(xml)))
            }
            val home = AgentHubHome(root.resolve("home"))
            assertTrue(LegacyStateMigration(home, config, system).migrate())
            val fresh = SkillSyncApplicationService(
                targets = mapOf("claude" to adapter), backupRoot = home.backups(), mutationHome = home,
                ownershipStore = SkillOwnershipStateService(SharedXmlStore(home, "state/ownership.xml", SkillOwnershipState::class.java, ::SkillOwnershipState)),
                auditTrail = SkillSyncAuditStateService(SharedXmlStore(home, "state/audit.xml", SkillSyncAuditState::class.java, ::SkillSyncAuditState)),
                settings = SkillSyncSettingsStateService(SharedXmlStore(home, "state/sync-settings.xml", SkillSyncSettingsState::class.java, ::SkillSyncSettingsState)),
                sharedSkillDirectory = SharedSkillProvider(root),
            )
            if (mode == "undo") {
                val result = fresh.undoOperation(prepared.planResult.plan.operationId)
                assertTrue(result != null && result.errors.isEmpty(), result.toString())
            } else {
                val backup = fresh.restorableBackups(skill, SkillScope.GLOBAL, null).single()
                assertTrue(backup.backup.backupPath.startsWith(home.backups()))
                assertEquals(SyncOperationStatus.SUCCESS, fresh.executeRestoreBackup(backup).status)
            }
            assertEquals("original content", Files.readString(target.resolve("SKILL.md")))
            assertTrue(Files.exists(oldBackups), "Legacy backups remain available")
        }
    }
}
