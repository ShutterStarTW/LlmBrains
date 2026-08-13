package com.shutterstar.agenthub

/**
 * Companion tools are not coding agents but CLI utilities that operate on the same agent
 * ecosystem (usage/cost tracking, context packing, skill management). They reuse the
 * [CodingAgent] data model so the entire install/detect/update/launch/favicon pipeline works
 * unchanged, but live in this separate registry so they stay out of [CodingAgents.all] (its
 * size, [CodingAgents.defaultActiveIds], and the agent-count test) and are opt-in in the UI.
 */
object CompanionTools {
    // On Windows (native mode), hide tools with no native Windows install (mirrors
    // CodingAgents.available()); in WSL mode the full list applies.
    fun available(): List<CodingAgent> =
        if (OsDetector.isWindows() && !WslSupport.isActive()) all.filterNot { it.unsupportedOnWindows } else all

    fun isCompanion(id: String): Boolean = all.any { it.id == id }

    val all: List<CodingAgent> = listOf(
        CodingAgent(
            id = "ccr",
            name = "Claude Code Router",
            command = "ccr",
            installHint = "npm install -g @musistudio/claude-code-router",
            updateHint = "npm update --quiet --no-fund -g @musistudio/claude-code-router",
            uninstallHint = "npm uninstall -g @musistudio/claude-code-router",
            provider = "musistudio",
            url = "https://ccrdesk.top",
            devUrl = "https://github.com/musistudio/claude-code-router",
        ),
        CodingAgent(
            id = "ccusage",
            name = "ccusage",
            command = "ccusage",
            installHint = "npm install -g ccusage",
            updateHint = "npm update --quiet --no-fund -g ccusage",
            uninstallHint = "npm uninstall -g ccusage",
            provider = "ryoppippi",
            url = "https://ccusage.com",
            devUrl = "https://github.com/ccusage/ccusage",
        ),
        CodingAgent(
            id = "claudemonitor",
            name = "Claude-Code-Usage-Monitor",
            command = "claude-monitor",
            installHint = "pip install claude-monitor",
            updateHint = "pip install --upgrade --upgrade-strategy eager claude-monitor",
            uninstallHint = "pip uninstall -y claude-monitor",
            provider = "Maciek-roboblog",
            url = "https://pypi.org/project/claude-monitor/",
            devUrl = "https://github.com/Maciek-roboblog/Claude-Code-Usage-Monitor",
            faviconKey = "github.com",
        ),
        CodingAgent(
            id = "code2prompt",
            name = "code2prompt",
            command = "code2prompt",
            installHint = "cargo install code2prompt",
            updateHint = "cargo install code2prompt --force",
            uninstallHint = "cargo uninstall code2prompt",
            provider = "mufeedvh",
            url = "https://code2prompt.dev",
            devUrl = "https://github.com/mufeedvh/code2prompt",
        ),
        CodingAgent(
            id = "codegrab",
            name = "CodeGrab",
            command = "grab",
            installHint = "brew install epilande/tap/codegrab",
            updateHint = "brew upgrade codegrab",
            uninstallHint = "brew uninstall codegrab",
            installHintWindows = "go install github.com/epilande/codegrab/cmd/grab@latest",
            uninstallHintWindows = "Remove-Item -Force \"\$env:USERPROFILE\\go\\bin\\grab.exe\" -ErrorAction SilentlyContinue",
            provider = "epilande",
            url = "https://pkg.go.dev/github.com/epilande/codegrab",
            devUrl = "https://github.com/epilande/codegrab",
            faviconKey = "github.com",
        ),
        CodingAgent(
            id = "coderabbit",
            name = "CodeRabbit CLI",
            command = "coderabbit",
            installHint = "curl -fsSL https://cli.coderabbit.ai/install.sh | sh",
            updateHint = "curl -fsSL https://cli.coderabbit.ai/install.sh | sh",
            uninstallHint = "rm -f \$(which coderabbit) \$(which cr)",
            provider = "CodeRabbit",
            url = "https://www.coderabbit.ai/cli",
            unsupportedOnWindows = true, // Windows support not released yet (macOS + Linux only)
        ),
        CodingAgent(
            id = "litellm",
            name = "LiteLLM",
            command = "litellm",
            installHint = "pip install 'litellm[proxy]'",
            updateHint = "pip install --upgrade --upgrade-strategy eager 'litellm[proxy]'",
            uninstallHint = "pip uninstall -y litellm",
            provider = "BerriAI",
            url = "https://www.litellm.ai",
            devUrl = "https://github.com/BerriAI/litellm",
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
            id = "semgrep",
            name = "Semgrep CLI",
            command = "semgrep",
            installHint = "pip install semgrep",
            updateHint = "pip install --upgrade --upgrade-strategy eager semgrep",
            uninstallHint = "pip uninstall -y semgrep",
            provider = "Semgrep",
            url = "https://semgrep.dev",
            devUrl = "https://github.com/semgrep/semgrep",
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
        CodingAgent(
            id = "tokscale",
            name = "Tokscale",
            command = "tokscale",
            installHint = "npm install -g tokscale",
            updateHint = "npm update --quiet --no-fund -g tokscale",
            uninstallHint = "npm uninstall -g tokscale",
            provider = "junhoyeo",
            url = "https://tokscale.ai/",
            devUrl = "https://github.com/junhoyeo/tokscale",
        ),
    )
}
