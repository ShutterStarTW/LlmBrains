package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdditionalAgentSkillProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover Cline global and compatible project skill locations`() {
        writeSkill(temporaryDirectory.resolve(".cline/skills/global-review"), "global-review")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cline-project"))
        writeSkill(projectRoot.resolve(".cline/skills/native-review"), "native-review")
        writeSkill(projectRoot.resolve(".clinerules/skills/legacy-review"), "legacy-review")
        writeSkill(projectRoot.resolve(".claude/skills/compatible-review"), "compatible-review")

        val provider = ClineSkillProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals("global-review", global.name)
        assertEquals(SkillScope.GLOBAL, global.scope)
        assertEquals(setOf("native-review", "legacy-review", "compatible-review"), project.names())
        assertTrue(project.all { it.agentId == "cline" && it.scope == SkillScope.PROJECT })
    }

    @Test
    fun `should discover Kiro and Qwen global and project skills`() {
        writeSkill(temporaryDirectory.resolve(".kiro/skills/kiro-global"), "kiro-global")
        writeSkill(temporaryDirectory.resolve(".qwen/skills/qwen-global"), "qwen-global")
        writeSkill(temporaryDirectory.resolve(".grok/skills/grok-global"), "grok-global")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("shared-project"))
        writeSkill(projectRoot.resolve(".kiro/skills/kiro-project"), "kiro-project")
        writeSkill(projectRoot.resolve(".qwen/skills/qwen-project"), "qwen-project")
        writeSkill(projectRoot.resolve(".grok/skills/grok-project"), "grok-project")
        writeSkill(projectRoot.resolve("packages/app/.grok/skills/nested-grok"), "nested-grok")

        val kiro = KiroSkillProvider(temporaryDirectory)
        val qwen = QwenSkillProvider(temporaryDirectory)
        val grok = GrokSkillProvider(temporaryDirectory)

        assertEquals(setOf("kiro-global"), kiro.discoverGlobal().names())
        assertEquals(setOf("kiro-project"), kiro.discoverProject(project(projectRoot)).names())
        assertEquals(setOf("qwen-global"), qwen.discoverGlobal().names())
        assertEquals(setOf("qwen-project"), qwen.discoverProject(project(projectRoot)).names())
        assertEquals(setOf("grok-global"), grok.discoverGlobal().names())
        assertEquals(setOf("grok-project", "nested-grok"), grok.discoverProject(project(projectRoot)).names())
        assertTrue(grok.discoverProject(project(projectRoot)).all { it.projectName == "project" })
    }

    @Test
    fun `should tolerate missing additional agent skill directories`() {
        val missingProject = project(temporaryDirectory.resolve("missing"))

        assertTrue(ClineSkillProvider(temporaryDirectory).discoverGlobal().isEmpty())
        assertTrue(KiroSkillProvider(temporaryDirectory).discoverGlobal().isEmpty())
        assertTrue(QwenSkillProvider(temporaryDirectory).discoverGlobal().isEmpty())
        assertTrue(GrokSkillProvider(temporaryDirectory).discoverGlobal().isEmpty())
    }

    private fun writeSkill(directory: Path, name: String) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: $name\ndescription: Test skill\n---\nInstructions")
    }

    private fun List<SkillSourceRecord>.names(): Set<String> = mapTo(mutableSetOf()) { it.name }

    private fun project(root: Path): DiscoveredProject = DiscoveredProject(
        identity = ProjectIdentity("project", root.toString(), root.toString(), null),
        name = "project",
        path = root.toString(),
        gitRoot = root.toString(),
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
}
