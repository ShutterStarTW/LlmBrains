package com.shutterstar.agenthub.storage

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipEntryState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LegacyStateMigrationTest {
    @TempDir lateinit var directory: Path

    private fun legacy(config: Path, name: String, state: Any): Path {
        Files.createDirectories(config.resolve("options"))
        val element = XmlSerializer.serialize(state).apply {
            setName("component")
            setAttribute("name", name)
        }
        val file = config.resolve("options/$name.xml")
        Files.writeString(file, JDOMUtil.writeElement(org.jdom.Element("application").addContent(element)))
        return file
    }

    private fun ownership(home: AgentHubHome) = SharedXmlStore(
        home, "state/ownership.xml", SkillOwnershipState::class.java, ::SkillOwnershipState,
        statIntervalMillis = 0,
    )

    @Test fun `should import once preserve old files and copy backups without overwriting`() {
        val config = directory.resolve("idea")
        val system = directory.resolve("system")
        val home = AgentHubHome(directory.resolve("home"))
        val old = legacy(config, "AgentHubSkillOwnership", SkillOwnershipState(entries = mutableListOf(
            SkillOwnershipEntryState(skillId = "writing", agentId = "claude", path = "\$USER_HOME\$/skills/writing", recordedAtEpochMillis = 100),
        )))
        val original = Files.readString(old)
        val backup = system.resolve("agenthub/skill-backups/operation/content/SKILL.md")
        Files.createDirectories(backup.parent)
        Files.writeString(backup, "original skill")
        val migration = LegacyStateMigration(home, config, system, directory.resolve("user"))
        assertTrue(migration.migrate())
        assertEquals(directory.resolve("user/skills/writing").toString().replace('\\', '/'),
            ownership(home).snapshot().entries.single().path.replace('\\', '/'))
        assertEquals("original skill", Files.readString(home.backups().resolve("operation/content/SKILL.md")))
        assertFalse(migration.migrate())
        assertEquals(original, Files.readString(old))
        assertEquals("original skill", Files.readString(backup))
        Files.writeString(backup, "changed legacy")
        assertTrue(LegacyStateMigration(home, directory.resolve("other-ide"), system).migrate())
        assertEquals("original skill", Files.readString(home.backups().resolve("operation/content/SKILL.md")))
    }

    @Test fun `should merge newest ownership keep shared settings and honor removals`() {
        val home = AgentHubHome(directory.resolve("home"))
        val entry = SkillOwnershipEntryState(skillId = "a", agentId = "claude", recordedAtEpochMillis = 200, path = "new")
        ownership(home).update { it.copy(entries = mutableListOf(entry), removedEntries = mutableListOf(
            SkillOwnershipEntryState(skillId = "removed", agentId = "codex", recordedAtEpochMillis = 300),
        )) }
        val settings = SharedXmlStore(home, "state/sync-settings.xml", SkillSyncSettingsState::class.java, ::SkillSyncSettingsState)
        settings.update { it.copy(preferredSyncMode = "COPY") }
        val config = directory.resolve("ide")
        legacy(config, "AgentHubSkillOwnership", SkillOwnershipState(entries = mutableListOf(
            entry.copy(recordedAtEpochMillis = 100, path = "old"),
            SkillOwnershipEntryState(skillId = "removed", agentId = "codex", recordedAtEpochMillis = 100),
            SkillOwnershipEntryState(skillId = "additional", agentId = "codex", recordedAtEpochMillis = 400),
        )))
        legacy(config, "AgentHubSkillSyncSettings", SkillSyncSettingsState(preferredSyncMode = "SYMLINK"))
        assertTrue(LegacyStateMigration(home, config, directory.resolve("system")).migrate())
        assertEquals(setOf("a", "additional"), ownership(home).snapshot().entries.map { it.skillId }.toSet())
        assertEquals("new", ownership(home).snapshot().entries.first { it.skillId == "a" }.path)
        assertEquals("COPY", settings.snapshot().preferredSyncMode)
    }

    @Test fun `should leave failed migration unmarked and retry after fixing legacy XML`() {
        val home = AgentHubHome(directory.resolve("home"))
        val config = directory.resolve("ide")
        val file = legacy(config, "AgentHubSkillSyncSettings", SkillSyncSettingsState(preferredSyncMode = "COPY"))
        Files.writeString(file, "<broken")
        val migration = LegacyStateMigration(home, config, directory.resolve("system"))
        assertThrows(Exception::class.java) { migration.migrate() }
        assertFalse(Files.exists(home.root!!.resolve("migration.json")))
        legacy(config, "AgentHubSkillSyncSettings", SkillSyncSettingsState(preferredSyncMode = "COPY"))
        assertTrue(migration.migrate())
        assertEquals("COPY", SharedXmlStore(home, "state/sync-settings.xml", SkillSyncSettingsState::class.java, ::SkillSyncSettingsState).snapshot().preferredSyncMode)
    }
}
