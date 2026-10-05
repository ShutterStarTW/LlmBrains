# AgentHub

[![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/32310)](https://plugins.jetbrains.com/plugin/32310-agenthub)
[![JetBrains Plugin Downloads](https://img.shields.io/jetbrains/plugin/d/32310?logo=jetbrains)](https://plugins.jetbrains.com/plugin/32310-agenthub)
![GitHub commit activity](https://img.shields.io/github/commit-activity/y/ShutterStarTW/LlmBrains)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A JetBrains IDE plugin that adds a toolbar button to launch popular **CLI coding agents**
(Claude Code, Codex, Qodo and 38 more) directly in their own IDE terminal window.
Works with all JetBrains IDEs (IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, etc.).

A tool window also shows the projects, sessions, skills, MCP servers and instruction files your agents
use, and lets you [share skills between agents](docs/skills.md).

**Marketplace:** [plugins.jetbrains.com/plugin/32310-agenthub](https://plugins.jetbrains.com/plugin/32310-agenthub)
**Docs:** see [`docs/`](docs/index.md) (published via MkDocs / GitHub Pages)

---

## Development

![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.1%2B%20(241)-000000?logo=intellijidea&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17-ED8B00?logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.6.1-02303A?logo=gradle&logoColor=white)
![IntelliJ Platform Gradle Plugin](https://img.shields.io/badge/IntelliJ%20Platform%20Gradle%20Plugin-2.18.1-000000?logo=intellijidea&logoColor=white)

### Prerequisites

- JDK 17+ (build toolchain targets 17+; a GraalVM JDK 24 is used locally)
- A locally installed **IntelliJ IDEA Ultimate** is used as the plugin SDK when present;
  otherwise the build falls back to downloading IntelliJ IDEA Community `2024.1`
- The built-in **Terminal** plugin (bundled with every JetBrains IDE)

### Common commands

Run from the project root using the Gradle wrapper:

```bash
./gradlew buildPlugin     # compile and produce the plugin ZIP
./gradlew build           # compile, verify, and build the ZIP
./gradlew runIde          # launch a sandbox IDE with the plugin installed
./gradlew test            # run the unit tests
mkdocs serve              # preview the docs at http://localhost:8000
```

The plugin ZIP is written to `build/distributions/agenthub-<version>.zip`.
The version is read from [`VERSION.md`](VERSION.md) at build time.

> **Note:** In restricted/CI environments where the Gradle daemon has no network access,
> use the wrapper JAR directly and run the tests via the JUnit standalone launcher.

### Project structure

```
src/main/kotlin/com/shutterstar/agenthub/   Kotlin sources (one file per feature)
src/main/resources/META-INF/plugin.xml  Plugin manifest & extension registration
src/main/resources/favicons/            Per-agent toolbar icons (<domain>.png)
src/main/resources/scripts/             Helper scripts (llmbrains.sh / llmbrains.ps1)
src/test/kotlin/com/shutterstar/agenthub/   JUnit 5 tests
docs/                                    MkDocs documentation
```

Key components:

| File | Responsibility |
|------|----------------|
| `LlmBrainsActionGroup` | Builds the toolbar dropdown and the detect / check / update actions |
| `CodingAgents` | Registry & data model of all supported agents |
| `AgentDetector` | In-process, parallel detection of installed agents |
| `AgentSettingsState` / `AgentSettingsConfigurable` | Persisted settings + the Settings UI panel |
| `DetectionResultsWatcher` | Polls helper-script result files and updates state |
| `LlmBrainsStartupActivity` | Runs auto-detect on plugin update and a once-per-session update check |
| `TerminalCommandRunner` | Runs commands in a new IDE terminal window |
| `LlmBrainsScriptInstaller` / `OsDetector` | Install the OS-appropriate helper script |
| `ProjectEnvironmentDiscoveryService` | Merges project skills, MCP servers, and instruction sources for inspection |

### Coding conventions

- Kotlin, four-space indentation, trailing commas, immutable `val` by default
- PascalCase for classes/actions, camelCase for methods, SCREAMING_SNAKE_CASE for constants
- Prefix AgentHub actions with `LlmBrains`
- Actions that must work during indexing implement `DumbAware`
- Follow the existing Kotlin formatting conventions; no ktlint Gradle task is configured

### Testing

Tests live in `src/test/kotlin/com/shutterstar/agenthub/`, next to the package they cover
(`projects/`, `environment/`, `environment/skills/sync/`, …). For example, `CodingAgentsTest.kt`
validates the agent registry (ID uniqueness, ordering, install hints). Run the whole suite with
`./gradlew test --offline` or `src/test/scripts/run-all-tests.ps1`.

Use JUnit 5 with temporary filesystem fixtures and hand-written fakes. Name test classes `<Subject>Test`.

### Commit & PR guidelines

Use Conventional Commit prefixes (`feat`, `fix`, `docs`, `chore`, …) with a short imperative
summary; reference issues as `(#123)`. PRs should include a purpose summary, testing evidence,
and notes on any plugin-manifest changes.

See also: [`CHANGES.md`](CHANGES.md)

---

# Plugin README

> The user-facing description published to the JetBrains Marketplace and the docs site.

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
  sent and when it ran. **Resume** reopens a Claude Code, Codex CLI or OpenCode session in a
  terminal; **Transcript** opens the session file. See [session statistics](docs/session-statistics.md) for the per-session usage details.
- **Environment** — skills, MCP servers, instruction files (`AGENTS.md`, `CLAUDE.md`,
  `.cursor/rules`, …) and configuration files per project or agent. Skills and MCP servers that
  exist under several agents are marked as identical or different. MCP secrets are never shown,
  only variable names. See the [environment discovery paths](docs/environment-paths.md).
- **Skills** — share a skill between agents, resolve conflicts, restore backups and undo. See [the Skills page](docs/skills.md).
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
[WSL Mode](https://ShutterStarTW.github.io/LlmBrains/wsl-mode/) in the docs for details.

## Usage

Click the toolbar icon in the top right corner of the IDE to access:

- **Agent actions** — click an enabled agent to launch it in a new terminal tab. If it is not installed, a dialog offers to install it; the install finishes in the background.
- **Detect installed agents** — scans your PATH and saves the results with a timestamp; also runs on every IDE start and after plugin updates
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

## License

[MIT](LICENSE) © 2026 ShutterStarTW
