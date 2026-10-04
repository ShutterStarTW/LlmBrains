package com.shutterstar.agenthub.environment.skills.sync.model

enum class SkillTargetStatus {
    NOT_AVAILABLE,
    /** The agent reads the shared source directly (see [AgentCapabilities.supportsSharedAgentSkills]) - no per-agent link/copy is ever created or needed. */
    NATIVE,
    LINKED,
    COPIED,
    IDENTICAL_UNMANAGED,
    DIFFERENT,
    BROKEN_LINK,
    MISSING_SOURCE,
    UNSUPPORTED,
    ERROR,
}
