package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.DirectorySkillProvider
import java.nio.file.Path

/**
 * Minimal concrete [DirectorySkillProvider] for sync targets whose real discovery provider scans
 * several compatibility roots and is therefore not itself a [DirectorySkillProvider] (unlike
 * Claude/Codex/Kiro/Qwen, which are). Synchronization only ever writes to the agent's own native
 * root, never the compatibility paths discovery also reads for those agents — the same rule
 * [CursorSkillSyncTarget] and [OpenCodeSkillSyncTarget] already follow bespoke; this expresses it
 * once for the agents whose native shape is otherwise a plain single relative path, instead of
 * duplicating [DirectorySkillProvider]'s path-resolution logic per agent.
 */
internal class NativeSkillDirectoryProvider(
    agentId: String,
    userHome: Path,
    relativeSkillDirectory: Path,
) : DirectorySkillProvider(agentId, userHome, relativeSkillDirectory, shared = false)
