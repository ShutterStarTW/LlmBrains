package com.shutterstar.agenthub.projects.launch

import com.shutterstar.agenthub.LaunchFlags

/**
 * The smallest useful slice of Milestone 5 (native session resume): the shell command that
 * reopens a discovered session in *its own* agent. No context translation, no cross-agent
 * handoff — the agent's own session store stays authoritative.
 *
 * Verified against the installed CLIs / official docs on 2026-09-18:
 * - Claude Code: `claude --resume <session-id>` (`claude --help`)
 * - Codex CLI:   `codex resume <SESSION_ID>` (`codex resume --help`)
 * - OpenCode:    `opencode --session <id>` (opencode.ai/docs/cli, `-s`/`--session`: "Session ID to continue")
 *
 * Pure Kotlin (no IntelliJ imports) so the session list model can consult it and it can be unit
 * tested standalone.
 */
object NativeResumeCommands {
    /**
     * [command] [subcommand] <supported [flags]> [idArgs] <id>. The flags are launch-time workarounds
     * (same ones as `CodingAgent.launchFlags`) that are only passed when the installed CLI lists them
     * in `--help` — see [LaunchFlags].
     */
    private class Template(
        val command: String,
        val subcommand: String = "",
        val flags: List<String> = emptyList(),
        val idArgs: String = "",
    )

    private val templates: Map<String, Template> = mapOf(
        "claude" to Template("claude", idArgs = "--resume"),
        "codex" to Template("codex", subcommand = "resume", flags = listOf("--no-daemon", "--no-alt-screen")),
        "opencode" to Template("opencode", idArgs = "--session"),
    )

    /**
     * Session ids are UUIDs or similar opaque tokens. Anything outside this set is refused rather
     * than quoted: the id is interpolated into a shell line, and a discovered session record is
     * untrusted input.
     */
    private val safeId = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun supports(agentId: String): Boolean = agentId in templates

    /** Why [command] returns null for this session; null when the session can be resumed. */
    fun unavailableReason(agentId: String, nativeResumeId: String?): String? = when {
        agentId !in templates -> "AgentHub does not know a native resume command for this agent yet"
        nativeResumeId.isNullOrBlank() -> "This session has no resume ID recorded"
        !safeId.matches(nativeResumeId.trim()) -> "This session's resume ID is not in a form AgentHub can pass safely to a command line"
        else -> null
    }

    /**
     * The resume command, or null when the agent has no known resume path or the id is unsafe/blank.
     * Without [help] the optional flags are omitted (cheap, non-blocking — enough to test
     * resumability); pass a `--help` source, off the EDT, to get the flags the installed CLI supports.
     */
    fun command(agentId: String, nativeResumeId: String?, help: ((String) -> String)? = null): String? {
        val template = templates[agentId] ?: return null
        val id = nativeResumeId?.trim()?.takeIf(safeId::matches) ?: return null
        val head = if (help == null) {
            listOf(template.command, template.subcommand).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            LaunchFlags.build(template.command, template.flags, template.subcommand, help)
        }
        return listOf(head, template.idArgs, id).filter { it.isNotBlank() }.joinToString(" ")
    }
}
