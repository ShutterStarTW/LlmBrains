# AgentHub

[![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/32310)](https://plugins.jetbrains.com/plugin/32310-agenthub)
[![JetBrains Plugin Downloads](https://img.shields.io/jetbrains/plugin/d/32310?logo=jetbrains)](https://plugins.jetbrains.com/plugin/32310-agenthub)
![GitHub commit activity](https://img.shields.io/github/commit-activity/y/ShutterStarTW/LlmBrains)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A JetBrains IDE plugin that adds a toolbar button to launch popular **CLI coding agents**
(Claude Code, Codex, Qodo and 27 more) directly in their own IDE terminal window.
Works with all JetBrains IDEs (IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, etc.).

📦 **Marketplace:** [plugins.jetbrains.com/plugin/32310-agenthub](https://plugins.jetbrains.com/plugin/32310-agenthub)
📖 **Docs:** see [`docs/`](docs/index.md) (published via MkDocs / GitHub Pages)

---

## Development

![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.1%2B%20(251)-000000?logo=intellijidea&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17-ED8B00?logo=openjdk&logoColor=white)
![Gradle IntelliJ Plugin](https://img.shields.io/badge/Gradle%20IntelliJ%20Plugin-1.17.3-02303A?logo=gradle&logoColor=white)

### Prerequisites

- JDK 17+ (build toolchain targets 17+; a GraalVM JDK 24 is used locally)
- A locally installed **IntelliJ IDEA Ultimate** is used as the plugin SDK when present;
  otherwise the build falls back to downloading IntelliJ IDEA Community `2025.1`
- The built-in **Terminal** plugin (bundled with every JetBrains IDE)

### Common commands

Run from the project root using the Gradle wrapper:

```bash
./gradlew buildPlugin     # compile and produce the plugin ZIP
./gradlew build           # compile, verify, and build the ZIP
./gradlew runIde          # launch a sandbox IDE with the plugin installed
./gradlew test            # run the unit tests
./gradlew ktlintFormat    # auto-format Kotlin before committing
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

### Coding conventions

- Kotlin, four-space indentation, trailing commas, immutable `val` by default
- PascalCase for classes/actions, camelCase for methods, SCREAMING_SNAKE_CASE for constants
- Prefix AgentHub actions with `LlmBrains`
- Actions that must work during indexing implement `DumbAware`
- Run `./gradlew ktlintFormat` before committing

### Testing

Tests live in `src/test/kotlin/com/shutterstar/agenthub/`:

- `CodingAgentsTest.kt` — data-validation tests (agent count, ID uniqueness, install hints, …)
- `OsDetectorTest.kt` — OS detection tests

Use JUnit 5 (and MockK for mocks). Name test classes `<Subject>Test`.

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

- **One-click launch** of any CLI coding agent directly from the IDE toolbar
- **30 built-in agents** with auto-detection of installed tools
- **Auto-detect on install** — runs automatically on first install and after each plugin update
- **Update notifications** — background check on IDE startup; notifies when npm/pip agents have newer versions available
- **Install flow** — not-installed agents are labeled "(not installed)" and prompt to install with a confirmation dialog; success confirmed automatically in background
- **Detection results persisted** across IDE restarts — no need to re-detect every session; last run timestamp displayed in Settings
- **Custom agent support** — add your own CLI tool with configurable name, command, and URL
- **Check & Update utilities** — operate on all installed agents, not just enabled ones
- **Configurable** — enable/disable agents via Settings > Tools > AgentHub
- **Cross-platform** — works on macOS, Linux, and Windows

## Supported CLI Agents

| Agent                                                                          | Command      | Provider    | Installation                                                                    |
|--------------------------------------------------------------------------------|--------------|-------------|---------------------------------------------------------------------------------|
| [Aider](https://aider.chat)                                                    | `aider`      | Aider AI    | `pip install aider-install && aider-install`                                    |
| [Amp](https://ampcode.com)                                                     | `amp`        | Sourcegraph | `npm install -g @sourcegraph/amp`                                               |
| [Antigravity CLI](https://antigravity.google/product/antigravity-cli)         | `agy`        | Google      | `curl -fsSL https://antigravity.google/cli/install.sh \| bash`                  |
| [Auggie](https://www.augmentcode.com/product/CLI)                              | `auggie`     | Augment     | `npm install -g @augmentcode/auggie`                                            |
| [Claude Code](https://claude.com/product/claude-code)                          | `claude`     | Anthropic   | `npm install -g @anthropic-ai/claude-code`                                      |
| [Cline](https://cline.bot/cli)                                                 | `cline`      | Cline       | `npm install -g cline`                                                          |
| [Codex CLI](https://openai.com/codex)                                          | `codex`      | OpenAI      | `npm install -g @openai/codex`                                                  |
| [Cody CLI](https://sourcegraph.com/cody)                                       | `cody`       | Sourcegraph | `npm install -g @sourcegraph/cody`                                              |
| [Continue CLI](https://continue.dev)                                           | `cn`         | Continue    | `npm install -g @continuedev/cli`                                               |
| [Copilot CLI](https://github.com/features/copilot/cli)                         | `copilot`    | GitHub      | `npm install -g @github/copilot`                                                |
| [Crush](https://charm.land/)                                                   | `crush`      | Charm       | `npm install -g @charmland/crush`                                               |
| [Cursor CLI](https://cursor.com/cli)                                           | `cursor-agent` | Cursor    | `curl https://cursor.com/install -fsS \| bash`                                  |
| [Droid](https://factory.ai/product/ide)                                        | `droid`      | Factory AI  | `npm install -g droid`                                                          |
| [ForgeCode](https://forgecode.dev)                                             | `forge`      | Antinomy    | `curl -fsSL https://forgecode.dev/cli \| sh`                                    |
| [Goose CLI](https://block.github.io/goose)                                     | `goose`      | Block       | `curl -fsSL https://github.com/block/goose/releases/download/stable/download_cli.sh \| bash` |
| [Grok Build](https://x.ai/cli)                                                 | `grok`       | xAI         | `curl -fsSL https://x.ai/cli/install.sh \| bash`                                |
| [Kilo Code](https://kilo.ai)                                                   | `kilo`       | Kilo        | `npm install -g @kilocode/cli`                                                  |
| [Kimi Code](https://www.kimi.com/code)                                         | `kimi`       | Moonshot AI | `pip install kimi-cli`                                                          |
| [Kiro CLI](https://kiro.dev/cli/)                                              | `kiro-cli`   | Kiro        | `curl -fsSL https://cli.kiro.dev/install \| bash`                               |
| [LeanCTL](https://leanctl.com)                                                 | `leanctl`    | LeanCTL     | `npm install -g leanctl-bin`                                                    |
| [MiMo Code](https://mimo.xiaomi.com/mimocode)                                  | `mimo`       | Xiaomi      | `npm install -g @mimo-ai/cli`                                                   |
| [Mistral Vibe](https://mistral.ai/products/vibe)                               | `vibe`       | Mistral AI  | `pip install mistral-vibe`                                                      |
| [OpenCode](https://opencode.ai)                                                | `opencode`   | SST         | `npm install -g opencode-ai`                                                    |
| [OpenHands](https://openhands.dev/)                                            | `openhands`  | All Hands   | `pip install openhands-ai`                                                      |
| [Plandex](https://plandex.ai)                                                  | `plandex`    | Plandex     | `curl -sL https://plandex.ai/install.sh \| bash`                                |
| [Qodo](https://qodo.ai/)                                                       | `qodo`       | Qodo        | `npm install -g @qodo/command`                                                  |
| [Qoder CLI](https://qoder.com)                                                 | `qodercli`   | Qoder AI    | `npm install -g @qoder-ai/qodercli`                                             |
| [Qwen Code](https://qwen.ai/qwencode)                                          | `qwen`       | Alibaba     | `npm install -g @qwen-code/qwen-code@latest`                                    |
| [SWE-agent](https://swe-agent.com)                                             | `sweagent`   | SWE-agent   | `pip install sweagent`                                                          |
| [VT Code](https://vinhnx.github.io/)                                           | `vtcode`     | vinhnx      | `npm install -g @vinhnx/vtcode --registry=https://npm.pkg.github.com`           |

> **Note:** ForgeCode, LeanCTL, and Plandex have no native Windows build and are hidden on Windows.

## Custom Agent

In addition to the built-in agents, you can configure your own custom CLI agent:

1. Go to **Settings/Preferences > Tools > AgentHub**
2. Enable the **Custom Agent** checkbox
3. Configure:
   - **Name**: Display name shown in the dropdown (e.g., "My Agent")
   - **Command**: The CLI command to execute (e.g., `myagent`)
   - **URL**: Documentation URL for reference

Your custom agent will appear in the dropdown menu alongside the built-in agents.

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

- JetBrains IDE 2025.1+ (platform version 251+)
- Terminal plugin (bundled with all JetBrains IDEs)

## License

[MIT](LICENSE) © 2026 ShutterStarTW
