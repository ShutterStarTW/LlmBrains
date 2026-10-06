package com.shutterstar.agenthub.environment.skills.model

/**
 * Who a skill folder belongs to. Several agents also read another agent's folder (Cline, Cursor, OpenCode, Kilo, ...
 * scan `.claude/skills`; Grok scans `.cursor/skills`; OMP scans `.codex/skills`), so discovery reports one directory
 * under every agent that scans it. Only the agent whose own folder it is owns the skill; the others merely read it.
 */
internal object SkillRootOwner {
    /** The folders other agents are known to read, mapped to the agent that owns them. */
    private val FOREIGN_ROOTS = mapOf(".claude" to "claude", ".codex" to "codex", ".cursor" to "cursor")

    /** The agent whose own folder [path] lies in, when it is one of the folders other agents read; otherwise null. */
    fun ownerOf(path: String): String? =
        path.split('/', '\\').lastOrNull { it in FOREIGN_ROOTS }?.let(FOREIGN_ROOTS::get)

    /** False when [agentId] only reads this folder because it belongs to a different agent. */
    fun isNative(agentId: String, path: String): Boolean = ownerOf(path).let { it == null || it == agentId }
}
