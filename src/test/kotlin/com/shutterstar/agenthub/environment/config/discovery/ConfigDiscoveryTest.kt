package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.config.model.AgentConfigSource
import com.shutterstar.agenthub.environment.config.model.ConfigFormat
import com.shutterstar.agenthub.environment.config.model.ConfigHighlight
import com.shutterstar.agenthub.environment.config.model.ConfigKind
import com.shutterstar.agenthub.environment.config.model.ConfigRisk
import com.shutterstar.agenthub.environment.config.model.ConfigScope
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.model.visibleTo
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexConfigHighlightState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexProjectState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexState
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexStateMapper
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.ui.EnvironmentUiModel
import com.shutterstar.agenthub.environment.ui.ScopeFilter
import com.shutterstar.agenthub.environment.ui.SkillFilter
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ConfigDiscoveryTest {
    @TempDir
    lateinit var home: Path

    private fun providers(): List<ConfigProvider> = listOf(
        AntigravityConfigProvider(home), ClaudeConfigProvider(home), ClineConfigProvider(home), CodexConfigProvider(home),
        CopilotConfigProvider(home), CursorConfigProvider(home), GrokConfigProvider(home), KiroConfigProvider(home),
        OpenCodeConfigProvider(home), QwenConfigProvider(home),
    )

    private val globalPaths = listOf(
        ".gemini/antigravity-cli/settings.json", ".claude/settings.json", ".cline/data/globalState.json", ".codex/config.toml",
        ".copilot/settings.json", ".cursor/cli-config.json", ".grok/config.toml", ".kiro/settings/cli.json",
        ".config/opencode/opencode.jsonc", ".qwen/settings.json",
    )

    @Test
    fun `should inventory only exact settings paths for every provider`() {
        val all = providers()
        all.forEachIndexed { index, provider ->
            assertTrue(provider.discoverGlobal().isEmpty(), provider.agentId)
            val path = write(globalPaths[index], if (globalPaths[index].endsWith("toml")) "model = \"gpt-5\"" else "{}")
            val source = provider.discoverGlobal().single()
            assertEquals(provider.agentId, source.agentId)
            assertEquals(path.toString(), source.path)
            assertEquals(ConfigScope.GLOBAL, source.scope)
            assertEquals(Files.size(path), source.sizeBytes)
            assertTrue(source.modifiedAtEpochMillis > 0)
            assertTrue(AgentCapabilityRegistry.capabilitiesFor(provider.agentId).supportsConfig)
        }
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("amp").supportsConfig)
        listOf(".codex/auth.json", ".qwen/oauth_creds.json", ".cline/data/secrets.json", ".cline/data/settings/providers.json", ".copilot/config.json")
            .forEach { write(it, "{\"token\":\"$SECRET\"}") }
        assertEquals(10, all.sumOf { it.discoverGlobal().size })
    }

    @Test
    fun `should inventory the antigravity user config next to its settings`() {
        write(".gemini/antigravity-cli/settings.json", "{}")
        write(".gemini/config/config.json", "{\"userSettings\":{}}")
        write(".gemini/settings.json", "{}") // Gemini CLI's own file, not Antigravity's

        val paths = AntigravityConfigProvider(home).discoverGlobal().map { Path.of(it.path).fileName.toString() }

        assertEquals(listOf("settings.json", "config.json"), paths)
    }

    @Test
    fun `should inventory documented settings files added after the web audit but never provider secrets`() {
        write(".cline/data/settings/global-settings.json", "{}")
        write(".cline/data/settings/providers.json", "{\"token\":\"$SECRET\"}")
        write(".config/opencode/tui.json", "{}")
        write(".copilot/lsp-config.json", "{}")
        write(".gemini/antigravity-cli/keybindings.json", "{}")

        fun names(provider: ConfigProvider) = provider.discoverGlobal().map { Path.of(it.path).fileName.toString() }

        assertEquals(listOf("global-settings.json"), names(ClineConfigProvider(home)))
        assertEquals(listOf("tui.json"), names(OpenCodeConfigProvider(home)))
        assertEquals(listOf("lsp-config.json"), names(CopilotConfigProvider(home)))
        assertEquals(listOf("keybindings.json"), names(AntigravityConfigProvider(home)))
    }

    @Test
    fun `should retain empty malformed and oversized files as inventory for every provider`() {
        providers().forEachIndexed { index, provider ->
            val path = write(globalPaths[index], "")
            assertTrue(provider.discoverGlobal().single().highlights.isEmpty())
            Files.writeString(path, "{broken [invalid")
            assertTrue(provider.discoverGlobal().single().highlights.isEmpty())
            Files.writeString(path, "x".repeat(FileConfigProvider.MAX_CONTENT_BYTES + 1))
            val source = provider.discoverGlobal().single()
            assertTrue(source.highlights.isEmpty())
            assertEquals((FileConfigProvider.MAX_CONTENT_BYTES + 1).toLong(), source.sizeBytes)
        }
    }

    @Test
    fun `should scan nested project settings with exclusions and depth bound`() {
        val root = Files.createDirectories(home.resolve("project"))
        val relative = listOf(".claude/settings.local.json", ".codex/config.toml", ".cursor/cli.json", ".grok/config.toml",
            ".grok/settings.json", ".qwen/settings.json", "opencode.jsonc", "tui.json", ".github/copilot/settings.local.json", ".agents/hooks.json")
        relative.forEach { name ->
            write("project/$name", "{}")
            write("project/packages/app/$name", "{}")
            write("project/node_modules/vendor/$name", "{}")
            write("project/vendor/lib/$name", "{}")
            write("project/" + "deep/".repeat(10) + name, "{}")
        }
        val discovered = ConfigDiscoveryService(providers()).discoverProject(project(root))
        assertEquals(20, discovered.size)
        assertTrue(discovered.any { it.agentId == "grok" && it.path.endsWith("settings.json") })
        assertTrue(discovered.all { it.scope == ConfigScope.PROJECT && it.projectName == "Fixture" })
        assertFalse(discovered.any { it.path.contains("node_modules") || it.path.contains("vendor") || it.path.contains("deep") })
        assertEquals(ConfigKind.PERMISSIONS, discovered.first { it.agentId == "cursor" }.kind)
    }

    @Test
    fun `should exclude linked config files and directories when filesystem supports symlinks`() {
        val target = write("outside/settings.json", "{\"model\":\"gpt-5\"}")
        val root = Files.createDirectories(home.resolve("project"))
        val configDir = Files.createDirectories(root.resolve(".claude"))
        if (runCatching { Files.createSymbolicLink(configDir.resolve("settings.json"), target) }.isFailure) return
        Files.createSymbolicLink(root.resolve("linked"), target.parent)
        assertTrue(ClaudeConfigProvider(home).discoverProject(project(root)).isEmpty())
    }

    @Test
    fun `should expose allowlisted highlights and report risks without secrets`() {
        write(".claude/settings.json", """{"model":"claude-sonnet-4-5","permissions":{"defaultMode":"bypassPermissions","allow":["$SECRET"]},"env":{"API_KEY":"$SECRET"},"hooks":{"PreToolUse":[{"command":"$SECRET"}]},"enabledPlugins":{"$SECRET":true},"unknown":"$SECRET"}""")
        write(".codex/config.toml", """model = "gpt-5"
approval_policy = "never"
sandbox_mode = "danger-full-access"
[mcp_servers.secret]
url = "https://$SECRET.invalid"
[profiles.secret]
model = "$SECRET"
""")
        write(".grok/config.toml", """[ui]
permission_mode = "always-approve"
[sandbox]
profile = "off"
""")
        write(".gemini/antigravity-cli/settings.json", """{"toolPermission":"always-proceed"}""")
        val service = ConfigDiscoveryService(providers())
        val (configs, warnings) = service.discoverGlobalRecordsWithWarnings()
        assertEquals(6, warnings.size)
        assertTrue(configs.first { it.agentId == "claude" }.highlights.any { it.key == "hooks" && it.value == "1" })
        assertTrue(configs.first { it.agentId == "codex" }.highlights.any { it.key == "model" && it.value == "gpt-5" })
        assertFalse((configs.toString() + warnings).contains(SECRET))
        assertTrue(warnings.all { it.capability == "config" && it.message.contains("session overrides") })
        val environment = environment(configs).copy(warnings = warnings)
        val state = EnvironmentIndexStateMapper.encode(mapOf("fixture" to environment), Instant.EPOCH)
        val rows = EnvironmentUiModel.comparison(environment) { it }
        assertFalse((state.toString() + rows + rows.rows.flatMap { EnvironmentUiModel.detailLines(it) }).contains(SECRET))
    }

    @Test
    fun `should sanitize highlights on persistence and UI boundaries including forged labels`() {
        val source = AgentConfigSource("codex", "/fixture/config.toml", ConfigScope.GLOBAL, ConfigKind.SETTINGS, ConfigFormat.TOML,
            highlights = listOf(ConfigHighlight("unknown", SECRET, SECRET), ConfigHighlight("model", SECRET, SECRET), ConfigHighlight("approval_policy", SECRET, "never", ConfigRisk.NONE)))
        val env = environment(listOf(source))
        val state = EnvironmentIndexStateMapper.encode(mapOf("fixture" to env), Instant.EPOCH)
        assertFalse(state.toString().contains(SECRET))
        state.projects.single().configs.single().highlights += EnvironmentIndexConfigHighlightState("token", SECRET)
        val restored = EnvironmentIndexStateMapper.decode(state).getValue("fixture")
        assertEquals(listOf(ConfigHighlight("approval_policy", "Approval policy", "never", ConfigRisk.WARNING)), restored.configs.single().highlights)
        val comparison = EnvironmentUiModel.comparison(env) { it }
        assertFalse(EnvironmentUiModel.detailLines(comparison.rows.single()).toString().contains(SECRET))
        assertTrue(EnvironmentIndexStateMapper.decode(EnvironmentIndexState(projects = mutableListOf(EnvironmentIndexProjectState(projectId = "old")))).getValue("old").configs.isEmpty())
    }

    @Test
    fun `should integrate config summaries filters visibility aggregation and persistence`() {
        write(".codex/config.toml", "approval_policy = \"never\"")
        val root = Files.createDirectories(home.resolve("project"))
        write("project/.codex/config.toml", "model = \"gpt-5\"")
        write("project/.claude/settings.json", "{}")
        var saved: ProjectEnvironment? = null
        val configService = ConfigDiscoveryService(providers(), isAgentVisible = { it == "codex" })
        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(emptyList()), mcpDiscovery = McpDiscoveryService(emptyList()),
            instructionDiscovery = InstructionDiscoveryService(emptyList()), configDiscovery = configService,
            isAgentVisible = { it == "codex" }, persist = { _, env -> saved = env },
        )
        val result = service.discover(project(root))
        assertEquals(2, result.configs.size)
        assertEquals(result, saved, "risk warnings must not prevent caching/persistence")
        assertTrue(result.visibleTo(emptySet()).configs.isEmpty())
        assertEquals("Config 2 · Warnings 1", EnvironmentUiModel.summaryLabel(EnvironmentUiModel.summary(result)))
        val comparison = EnvironmentUiModel.comparison(result) { it }
        assertEquals(1, EnvironmentUiModel.filterRows(comparison, "Config", SkillFilter.ALL, ScopeFilter.PROJECT, "codex").size)
        val details = EnvironmentUiModel.detailLines(comparison.rows.first())
        assertTrue(details.any { it.contains("Format: TOML") && it.contains("Size:") && it.contains("Modified:") })
        val agentService = AgentEnvironmentDiscoveryService(service)
        assertEquals(1, agentService.discover("codex", emptyList()).configs.size)
        val agent = agentService.aggregate("codex", listOf(service.forAgent(result, "codex"), service.forAgent(result, "codex")))
        assertEquals(2, agent.configs.size)
        val agentRows = EnvironmentUiModel.agentComparison(agent, "codex").rows
        assertTrue(agentRows.all { it.agentIds.isEmpty() })
        assertEquals(setOf("Global", "Fixture"), agentRows.map { it.location }.toSet())
        assertTrue(EnvironmentUiModel.agentSummaryLabel(EnvironmentUiModel.agentSummary(agent), 1).contains("Config 2"))
    }

    @Test
    fun `should skip hidden providers and recover from failures without logging content`() {
        var calls = 0
        val throwing = object : ConfigProvider {
            override val agentId = "codex"
            override fun discoverGlobal(): List<AgentConfigSource> { calls++; error(SECRET) }
            override fun discoverProject(project: DiscoveredProject) = discoverGlobal()
        }
        assertTrue(ConfigDiscoveryService(listOf(throwing), isAgentVisible = { false }).discoverGlobal().isEmpty())
        assertEquals(0, calls)
        val logged = mutableListOf<String>()
        val logger = java.util.logging.Logger.getLogger(ConfigDiscoveryService::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { logged += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val (_, warnings) = ConfigDiscoveryService(listOf(throwing)).discoverGlobalRecordsWithWarnings()
            assertEquals(1, warnings.size)
            assertFalse(warnings.toString().contains(SECRET))
            assertTrue(logged.isNotEmpty())
            assertFalse(logged.toString().contains(SECRET))
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `should validate per-agent schemas JSONC booleans and model values`() {
        val cases = listOf(
            Triple("claude", "{\"permissions\":{\"defaultMode\":\"plan\"}}", "plan"),
            Triple("cline", "{\"actModeApiModelId\":\"claude-sonnet-4-5\"}", "claude-sonnet-4-5"),
            Triple("copilot", "{/*comment*/ \"model\":\"gpt-5\",}", "gpt-5"),
            Triple("cursor", "{\"permissions\":{\"allow\":[\"secret\"]}}", "1"),
            Triple("kiro", "{\"chat.defaultModel\":\"claude-sonnet-4-5\"}", "claude-sonnet-4-5"),
            Triple("kilo", "{\"plugin\":[\"secret\"]}", "1"),
            Triple("mimo", "{\"plugin\":[\"secret\"]}", "1"),
            Triple("opencode", "{\"plugin\":[\"secret\"]}", "1"),
            Triple("qwen", "{\"tools\":{\"approvalMode\":\"yolo\"}}", "yolo"),
            Triple("antigravity", "{\"enableTerminalSandbox\":false}", "false"),
        )
        cases.forEach { (agent, content, expected) ->
            assertEquals(expected, ConfigHighlightReader.read(agent, ConfigFormat.JSON, content).single().value, agent)
        }
        listOf("gpt-privatecredential", "https://user:password@host/model", "gpt-5" + SECRET).forEach { value ->
            assertTrue(ConfigHighlightReader.read("codex", ConfigFormat.TOML, "model = \"$value\"").isEmpty())
        }
        assertTrue(ConfigHighlightReader.read("codex", ConfigFormat.TOML, "model = \"gpt-5\"\n[broken").isEmpty())
    }

    private fun write(relative: String, content: String): Path {
        val path = home.resolve(relative).toAbsolutePath().normalize()
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    private fun project(root: Path) = DiscoveredProject(ProjectIdentity("fixture", root.toString(), null, null), "Fixture", root.toString(), null, null, null,
        listOf(AgentProject("codex", "Codex", 0, null, emptyList()), AgentProject("claude", "Claude", 0, null, emptyList())), null)

    private fun environment(configs: List<AgentConfigSource>) = ProjectEnvironment("fixture", configs.map { it.agentId }.toSet(), emptyList(), emptyList(), emptyList(), configs = configs)

    companion object { private const val SECRET = "fake-secret-token-do-not-expose" }
}

