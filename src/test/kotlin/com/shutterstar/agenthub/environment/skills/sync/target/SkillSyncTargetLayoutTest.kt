package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projectAt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * One table for every adapter's skill-directory layout: the native root (where AgentHub writes) and the
 * compatibility roots discovery also reads (`alternate*`, which must never include the native root).
 * All paths are relative to the user home / project root.
 */
class SkillSyncTargetLayoutTest {
    data class Layout(
        val agentId: String,
        val global: String,
        val project: String,
        val alternateGlobal: List<String> = emptyList(),
        val alternateProject: List<String> = emptyList(),
        val create: (home: Path) -> SkillSyncTarget,
    ) {
        override fun toString() = agentId
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    fun `global directory is the agent's native root`(layout: Layout, @TempDir home: Path) {
        val target = layout.create(home)

        assertEquals(layout.agentId, target.agentId)
        assertEquals(home.resolve(layout.global), target.globalSkillDirectory())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    fun `project directory is the agent's native root under the project`(layout: Layout, @TempDir home: Path) {
        val root = Files.createDirectories(home.resolve("project"))

        assertEquals(root.resolve(layout.project), layout.create(home).projectSkillDirectory(projectAt(root.toString())))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    fun `alternate directories cover exactly the compatibility roots, never the native one`(
        layout: Layout,
        @TempDir home: Path,
    ) {
        val target = layout.create(home)
        val root = Files.createDirectories(home.resolve("project"))
        val project = projectAt(root.toString())

        assertEquals(layout.alternateGlobal.map(home::resolve), target.alternateGlobalSkillDirectories())
        assertEquals(layout.alternateProject.map(root::resolve), target.alternateProjectSkillDirectories(project))
        assertTrue(target.globalSkillDirectory() !in target.alternateGlobalSkillDirectories())
        assertTrue(target.projectSkillDirectory(project) !in target.alternateProjectSkillDirectories(project))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    fun `a project without a path or git root has no skill directories`(layout: Layout, @TempDir home: Path) {
        val target = layout.create(home)
        val project = projectAt(path = null)

        assertNull(target.projectSkillDirectory(project))
        assertEquals(emptyList<Path>(), target.alternateProjectSkillDirectories(project))
    }

    @Test
    fun `cursor sync writes only into an existing project directory`(@TempDir home: Path) {
        assertNull(CursorSkillSyncTarget(home).projectSkillDirectory(projectAt(home.resolve("missing").toString())))
    }

    companion object {
        @JvmStatic
        fun layouts(): List<Layout> = listOf(
            Layout(
                "antigravity", ".gemini/config/skills", ".agent/skills",
                alternateGlobal = listOf(
                    ".gemini/antigravity-cli/skills",
                    ".gemini/skills",
                    ".gemini/antigravity/skills",
                    ".gemini/antigravity-cli/builtin/skills",
                    ".gemini/antigravity/builtin/skills",
                ),
                alternateProject = listOf("_agents/skills", "_agent/skills", ".gemini/skills"),
                create = ::AntigravitySkillSyncTarget,
            ),
            Layout("claude", ".claude/skills", ".claude/skills", create = ::ClaudeSkillSyncTarget),
            Layout(
                "cline", ".cline/skills", ".cline/skills",
                alternateProject = listOf(".clinerules/skills", ".claude/skills"),
                create = ::ClineSkillSyncTarget,
            ),
            Layout("codex", ".codex/skills", ".codex/skills", create = ::CodexSkillSyncTarget),
            Layout(
                "copilot", ".copilot/skills", ".github/skills",
                alternateProject = listOf(".claude/skills"),
                create = { CopilotSkillSyncTarget(it.resolve(".copilot")) },
            ),
            Layout(
                "cursor", ".cursor/skills", ".cursor/skills",
                alternateGlobal = listOf(".cursor/skills-cursor", ".claude/skills", ".codex/skills"),
                alternateProject = listOf(".claude/skills", ".codex/skills"),
                create = ::CursorSkillSyncTarget,
            ),
            Layout(
                "grok", ".grok/skills", ".grok/skills",
                alternateGlobal = listOf(".claude/skills", ".cursor/skills"),
                alternateProject = listOf(".claude/skills", ".cursor/skills"),
                create = ::GrokSkillSyncTarget,
            ),
            Layout("junie", ".junie/skills", ".junie/skills", create = ::JunieSkillSyncTarget),
            Layout("kimi", ".kimi-code/skills", ".kimi-code/skills", create = ::KimiSkillSyncTarget),
            Layout(
                "kilo", ".kilo/skills", ".kilo/skills",
                alternateGlobal = listOf(
                    ".config/kilo/skills", ".kilocode/skills", ".claude/skills",
                    ".kilo/skill", ".config/kilo/skill", ".kilocode/skill",
                ),
                alternateProject = listOf(".kilocode/skills", ".claude/skills", ".kilo/skill", ".kilocode/skill"),
                create = ::KiloSkillSyncTarget,
            ),
            Layout("kiro", ".kiro/skills", ".kiro/skills", create = ::KiroSkillSyncTarget),
            Layout(
                "mimo", ".config/mimocode/skills", ".mimocode/skills",
                alternateGlobal = listOf(".config/mimocode/skill"),
                alternateProject = listOf(".mimocode/skill"),
                create = ::MimoSkillSyncTarget,
            ),
            Layout(
                "omp", ".omp/agent/skills", ".omp/skills",
                alternateProject = listOf(".claude/skills", ".codex/skills"),
                create = ::OmpSkillSyncTarget,
            ),
            Layout(
                "opencode", ".config/opencode/skills", ".opencode/skills",
                alternateGlobal = listOf(".claude/skills", ".opencode/skills", ".config/opencode/skill", ".opencode/skill"),
                alternateProject = listOf(".claude/skills", ".opencode/skill"),
                create = ::OpenCodeSkillSyncTarget,
            ),
            Layout("qwen", ".qwen/skills", ".qwen/skills", create = ::QwenSkillSyncTarget),
            Layout("vibe", ".vibe/skills", ".vibe/skills", create = ::VibeSkillSyncTarget),
        )
    }
}
