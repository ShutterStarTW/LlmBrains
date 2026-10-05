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
 * Verified against the installed CLIs' `--help` on 2026-10-05 (the id AgentHub records is the one the CLI lists):
 * - Cline:       `cline --id <session-id>` ("Resume an existing session by ID")
 * - Copilot CLI: `copilot --resume <id>`
 * - Cursor CLI:  `cursor-agent --resume <chatId>` (chat id = the `~/.cursor/chats/<hash>/<chatId>` folder name)
 * - Grok Build:  `grok --resume <id>` (accepts an ID or a title; AgentHub only ever passes the UUID)
 * - Kiro CLI:    `kiro-cli chat --resume-id <id>`
 * - Qwen Code:   `qwen --resume <id>`
 * - Freebuff:    `freebuff --continue <chatId>` (chat id = the `projects/<name>/chats/<chatId>` folder name)
 * - Junie CLI:   `junie --resume --session-id=<id>` (`--resume`: "Resume the last session (or the session specified by --session-id)")
 * - MiMo Code:   `mimo --session <id>` (OpenCode fork; `-s`/`--session`: "session id to continue")
 * - Mistral Vibe: `vibe --resume <SESSION_ID>`
 * - Antigravity: `agy --conversation <id>` (id = conversation UUID in `~/.gemini/antigravity-cli`: the
 *   `conversations/<id>.db` file name, `conversation_summaries.db` and `cache/last_conversations.json`)
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
        "antigravity" to Template("agy", idArgs = "--conversation"),
        "claude" to Template("claude", idArgs = "--resume"),
        "cline" to Template("cline", idArgs = "--id"),
        "codex" to Template("codex", subcommand = "resume", flags = listOf("--no-daemon", "--no-alt-screen")),
        "copilot" to Template("copilot", idArgs = "--resume"),
        "cursor" to Template("cursor-agent", idArgs = "--resume"),
        "freebuff" to Template("freebuff", idArgs = "--continue"),
        "grok" to Template("grok", idArgs = "--resume"),
        "junie" to Template("junie", idArgs = "--resume --session-id="),
        "kilo" to Template("kilo", idArgs = "--session"),
        "kimi" to Template("kimi", idArgs = "--session"),
        "kiro" to Template("kiro-cli", subcommand = "chat", idArgs = "--resume-id"),
        "mimo" to Template("mimo", idArgs = "--session"),
        "omp" to Template("omp", idArgs = "--resume"),
        "opencode" to Template("opencode", idArgs = "--session"),
        "qwen" to Template("qwen", idArgs = "--resume"),
        "vibe" to Template("vibe", idArgs = "--resume"),
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
        // A flag written `--name=` (Junie's `--session-id=`) is glued to the id; anything else is `flag id`.
        val tail = if (template.idArgs.endsWith("=")) template.idArgs + id else listOf(template.idArgs, id).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(head, tail).filter { it.isNotBlank() }.joinToString(" ")
    }
}
