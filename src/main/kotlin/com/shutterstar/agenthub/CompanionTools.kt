package com.shutterstar.agenthub

/**
 * Companion tools are not coding agents but CLI utilities that operate on the same agent
 * ecosystem (usage/cost tracking, context packing, skill management). They reuse the
 * [CodingAgent] data model so the entire install/detect/update/launch/favicon pipeline works
 * unchanged, but live in this separate registry so they stay out of [CodingAgents.all] (its
 * size, [CodingAgents.defaultActiveIds], and the agent-count test) and are opt-in in the UI.
 */
object CompanionTools {
    // On Windows, hide tools with no native Windows install (mirrors CodingAgents.available()).
    fun available(): List<CodingAgent> =
        if (OsDetector.isWindows()) all.filterNot { it.unsupportedOnWindows } else all

    fun isCompanion(id: String): Boolean = all.any { it.id == id }

    val all: List<CodingAgent> = listOf(
        CodingAgent(
            id = "ccusage",
            name = "ccusage",
            command = "ccusage",
            installHint = "npm install -g ccusage",
            updateHint = "npm update --quiet --no-fund -g ccusage",
            uninstallHint = "npm uninstall -g ccusage",
            provider = "ryoppippi",
            url = "https://ccusage.com",
            devUrl = "https://github.com/ryoppippi/ccusage",
        ),
        CodingAgent(
            id = "repomix",
            name = "Repomix",
            command = "repomix",
            installHint = "npm install -g repomix",
            updateHint = "npm update --quiet --no-fund -g repomix",
            uninstallHint = "npm uninstall -g repomix",
            provider = "yamadashy",
            url = "https://repomix.com",
            devUrl = "https://github.com/yamadashy/repomix",
        ),
        CodingAgent(
            id = "skills",
            name = "Skills",
            command = "skills",
            installHint = "npm install -g skills",
            updateHint = "npm update --quiet --no-fund -g skills",
            uninstallHint = "npm uninstall -g skills",
            provider = "Vercel",
            url = "https://www.skills.sh",
            devUrl = "https://github.com/vercel-labs/skills",
        ),
        CodingAgent(
            id = "tokentracker",
            name = "TokenTracker",
            command = "tokentracker",
            installHint = "npm install -g tokentracker-cli",
            updateHint = "npm update --quiet --no-fund -g tokentracker-cli",
            uninstallHint = "npm uninstall -g tokentracker-cli",
            provider = "TokenTracker",
            url = "https://www.tokentracker.cc",
            devUrl = "https://github.com/mm7894215/TokenTracker",
        ),
    )
}