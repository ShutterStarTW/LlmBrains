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

- **Launch from the toolbar** — start any CLI coding agent in its own IDE terminal tab
- **40+ built-in agents**, with detection of which ones are installed
- **Automatic detection** — runs on every IDE start and after each plugin update
- **Update notifications** — a background check on IDE startup reports agents that have a newer version
- **Install from the menu** — agents that are not installed are labeled "(not installed)"; clicking one asks for confirmation, installs it, and launches it when the install finishes
- **Saved detection results** — kept across IDE restarts; Settings shows when detection last ran
- **Custom agent** — add your own CLI tool with a name, command and URL
- **Companion tools** — optional CLI utilities that work alongside the agents (usage tracking, context packing, skills); off by default
- **Check & Update utilities** — check installed agents and update those flagged outdated, including disabled ones
- **Configurable** — enable or disable agents in Settings > Tools > AgentHub
- **Cross-platform** — macOS, Linux and Windows
- **WSL mode** (Windows) — run every agent inside a WSL distribution instead of natively

## Projects, Environment and Skills

The **AgentHub** tool window (right side of the IDE) shows the projects your coding agents have worked
on, and the skills, MCP servers, instruction files and configuration files they use. Everything is
read from local files and nothing is uploaded. Discovery is read-only. Skill files change only after
you review a plan and confirm it.

### Features

- **Projects and Agents** — sessions from Antigravity, Claude Code, Cline, Codex CLI, GitHub Copilot
  CLI, Cursor CLI, Freebuff, Grok Build, Junie CLI, Kilo Code, Kimi Code, Kiro CLI, MiMo Code, Mistral Vibe,
  Oh My Pi, OpenCode and Qwen Code, grouped by
  project or by agent.
  Sessions from several agents in the same repository appear as one project (local path and Git
  remote are matched).
- **Sessions** — each session shows the agent's title (or your first prompt), how many prompts you
  sent and when it ran. **Resume** reopens a session of Antigravity, Claude Code, Cline, Codex CLI, Copilot CLI, Cursor CLI, Freebuff, Grok Build, Junie CLI,
  Kilo Code, Kimi Code, Kiro CLI, MiMo Code, Mistral Vibe, Oh My Pi, OpenCode or Qwen Code in a
  terminal; **Transcript** opens the session file. See [session statistics](session-statistics.md) for the per-session usage details.
- **Environment** — skills, MCP servers, instruction files (`AGENTS.md`, `CLAUDE.md`,
  `.cursor/rules`, …) and configuration files per project or agent. Skills and MCP servers that
  exist under several agents are marked as identical or different. MCP secrets are never shown,
  only variable names. See the [environment discovery paths](environment-paths.md).
- **Skills** — share a skill between agents, resolve conflicts, restore backups and undo. See [the Skills page](skills.md).
- **Installed agents only** — only agents whose CLI the latest detection found are shown. Detection
  runs on every IDE start and after Detect, Settings install/remove and WSL switches. If you
  reinstall an agent, its data still on disk reappears. An installed agent that has no session yet is
  listed on the Agents tab too: its Projects and Sessions tabs stay greyed out, while Environment already
  shows its global skills, MCP servers, instructions and configuration.
- **Open in IDE and Launch** — open a project in a detected JetBrains IDE, or start an agent with
  the project directory as its working directory.
- **Cached index** — kept across IDE restarts and refreshed in the background. The refresh button
  rescans on demand.

### Using the tool window

- **Projects** lists repositories by recent activity. Select one to see its path, Git remote,
  per-agent activity, sessions and environment.
- **Agents** lists each discovered agent with its projects. Double-click a project (or press
  **Enter**) to open it on the Projects tab.
- **Skills** lists global or project skills. One **Filters** popup narrows the list.
- The search box under the tabs filters the selected tab. In a narrow window the details replace the
  list, and a back button returns to it.
- Row menus (right-click or **Shift+F10**) offer Open, Reveal in Files and Copy Path. With a session
  selected, **Ctrl+Enter** resumes it and **Shift+Enter** opens its transcript.

Project, MCP and instruction data is read-only. Only confirmed Skills actions write to skill
folders, with backups and rollback.

## Supported CLI Agents

| Agent                                                                          | Command      | Provider    | Installation                                                                    |
|--------------------------------------------------------------------------------|--------------|-------------|---------------------------------------------------------------------------------|
| [Aider](https://aider.chat)                                                    | `aider`      | Aider AI    | `pip install aider-install && aider-install`                                    |
| [Amp](https://ampcode.com)                                                     | `amp`        | Sourcegraph | `npm install -g @ampcode/cli`                                                   |
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
| [Kimi Code](https://www.kimi.com/code)                                         | `kimi`       | Moonshot AI | `npm install -g @moonshot-ai/kimi-code`                                          |
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

> **Note:** Command Code, ForgeCode, LeanCTL, Muse Code, and Plandex are hidden on Windows (they have no native Windows build, or their launch command collides with a built-in Windows command).

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
- Detection uses the distro's own `command -v` and ignores Windows binaries exposed through WSL
  interop, so an agent installed only on the Windows side is reported as not installed in the distro.
- A few agents that are hidden on native Windows because they have no native Windows build
  (ForgeCode, LeanCTL, Muse Code, Plandex, Command Code) become available, since they run as Linux
  binaries inside the distro.
- If `pip` or `npm` is missing in the distro, install and update commands print a hint
  (e.g. `sudo apt install python3-pip`) instead of failing silently.

Switching between native and WSL mode (or changing the distro) re-runs detection automatically,
because each environment has its own set of installed agents. See
[WSL Mode](wsl-mode.md) for details.

## Usage

Click the toolbar icon in the top right corner of the IDE to access:

- **Agent actions** — click an enabled agent to launch it in a new terminal tab. If it is not installed, a dialog offers to install it; the install finishes in the background.
- **Detect installed agents** — scans your PATH and saves the results with a timestamp; also runs on every IDE start (the AgentHub tool window only shows installed agents)
- **Check all CLI versions** — shows the version of every installed agent; the summary reads `✓ N OK` or `✓ N OK   ⚠ M issues`
- **Update all agents** — updates the installed agents that the last update check flagged as outdated

## Installation

1. Open your JetBrains IDE
2. Go to **Settings/Preferences > Plugins > Marketplace**
3. Search for "AgentHub"
4. Click **Install** and restart the IDE

Or install from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32310-agenthub).

## Configuration

Go to **Settings/Preferences > Tools > AgentHub** to:

- Enable or disable built-in agents in the dropdown menu
- Enable companion tools
- Configure a custom agent
- Choose whether operations run in the background, and (on Windows) whether agents run natively or in WSL

## Requirements

- JetBrains IDE 2024.1+ (platform version 241+)
- Terminal plugin (bundled with all JetBrains IDEs)


Agent and project **Environment** views also include **Config**: a read-only inventory of documented
agent settings files, safe model/mode/count highlights, Open / Reveal / Copy Path, and warnings for
configured approval bypass or unrestricted sandbox modes. See [Environment paths](environment-paths.md#config-inventory-2026-09-30).
