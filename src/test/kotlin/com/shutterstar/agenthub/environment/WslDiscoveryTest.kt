package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.WslSupport
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.discovery.ClaudeMcpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.projectAt
import com.shutterstar.agenthub.projects.discovery.AgentProjectProviders
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.GitProjectResolver
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import com.shutterstar.agenthub.writeFile
import com.shutterstar.agenthub.writeSkill
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * WSL mode: the agents live in a distro, so discovery must read the distro's home - not the Windows user's. The distro
 * is simulated by a temporary directory standing in for the `\\wsl.localhost\<distro>` share (path separators are
 * the platform's own), so these run on any system.
 */
class WslDiscoveryTest {
    @TempDir
    lateinit var distro: Path

    @BeforeEach
    fun wslMode() {
        AgentRuntime.resetForTests()
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "Ubuntu")
        AgentRuntime.wslActive = { true }
        AgentRuntime.wslLookup = { AgentRuntime.WslEnvironment("Ubuntu", "/home/wsl-only-user", distro.toString()) }
    }

    @AfterEach
    fun restore() {
        AgentRuntime.resetForTests()
        WslSupport.settings = WslSupport.Settings()
    }

    private fun inHome(relative: String): Path = distro.resolve("home").resolve("wsl-only-user").resolve(relative)

    private fun hostMode() {
        AgentRuntime.wslActive = { false }
    }

    @Test
    fun `default providers read the distro home and the Windows home again after switching back`() {
        writeSkill(inHome(".claude/skills/wsl-only-skill"), "wsl-only-skill")

        assertEquals(listOf("wsl-only-skill"), ClaudeSkillProvider().discoverGlobal().map { it.name })

        hostMode()
        assertFalse(ClaudeSkillProvider().discoverGlobal().any { it.name == "wsl-only-skill" })
    }

    @Test
    fun `the discovery services pick the providers of the current runtime on every call`() {
        writeSkill(inHome(".agents/skills/shared-wsl-skill"), "shared-wsl-skill")
        writeFile(inHome(".claude.json"), """{"mcpServers":{"wsl-server":{"command":"echo"}}}""")
        val skills = SkillDiscoveryService()
        val mcp = McpDiscoveryService()

        assertTrue(skills.discoverGlobal().any { it.name == "shared-wsl-skill" })
        assertTrue(mcp.discoverGlobal().any { it.name == "wsl-server" })

        hostMode()
        assertFalse(skills.discoverGlobal().any { it.name == "shared-wsl-skill" })
        assertFalse(mcp.discoverGlobal().any { it.name == "wsl-server" })
    }

    @Test
    fun `the project providers are rebuilt for the runtime`() {
        val inWsl = AgentProjectProviders.all
        hostMode()
        val onHost = AgentProjectProviders.all

        assertEquals(AgentProjectProviders.agentIds, inWsl.map { it.agentId }.toSet())
        assertTrue(inWsl !== onHost)
    }

    @Test
    fun `a project given as a Linux path is opened through the share`() {
        Files.createDirectories(inHome("proj"))

        assertEquals(inHome("proj").toAbsolutePath().normalize(), ProjectPathResolver.resolveExistingRoot(projectAt("/home/wsl-only-user/proj")))
        assertNull(ProjectPathResolver.resolveExistingRoot(projectAt("/home/wsl-only-user/missing")))
    }

    @Test
    fun `Claude's per-project MCP entries match a Linux project path`() {
        Files.createDirectories(inHome("proj"))
        writeFile(inHome("proj/.mcp.json"), """{"mcpServers":{"shared-x":{"command":"a"}}}""")
        writeFile(
            inHome(".claude.json"),
            """{"projects":{"/home/wsl-only-user/proj":{"mcpServers":{"local-x":{"command":"b"}}},"/home/wsl-only-user/other":{"mcpServers":{"nope":{"command":"c"}}}}}""",
        )

        val names = ClaudeMcpProvider().discoverProject(projectAt("/home/wsl-only-user/proj")).map { it.name }.toSet()

        assertEquals(setOf("shared-x", "local-x"), names)
    }

    @Test
    fun `git information of a distro project comes from its files, in Linux paths`() {
        writeFile(inHome("proj/.git/HEAD"), "ref: refs/heads/feature/wsl\n")
        writeFile(inHome("proj/.git/config"), "[core]\n\tbare = false\n[remote \"origin\"]\n\turl = git@github.com:team/proj.git\n")
        Files.createDirectories(inHome("proj/src/pkg"))

        val info = GitProjectResolver().resolve("/home/wsl-only-user/proj/src/pkg")

        assertNotNull(info)
        assertEquals("/home/wsl-only-user/proj", info!!.root)
        assertEquals("git@github.com:team/proj.git", info.remote)
        assertEquals("feature/wsl", info.currentBranch)
    }

    @Test
    fun `a session of a distro project resolves to its git root and remote`() {
        writeFile(inHome("proj/.git/HEAD"), "ref: refs/heads/main\n")
        writeFile(inHome("proj/.git/config"), "[remote \"origin\"]\n\turl = https://github.com/team/proj.git\n")
        Files.createDirectories(inHome("proj/src"))

        val resolved = ProjectResolver().resolveProject(
            RawAgentProject("claude", "/home/wsl-only-user/proj/src", "session-1", null, null, null),
        )

        assertEquals("/home/wsl-only-user/proj", resolved.path)
        assertEquals("proj", resolved.name)
        assertEquals("github.com/team/proj", resolved.identity.gitRemote)
        assertEquals("main", resolved.currentBranch)
    }

    @Test
    fun `a tilde in a path an agent recorded is the distro home`() {
        assertEquals("/home/wsl-only-user/proj", ProjectResolver.normalizeFilesystemPath("~/proj"))
        assertEquals("/home/wsl-only-user", ProjectResolver.normalizeFilesystemPath("~"))
    }

    @Test
    fun `environment variables of the IDE process do not apply inside the distro`() {
        // Some variable holding a plain directory exists on every system: honoured on the host, ignored in WSL mode.
        val name = listOf("USERPROFILE", "HOME", "TEMP", "TMP", "TMPDIR").first { !System.getenv(it).isNullOrBlank() }
        val fallback = Path.of("distro-default")

        assertEquals(fallback, EnvHomeDirectorySupport.resolveFirst(name) { fallback })

        hostMode()
        assertEquals(Path.of(System.getenv(name)), EnvHomeDirectorySupport.resolveFirst(name) { fallback })
    }
}
