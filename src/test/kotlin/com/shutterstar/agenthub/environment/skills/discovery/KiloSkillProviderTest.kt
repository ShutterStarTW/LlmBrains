package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.writeSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KiloSkillProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global skills from every Kilo config directory and the Claude compatibility root`() {
        writeSkill(temporaryDirectory.resolve(".kilo/skills/from-kilo"), "from-kilo")
        writeSkill(temporaryDirectory.resolve(".kilocode/skills/from-kilocode"), "from-kilocode")
        writeSkill(temporaryDirectory.resolve(".config/kilo/skills/from-config"), "from-config")
        writeSkill(temporaryDirectory.resolve(".claude/skills/from-claude"), "from-claude")
        writeSkill(temporaryDirectory.resolve(".config/opencode/skills/opencode-only"), "opencode-only")

        val records = KiloSkillProvider(temporaryDirectory).discoverGlobal()

        assertEquals(setOf("from-kilo", "from-kilocode", "from-config", "from-claude"), records.map { it.name }.toSet())
        assertTrue(records.all { it.agentId == "kilo" && it.scope == SkillScope.GLOBAL && !it.shared })
    }

    @Test
    fun `should discover project skills in kilo and kilocode directories with the project name`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("project"))
        writeSkill(root.resolve(".kilo/skills/project-skill"), "project-skill")
        writeSkill(root.resolve(".kilocode/skills/legacy-skill"), "legacy-skill")
        writeSkill(root.resolve(".claude/skills/claude-skill"), "claude-skill")
        writeSkill(root.resolve(".opencode/skills/opencode-skill"), "opencode-skill")

        val records = KiloSkillProvider(temporaryDirectory).discoverProject(project(root))

        assertEquals(setOf("project-skill", "legacy-skill", "claude-skill"), records.map { it.name }.toSet())
        assertTrue(records.all { it.scope == SkillScope.PROJECT && it.projectName == "project" })
    }

    @Test
    fun `should also accept the singular skill folder in the Kilo config directories but not in the Claude root`() {
        writeSkill(temporaryDirectory.resolve(".kilo/skill/singular-kilo"), "singular-kilo")
        writeSkill(temporaryDirectory.resolve(".config/kilo/skill/singular-config"), "singular-config")
        writeSkill(temporaryDirectory.resolve(".claude/skill/singular-claude"), "singular-claude")
        val root = Files.createDirectories(temporaryDirectory.resolve("project"))
        writeSkill(root.resolve(".kilocode/skill/singular-project"), "singular-project")
        writeSkill(root.resolve(".claude/skill/singular-project-claude"), "singular-project-claude")

        val provider = KiloSkillProvider(temporaryDirectory)

        assertEquals(setOf("singular-kilo", "singular-config"), provider.discoverGlobal().map { it.name }.toSet())
        assertEquals(setOf("singular-project"), provider.discoverProject(project(root)).map { it.name }.toSet())
    }
}
