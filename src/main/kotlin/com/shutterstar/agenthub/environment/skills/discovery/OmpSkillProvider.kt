package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.OmpHomeSupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Oh My Pi skills (docs: `skills.md`): `skills/<name>/SKILL.md` one level under the native agent directory
 * (`~/.omp/agent`, profile- and `PI_CODING_AGENT_DIR`-aware), the auto-learned `managed-skills`, and per project the
 * `.omp/skills` plus the foreign project roots `.claude/skills` and `.codex/skills`, which OMP loads by default.
 * The shared `.agents/skills` root is the `SharedSkillProvider`'s. Foreign *user-level* roots are opt-in in OMP and not read;
 * skillshare/plugin packages and `skills.customDirectories` are not modelled. OMP does not descend into nested groups
 * below `skills/<name>`; this scanner may list them.
 */
class OmpSkillProvider(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val agentDirectory = OmpHomeSupport.agentDirectory(userHome)

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        return listOf(agentDirectory.resolve(SKILLS_DIRECTORY), agentDirectory.resolve(MANAGED_SKILLS_DIRECTORY)).flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.GLOBAL,
                shared = false,
                projectName = null,
                requireValidMetadata = true,
                budget = budget,
            )
        }.distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val budget = scanner.newBudget()
        return listOf(OMP_DIRECTORY, CLAUDE_DIRECTORY, CODEX_DIRECTORY).flatMap { ownerDirectory ->
            scanner.discoverNestedProjectSkills(
                projectRoot = projectRoot,
                ownerDirectoryName = ownerDirectory,
                agentId = agentId,
                shared = false,
                projectName = project.name,
                requireValidMetadata = true,
                budget = budget,
            )
        }.distinctBy { it.path }
    }

    private companion object {
        const val AGENT_ID = "omp"
        const val OMP_DIRECTORY = ".omp"
        const val CLAUDE_DIRECTORY = ".claude"
        const val CODEX_DIRECTORY = ".codex"
        const val SKILLS_DIRECTORY = "skills"
        const val MANAGED_SKILLS_DIRECTORY = "managed-skills"
    }
}
