![GitHub Tag](https://img.shields.io/github/v/tag/ShutterStarTW/LlmBrains)
![GitHub Release](https://img.shields.io/github/v/release/ShutterStarTW/LlmBrains)
![GitHub commit activity](https://img.shields.io/github/commit-activity/y/ShutterStarTW/LlmBrains)
![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/32310)
![JetBrains Plugin Downloads](https://img.shields.io/jetbrains/plugin/d/32310?logo=jetbrains)


<img src="icon/agenthub.svg" alt="AgentHub" width="96" align="right" />

# AgentHub

[![JetBrains Plugin Downloads](https://img.shields.io/jetbrains/plugin/d/32310?style=for-the-badge&logo=jetbrains)](https://plugins.jetbrains.com/plugin/32310-agenthub)

[plugins.jetbrains.com/plugin/32310-agenthub](https://plugins.jetbrains.com/plugin/32310-agenthub)

**AgentHub** is a JetBrains IDE plugin that adds a toolbar button to launch popular **CLI coding agents** directly in their own IDE terminal window. Works with all JetBrains IDEs (IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, etc.).

## Features

- **One-click launch** of any CLI coding agent directly from the IDE toolbar
- **30+ built-in agents** with auto-detection of installed tools
- **Auto-detect on install** — runs automatically on first install and after each plugin update
- **Update notifications** — background check on IDE startup; notifies when npm/pip agents have newer versions available
- **Install flow** — not-installed agents are labeled "(not installed)" and prompt to install with a confirmation dialog; success confirmed automatically in background
- **Detection results persisted** across IDE restarts — no need to re-detect every session; last run timestamp displayed in Settings
- **Custom agent support** — add your own CLI tool with configurable name, command, and URL
- **Companion tools** — optional CLI utilities that work alongside the agents (usage tracking, context packing, skills); off by default
- **Check & Update utilities** — operate on all installed agents, not just enabled ones
- **Configurable** — enable/disable agents via Settings > Tools > AgentHub
- **Cross-platform** — works on macOS, Linux, and Windows
- **WSL mode** (Windows) — optionally run every agent inside a WSL distribution instead of natively

## Project & Agent Environment Discovery

Beyond launching agents, AgentHub is also a read-only, local-first inspector for the projects
and environment (skills, MCP servers, instructions) your coding agents already know about.
Nothing leaves your machine, and no agent file is ever modified.

### Features

- **Project discovery** — unifies local Antigravity, Claude Code, Cline, Codex CLI, GitHub Copilot
  CLI, Cursor CLI, Grok Build, Kiro CLI, OpenCode, and Qwen Code session metadata into unified **Projects** and **Agents** views
- **Cross-agent project merging** — normalizes local paths and Git remotes (SSH/HTTPS), so
  sessions reported by multiple agents for the same repository appear as one project
- **Environment discovery** — inspects Skills, MCP servers, and instruction files (`AGENTS.md`,
  `CLAUDE.md`, `.cursor/rules`, …) for Antigravity, Claude, Cline, Codex, Copilot, Cursor, Grok,
  Kiro, OpenCode, and Qwen Code
- **Duplicate & conflict detection** — flags skills/MCP servers that exist under multiple agents
  and whether the copies are identical or diverge
- **Secret-safe** — MCP environment values, commands, arguments, URLs, and headers are never
  rendered; only variable *names* and normalized metadata are shown
- **IDE-aware project opening** — recommends an installed JetBrains product from project-root
  markers (Gradle, Composer, npm, Python, Go, .NET, Android, …) and opens it in the current IDE
  or as a separate process
- **Project-scoped agent launch** — starts any participating agent with the project directory as
  the working directory, reusing the same terminal launch path as the toolbar
- **Cached & background refresh** — the project/environment index survives IDE restarts, rescans
  run off the UI thread, and a manual refresh is always one click away

### Using the tool window

Open the **AgentHub** tool window on the right side of the IDE to browse projects previously
used with Antigravity, Claude Code, Cline, Codex CLI, GitHub Copilot CLI, Cursor CLI, Grok Build,
Kiro CLI, OpenCode, or Qwen Code.

- **Projects** lists repositories by recent activity with participating agents and session totals.
- **Agents** lists every discovered agent and all projects used with it.
- Select a project to inspect its path, Git remote, branch, per-agent activity, and session counts.
- Open its **Environment** tab to inspect normalized Skills, MCP servers, and Instructions;
  duplicate or conflicting definitions are highlighted without exposing MCP secrets.
- Use **Open in IDE** to choose a detected JetBrains product, or **Launch** to start an agent in
  the project's working directory.
- Search filters both project and agent views; the refresh button rescans local metadata in the
  background.

### Provider coverage

| Agent               | Project discovery | Skills | MCP servers | Instructions |
|----------------------|:---:|:---:|:---:|:---:|
| Antigravity CLI      | ✅ | ✅ | ✅ | ✅ |
| Claude Code          | ✅ | ✅ | ✅ | ✅ |
| Cline                | ✅ | ✅ | ✅ | ✅ |
| Codex CLI            | ✅ | ✅ | ✅ | ✅ |
| GitHub Copilot CLI   | ✅ | ✅ | ✅ | ✅ |
| Cursor CLI           | ✅ | ✅ | ✅ | ✅ |
| Grok Build           | ✅ | ✅ | ✅ | ✅ |
| Kiro CLI             | ✅ | ✅ | ✅ | ✅ |
| OpenCode             | ✅ | ✅ | ✅ | ✅ |
| Qwen Code            | ✅ | ✅ | ✅ | ✅ |

Discovery is read-only and metadata-oriented — AgentHub does not display or index conversation
content, and never writes to any agent's files or databases.

## Supported CLI Agents

| Agent                                                                          | Command      | Provider    | Installation                                                                    |
|--------------------------------------------------------------------------------|--------------|-------------|---------------------------------------------------------------------------------|
| [Aider](https://aider.chat)                                                    | `aider`      | Aider AI    | `pip install aider-install && aider-install`                                    |
| [Amp](https://ampcode.com)                                                     | `amp`        | Sourcegraph | `npm install -g @sourcegraph/amp`                                               |
| [Antigravity CLI](https://antigravity.google/product/antigravity-cli)         | `agy`        | Google      | `curl -fsSL https://antigravity.google/cli/install.sh \| bash`                  |
| [Auggie](https://www.augmentcode.com/product/CLI)                              | `auggie`     | Augment     | `npm install -g @augmentcode/auggie`                                            |
| [Claude Code](https://claude.com/product/claude-code)                          | `claude`     | Anthropic   | `npm install -g @anthropic-ai/claude-code`                                      |
| [Cline](https://cline.bot/cli)                                                 | `cline`      | Cline       | `npm install -g cline`                                                          |
| [CodeBuddy](https://www.codebuddy.ai)                                          | `codebuddy`  | Tencent      | `npm install -g @tencent-ai/codebuddy-code`                                     |
| [Codex CLI](https://openai.com/codex)                                          | `codex`      | OpenAI      | `npm install -g @openai/codex`                                                  |
| [Cody CLI](https://sourcegraph.com/cody)                                       | `cody`       | Sourcegraph | `npm install -g @sourcegraph/cody`                                              |
| [Command Code](https://commandcode.ai)                                         | `cmd`        | Command Code | `npm install -g command-code`                                                   |
| [Continue CLI](https://continue.dev)                                           | `cn`         | Continue    | `npm install -g @continuedev/cli`                                               |
| [Copilot CLI](https://github.com/features/copilot/cli)                         | `copilot`    | GitHub      | `npm install -g @github/copilot`                                                |
| [Crush](https://charm.land/)                                                   | `crush`      | Charm       | `npm install -g @charmland/crush`                                               |
| [Cursor CLI](https://cursor.com/cli)                                           | `cursor-agent` | Cursor    | `curl https://cursor.com/install -fsS \| bash`                                  |
| [Devin](https://devin.ai/cli)                                                  | `devin`      | Cognition   | `curl -fsSL https://cli.devin.ai/install.sh \| bash`                            |
| [Droid](https://factory.ai/product/ide)                                        | `droid`      | Factory AI  | `npm install -g droid`                                                          |
| [ForgeCode](https://forgecode.dev)                                             | `forge`      | Antinomy    | `curl -fsSL https://forgecode.dev/cli \| sh`                                    |
| [Freebuff](https://freebuff.com/cli)                                           | `freebuff`   | Codebuff    | `npm install -g freebuff`                                                       |
| [Goose CLI](https://goose-docs.ai)                                             | `goose`      | Block       | `curl -fsSL https://github.com/aaif-goose/goose/releases/download/stable/download_cli.sh \| bash` |
| [Grok Build](https://x.ai/cli)                                                 | `grok`       | xAI         | `npm install -g @xai-official/grok`                                             |
| [iFlow CLI](https://iflow.cn)                                                  | `iflow`      | iFlow       | `npm install -g @iflow-ai/iflow-cli`                                            |
| [Junie CLI](https://junie.jetbrains.com)                                       | `junie`      | JetBrains   | `npm install -g @jetbrains/junie-cli`                                           |
| [Kilo Code](https://kilo.ai)                                                   | `kilo`       | Kilo        | `npm install -g @kilocode/cli`                                                  |
| [Kimi Code](https://www.kimi.com/code)                                         | `kimi`       | Moonshot AI | `pip install kimi-cli`                                                          |
| [Kiro CLI](https://kiro.dev/cli/)                                              | `kiro-cli`   | Kiro        | `curl -fsSL https://cli.kiro.dev/install \| bash`                               |
| [Kode](https://www.npmjs.com/package/@shareai-lab/kode)                        | `kode`       | shareAI-lab | `npm install -g @shareai-lab/kode`                                             |
| [LeanCTL](https://leanctl.com)                                                 | `leanctl`    | LeanCTL     | `npm install -g leanctl-bin`                                                    |
| [MiMo Code](https://mimo.xiaomi.com/mimocode)                                  | `mimo`       | Xiaomi      | `npm install -g @mimo-ai/cli`                                                   |
| [Mistral Vibe](https://mistral.ai/products/vibe)                               | `vibe`       | Mistral AI  | `pip install mistral-vibe`                                                      |
| [Muse Code](https://developer.meta.com/ai/products/muse-code/)                 | `muse`       | Meta        | `curl -fsSL https://dev.meta.ai/install.sh \| bash`                             |
| [Oh My Pi](https://omp.sh)                                                     | `omp`        | can1357     | `npm install -g @oh-my-pi/pi-coding-agent`                                      |
| [OpenClaw](https://openclaw.ai)                                                | `openclaw`   | OpenClaw    | `npm install -g openclaw`                                                       |
| [OpenCode](https://opencode.ai)                                                | `opencode`   | SST         | `npm install -g opencode-ai`                                                    |
| [OpenHands](https://openhands.dev/)                                            | `openhands`  | All Hands   | `pip install openhands-ai`                                                      |
| [Pi](https://pi.dev)                                                           | `pi`         | Mario Zechner | `npm install -g @mariozechner/pi-coding-agent`                                |
| [Plandex](https://plandex.ai)                                                  | `plandex`    | Plandex     | `curl -sL https://plandex.ai/install.sh \| bash`                                |
| [Qodo](https://qodo.ai/)                                                       | `qodo`       | Qodo        | `npm install -g @qodo/command`                                                  |
| [Qoder CLI](https://qoder.com)                                                 | `qodercli`   | Qoder AI    | `npm install -g @qoder-ai/qodercli`                                             |
| [Qwen Code](https://qwen.ai/qwencode)                                          | `qwen`       | Alibaba     | `npm install -g @qwen-code/qwen-code@latest`                                    |
| [SWE-agent](https://swe-agent.com)                                             | `sweagent`   | SWE-agent   | `pip install sweagent`                                                          |
| [VT Code](https://vinhnx.github.io/)                                           | `vtcode`     | vinhnx      | `npm install -g @vinhnx/vtcode --registry=https://npm.pkg.github.com`           |

> **Note:** Command Code, ForgeCode, LeanCTL, Muse Code, and Plandex are hidden on Windows (no native Windows build, or a launch command that collides with a built-in Windows command).

## Companion Tools

Companion tools are **not coding agents** but CLI utilities that work alongside them — usage/cost
tracking, context packing, skill management. They share the same install / detect / update / launch
flow, appear in their own **Companion Tools** section in the toolbar dropdown and Settings, and are
**off by default** (opt-in).

| Tool                                                                          | Command        | Provider        | Installation                                          | What it does                                                        |
|--------------------------------------------------------------------------------|----------------|-----------------|--------------------------------------------------------|---------------------------------------------------------------------|
| [ccusage](https://ccusage.com)                                                 | `ccusage`      | ryoppippi       | `npm install -g ccusage`                               | Token & cost usage reports from local agent logs                    |
| [Claude Code Router](https://ccrdesk.top)                                     | `ccr`          | musistudio      | `npm install -g @musistudio/claude-code-router`        | Routes Claude Code requests to other providers/models                |
| [Claude-Code-Usage-Monitor](https://pypi.org/project/claude-monitor/)          | `claude-monitor` | Maciek-roboblog | `pip install claude-monitor`                     | Live, predictive token & cost dashboard                              |
| [code2prompt](https://code2prompt.dev)                                        | `code2prompt`  | mufeedvh        | `cargo install code2prompt`                            | Codebase to LLM prompt with Handlebars templating and token counting |
| [CodeGrab](https://pkg.go.dev/github.com/epilande/codegrab)                   | `grab`         | epilande        | `brew install epilande/tap/codegrab`                   | Interactive TUI repo-context packer with secret redaction            |
| [CodeRabbit CLI](https://www.coderabbit.ai/cli)                               | `coderabbit`   | CodeRabbit      | `curl -fsSL https://cli.coderabbit.ai/install.sh \| sh` | AI code reviews directly in the terminal                            |
| [LiteLLM](https://www.litellm.ai)                                             | `litellm`      | BerriAI         | `pip install 'litellm[proxy]'`                         | Self-hosted multi-provider LLM gateway with cost tracking            |
| [Repomix](https://repomix.com)                                                | `repomix`      | yamadashy       | `npm install -g repomix`                               | Packs your repository into a single AI-friendly file                |
| [Semgrep CLI](https://semgrep.dev)                                            | `semgrep`      | Semgrep         | `pip install semgrep`                                  | Static analysis security scanner for finding bugs and vulnerabilities |
| [Skills](https://www.skills.sh)                                              | `skills`       | Vercel          | `npm install -g skills`                                | Installs reusable agent skills across many CLI agents                |
| [TokenTracker](https://www.tokentracker.cc)                                   | `tokentracker` | TokenTracker    | `npm install -g tokentracker-cli`                      | Local-first token & cost dashboard across 25 AI coding tools         |
| [Tokscale](https://tokscale.ai)                                               | `tokscale`     | junhoyeo        | `npm install -g tokscale`                              | TUI token & cost dashboard with a contribution graph                 |

> **Note:** CodeRabbit CLI is hidden on Windows (Windows support not released yet).

## Custom Agent

In addition to the built-in agents, you can configure your own custom CLI agent:

1. Go to **Settings/Preferences > Tools > AgentHub**
2. Enable the **Custom Agent** checkbox
3. Configure:
   - **Name**: Display name shown in the dropdown (e.g., "My Agent")
   - **Command**: The CLI command to execute (e.g., `myagent`)
   - **URL**: Documentation URL for reference

Your custom agent will appear in the dropdown menu alongside the built-in agents.

## WSL Mode (Windows)

On Windows, AgentHub can run agents either **natively** or inside a **WSL distribution**.
Enable it in **Settings/Preferences > Tools > AgentHub > Behavior**, under *"Run agents in:"* —
choose `WSL` and pick a distribution (or leave it on the default one).

In WSL mode:

- Every command — agent launch, detection, install/update/remove — runs inside the distro.
- Detection uses the distro's own `command -v` and ignores Windows-side binaries exposed through
  WSL interop, so an agent that's only installed on the Windows side is correctly reported as
  not installed in the distro.
- A few agents that are hidden on native Windows because they have no native Windows build
  (ForgeCode, LeanCTL, Muse Code, Plandex, Command Code) become available, since they run as Linux
  binaries inside the distro.
- If `pip`/`npm` is missing in the distro, install/update commands show a friendly hint
  (e.g. `sudo apt install python3-pip`) instead of failing silently.

Switching between native and WSL mode (or changing the distro) re-runs detection automatically,
since the two environments have a different set of "installed" agents. See
[WSL Mode](wsl-mode.md) for details.

## Usage

Click the toolbar icon in the top right corner of the IDE to access:

- **Agent actions** — Click any enabled agent to launch it in a new terminal tab. If not installed, a confirmation dialog offers to install it; success is confirmed automatically in background.
- **Auto-detect installed agents** — Scans your PATH, saves results with timestamp; also runs automatically on first install and after plugin updates
- **Check all CLI versions** — Shows version info for all installed agents; summary: `✓ N OK` or `✓ N OK   ⚠ M issues`
- **Update all agents** — Updates all installed agents to their latest versions

## Installation

1. Open your JetBrains IDE
2. Go to **Settings/Preferences > Plugins > Marketplace**
3. Search for "AgentHub"
4. Click **Install** and restart the IDE

Or install from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32310-agenthub).

## Configuration

Go to **Settings/Preferences > Tools > AgentHub** to:

- Enable or disable specific built-in agents in the dropdown menu
- Configure a custom agent with your own CLI tool

## Requirements

- JetBrains IDE 2024.1+ (platform version 241+)
- Terminal plugin (bundled with all JetBrains IDEs)

