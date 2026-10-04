package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpdateChecksTest {

    private fun agent(id: String, updateHint: String = "", versionSource: String = "") = CodingAgent(
        id = id,
        name = id,
        command = id,
        installHint = "",
        updateHint = updateHint,
        versionSource = versionSource,
        url = "",
    )

    // --- VersionCompare / classifyVersions ---

    @Test
    fun `parses versions embedded in surrounding text`() {
        assertEquals(listOf(2L, 1L, 282L), VersionCompare.parse("2.1.282 (Claude Code)"))
        assertEquals(listOf(2L, 20L, 1L), VersionCompare.parse("kiro-cli-chat 2.20.1"))
        assertEquals(listOf(0L, 0L, 1790251618L), VersionCompare.parse("0.0.1790251618-ge05846"))
        assertEquals(listOf(1L, 2L, 11L), VersionCompare.parse("cli/v1.2.11"))
        assertNull(VersionCompare.parse("stable"))
        assertNull(VersionCompare.parse(""))
        assertNull(VersionCompare.parse(null))
    }

    @Test
    fun `compares numerically per segment, not as strings`() {
        assertEquals(UpdateStatus.OUTDATED, classifyVersions("agy 1.2.7", "1.2.11"))
        assertEquals(UpdateStatus.CURRENT, classifyVersions("1.2.11", "1.2.7"))
        assertEquals(UpdateStatus.CURRENT, classifyVersions("2.1.282", "v2.1.282"))
        assertEquals(UpdateStatus.CURRENT, classifyVersions("2.1", "2.1.0"))
        assertEquals(UpdateStatus.OUTDATED, classifyVersions("2.1", "2.1.1"))
    }

    @Test
    fun `unparsable versions are unknown, never up to date`() {
        assertEquals(UpdateStatus.UNKNOWN, classifyVersions("command not found", "1.0.0"))
        assertEquals(UpdateStatus.UNKNOWN, classifyVersions("1.0.0", null))
        assertEquals(UpdateStatus.UNKNOWN, classifyVersions("1.0.0", "stable"))
        assertEquals(UpdateStatus.UNKNOWN, classifyVersions(null, "1.0.0"))
    }

    // --- VersionSource ---

    @Test
    fun `parses explicit sources and rejects malformed ones`() {
        assertEquals(VersionSource(VersionSource.Kind.NPM, "@scope/pkg"), VersionSource.parse("npm:@scope/pkg"))
        assertEquals(VersionSource(VersionSource.Kind.GITHUB, "owner/repo"), VersionSource.parse("github:owner/repo"))
        assertEquals(VersionSource(VersionSource.Kind.PYPI, "aider-chat"), VersionSource.parse("pypi:Aider_Chat"))
        assertNull(VersionSource.parse(""))
        assertNull(VersionSource.parse("npm:"))
        assertNull(VersionSource.parse("gitlab:owner/repo"))
        assertNull(VersionSource.parse("nocolon"))
    }

    @Test
    fun `derives the package from npm and pip update hints`() {
        assertEquals(
            VersionSource(VersionSource.Kind.NPM, "@openai/codex"),
            VersionSource.derive("npm update --quiet --no-fund -g @openai/codex"),
        )
        assertEquals(
            VersionSource(VersionSource.Kind.NPM, "@vinhnx/vtcode"),
            VersionSource.derive("npm update -g @vinhnx/vtcode --registry=https://registry.npmjs.org"),
        )
        assertEquals(
            VersionSource(VersionSource.Kind.PYPI, "litellm"),
            VersionSource.derive("pip install --upgrade --upgrade-strategy eager 'litellm[proxy]'"),
        )
        assertEquals(
            VersionSource(VersionSource.Kind.PYPI, "aider-chat"),
            VersionSource.derive("uv tool upgrade aider-chat || pipx upgrade aider-chat"),
        )
        assertNull(VersionSource.derive("agy update"))
        assertNull(VersionSource.derive("curl -fsSL https://dev.meta.ai/install.sh | bash"))
        assertNull(VersionSource.derive(""))
    }

    @Test
    fun `every explicit versionSource in the registries is well-formed`() {
        (CodingAgents.all + CompanionTools.all).filter { it.versionSource.isNotBlank() }.forEach {
            assertNotNull(VersionSource.parse(it.versionSource), "malformed versionSource for ${it.id}: ${it.versionSource}")
        }
    }

    @Test
    fun `claude is checked against npm even though its update command is not npm`() {
        val claude = CodingAgents.all.first { it.id == "claude" }
        assertEquals(VersionSource(VersionSource.Kind.NPM, "@anthropic-ai/claude-code"), claude.resolvedVersionSource)
    }

    // --- UpdateChecker ---

    private class FakeFetcher(private val latest: Map<String, String?>) : LatestVersionFetcher {
        val requested = mutableListOf<String>()
        override fun latest(source: VersionSource): String? {
            synchronized(requested) { requested += source.id }
            return latest[source.id]
        }
    }

    @Test
    fun `natively installed npm agent is compared against the registry`() {
        // claude is not in `npm ls -g`, so the package manager cannot know about it.
        val claude = agent("claude", "claude update", "npm:@anthropic-ai/claude-code")
        val commands = mutableListOf<String>()
        val checker = UpdateChecker(
            runCommand = { cmd ->
                commands += cmd
                when (cmd) {
                    "npm ls -g --depth=0 --json" -> """{"dependencies":{"other":{"version":"1.0.0"}}}"""
                    "claude --version" -> "2.1.270 (Claude Code)"
                    else -> ""
                }
            },
            fetcher = FakeFetcher(mapOf("@anthropic-ai/claude-code" to "2.1.282")),
        )
        val outcome = checker.check(listOf(claude))
        assertEquals(listOf("claude"), outcome.outdated)
        assertTrue(outcome.unverified.isEmpty())
        assertFalse("npm outdated -g --json" in commands, "npm outdated is only needed for npm-managed packages")
    }

    @Test
    fun `npm managed agent trusts the package manager report`() {
        val codex = agent("codex", "npm update --quiet --no-fund -g @openai/codex")
        val fresh = agent("freebuff", "npm update --quiet --no-fund -g freebuff")
        val fetcher = FakeFetcher(emptyMap())
        val checker = UpdateChecker(
            runCommand = { cmd ->
                when (cmd) {
                    "npm ls -g --depth=0 --json" ->
                        """{"dependencies":{"@openai/codex":{"version":"0.156.1"},"freebuff":{"version":"0.0.195"}}}"""
                    "npm outdated -g --json" ->
                        """{"@openai/codex":{"current":"0.156.1","wanted":"0.157.0","latest":"0.157.0","location":"x"}}"""
                    else -> ""
                }
            },
            fetcher = fetcher,
        )
        val outcome = checker.check(listOf(codex, fresh))
        assertEquals(listOf("codex"), outcome.outdated)
        assertTrue(outcome.unverified.isEmpty())
        assertTrue(fetcher.requested.isEmpty(), "managed packages must not hit the registry")
    }

    @Test
    fun `uv or pipx installed python agent falls back to the registry`() {
        val aider = agent("aider", "uv tool upgrade aider-chat || pipx upgrade aider-chat", "pypi:aider-chat")
        val checker = UpdateChecker(
            runCommand = { cmd ->
                when (cmd) {
                    "pip list --format=json" -> """[{"name": "requests", "version": "2.0"}]"""
                    "aider --version" -> "aider 0.85.0"
                    else -> ""
                }
            },
            fetcher = FakeFetcher(mapOf("aider-chat" to "0.86.2")),
        )
        assertEquals(listOf("aider"), checker.check(listOf(aider)).outdated)
    }

    @Test
    fun `pip managed agent uses pip outdated with normalized names`() {
        val semgrep = agent("semgrep", "pip install --upgrade --upgrade-strategy eager semgrep")
        val checker = UpdateChecker(
            runCommand = { cmd ->
                when (cmd) {
                    "pip list --format=json" -> """[{"name": "Semgrep", "version": "1.0"}]"""
                    "pip list --outdated --format=json" -> """[{"name": "semgrep", "version": "1.0", "latest_version": "1.1"}]"""
                    else -> ""
                }
            },
            fetcher = FakeFetcher(emptyMap()),
        )
        assertEquals(listOf("semgrep"), checker.check(listOf(semgrep)).outdated)
    }

    @Test
    fun `github sourced agent is outdated when the release tag is newer`() {
        val agy = agent("antigravity", "agy update", "github:google-antigravity/antigravity-cli")
        val checker = UpdateChecker(
            runCommand = { if (it == "antigravity --version") "1.2.7" else "" },
            fetcher = FakeFetcher(mapOf("google-antigravity/antigravity-cli" to "1.2.11")),
        )
        assertEquals(listOf("antigravity"), checker.check(listOf(agy)).outdated)
    }

    @Test
    fun `agents that cannot be checked are reported as unverified, not up to date`() {
        val noSource = agent("cursor", "cursor-agent update")
        val lookupFails = agent("forge", "forge update", "github:tailcallhq/forgecode")
        val badVersionOutput = agent("goose", "goose update", "github:aaif-goose/goose")
        val current = agent("plandex", "plandex upgrade", "github:plandex-ai/plandex")
        val checker = UpdateChecker(
            runCommand = { cmd ->
                when (cmd) {
                    "forge --version" -> "forge 2.0.0"
                    "goose --version" -> "usage: goose"
                    "plandex --version" -> "2.2.1"
                    else -> ""
                }
            },
            fetcher = FakeFetcher(mapOf("aaif-goose/goose" to "v1.52.0", "plandex-ai/plandex" to "cli/v2.2.1")),
        )
        val outcome = checker.check(listOf(noSource, lookupFails, badVersionOutput, current))
        assertTrue(outcome.outdated.isEmpty())
        assertEquals(listOf("cursor", "forge", "goose"), outcome.unverified)
    }

    @Test
    fun `empty agent list is a no-op`() {
        val outcome = UpdateChecker(runCommand = { "" }, fetcher = FakeFetcher(emptyMap())).check(emptyList())
        assertTrue(outcome.outdated.isEmpty())
        assertTrue(outcome.unverified.isEmpty())
    }
}
