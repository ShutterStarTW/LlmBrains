# Environment discovery paths

This page lists the paths **AgentHub currently scans** for skills, instruction files, and MCP
servers. It describes AgentHub's implementation, which may cover a different set of sources than
the agent CLI itself. The list applies to the ten agents with environment providers.

`~` means the operating system user's home directory. `<project>` means the resolved local
project root. `**` means AgentHub also scans matching nested directories, subject to the scan
limits below. A path ending in `/` is a directory; otherwise it is a file. Environment variable
overrides affect global paths only; project paths remain relative to `<project>`.

## Shared skill location

AgentHub scans `~/.agents/skills/` and `<project>/**/.agents/skills/` once and associates those
skills with Antigravity, Codex, Copilot, Cursor, Grok, and OpenCode. The other four agents do not
receive this shared location. The table below omits `.agents/skills/` where this rule applies.

## Antigravity CLI

- **Skills, global:** `~/.gemini/config/skills/`, `~/.gemini/antigravity-cli/skills/`,
  `~/.gemini/skills/`, `~/.gemini/antigravity/skills/`. AgentHub also scans
  `~/.gemini/{antigravity-cli,antigravity}/builtin/skills/`. The configured
  `ANTIGRAVITY_HOME` or `GEMINI_HOME` adds its `skills/` and
  `builtin/skills/` folders. AgentHub also scans `skills/` inside plugin folders under these
  global roots.
- **Skills, project:** `<project>/**/{.agent,_agents,_agent,.gemini}/skills/` and `skills/`
  inside project plugin folders under `.agents/`, `.agent/`, `_agents/`, or `_agent/`.
- **Instructions, global:** `GEMINI.md` and `AGENTS.md` under `~/.gemini/`,
  `~/.gemini/config/`, `~/.gemini/antigravity-cli/`, `~/.gemini/antigravity/`, and a configured
  Antigravity/Gemini home. Markdown rules under `~/.gemini/rules/`,
  `~/.gemini/config/rules/`, `~/.gemini/antigravity-cli/rules/`, and the configured home's
  `rules/` folder.
- **Instructions, project:** nested `AGENTS.md` and `GEMINI.md`; Markdown rules in
  `**/{.agents,.agent,_agents,_agent,.gemini}/rules/` and recognized plugin `rules/` folders.
- **MCP, global:** `mcp_config.json` under `~/.gemini/config/`,
  `~/.gemini/antigravity-cli/`, `~/.gemini/`, `~/.gemini/antigravity/`, the configured home
  (including its `config/`), and recognized global plugin folders.
- **MCP, project:** `<project>/mcp_config.json` and the same filename under
  `<project>/{.agents,.agent,_agents,_agent,.gemini}/` and recognized project plugin folders.

## Claude Code

- **Skills:** global `$CLAUDE_CONFIG_DIR/skills/` (default `~/.claude/skills/`), including
  the separately marked `synced/` pack; project `<project>/.claude/skills/`.
- **Instructions:** global `$CLAUDE_CONFIG_DIR/CLAUDE.md` and `rules/**/*.md`; project
  nested `CLAUDE.md`, `CLAUDE.local.md`, and `.claude/rules/**/*.md`.
- **MCP:** global `~/.claude.json`; project `<project>/.mcp.json` and matching project
  entries in `~/.claude.json`.

## Cline

- **Skills:** global `~/.cline/skills/`; project `<project>/.cline/skills/`,
  `<project>/.clinerules/skills/`, and `<project>/.claude/skills/`.
- **Instructions:** global Markdown and text rules under `~/.cline/rules/`,
  `~/Documents/Cline/Rules/`, and `~/Cline/Rules/`; project nested `AGENTS.md`,
  `.cursorrules`, `.windsurfrules`, `.clinerules/**/*.{md,txt}`, and
  `.cline/rules/**/*.{md,txt}`.
- **MCP:** global `~/.cline/data/settings/cline_mcp_settings.json`; project
  `<project>/.cline/mcp.json`.

## Codex CLI

- **Skills:** global `$CODEX_HOME/skills/` (default `~/.codex/skills/`), including the
  separately marked `.system/` pack; project `<project>/.codex/skills/`.
- **Instructions:** global `$CODEX_HOME/AGENTS.override.md`, falling back to
  `$CODEX_HOME/AGENTS.md`; project `AGENTS.override.md` or `AGENTS.md` in each scanned
  directory, with the override taking priority within that directory.
- **MCP:** global `$CODEX_HOME/config.toml`; project `<project>/.codex/config.toml`.

## GitHub Copilot CLI

- **Skills:** global `$COPILOT_HOME/skills/` (default `~/.copilot/skills/`) and skill
  folders declared by installed plugin manifests; project `<project>/.github/skills/` and
  `<project>/.claude/skills/`.
- **Instructions:** global `$COPILOT_HOME/copilot-instructions.md` and
  `$COPILOT_HOME/instructions/**/*.instructions.md`; project nested `AGENTS.md`,
  `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, and
  `.github/instructions/**/*.instructions.md`.
- **MCP:** global `$COPILOT_HOME/mcp-config.json` and MCP files or inline servers
  declared by installed plugin manifests; project `<project>/.mcp.json` and
  `<project>/.github/mcp.json`.
- **Plugin source:** AgentHub scans bounded subdirectories of
  `$COPILOT_HOME/installed-plugins/`, including marketplace and `_direct` installs.
  Manifest paths are constrained to the plugin folder. This filesystem scan does not resolve
  session-specific enablement or plugins loaded directly from an external live directory.

## Cursor CLI

- **Skills:** global `~/.cursor/skills/`, `~/.cursor/skills-cursor/` (marked as managed),
  `~/.claude/skills/`, `~/.codex/skills/`, and supported Cursor plugin skill paths; project
  `<project>/**/.cursor/skills/`, `<project>/.claude/skills/`, and
  `<project>/.codex/skills/`.
- **Instructions:** global supported Cursor plugin rule files; project nested `AGENTS.md`,
  root `CLAUDE.md` and `.cursorrules`, and `**/.cursor/rules/**/*.mdc`.
- **MCP:** global `~/.cursor/mcp.json` and supported Cursor plugin MCP files; project
  `<project>/.cursor/mcp.json`.

## Grok Build

- **Skills:** global `$GROK_HOME/skills/` (default `~/.grok/skills/`), its `plugins/`
  tree, `~/.claude/skills/`, and `~/.cursor/skills/`; project nested
  `**/{.grok,.claude,.cursor}/skills/` and `<project>/.grok/plugins/`.
- **Instructions:** global `$GROK_HOME/{AGENTS.md,AGENT.md,CLAUDE.md,CLAUDE.local.md}`,
  `$GROK_HOME/rules/**/*.md`, and Claude's global `CLAUDE.md` and rules; project nested
  `AGENTS.md`, `AGENT.md`, `CLAUDE.md`, `CLAUDE.local.md`, `.grok/rules/**/*.md`,
  `.claude/rules/**/*.md`, and `.cursor/rules/**/*.{md,mdc}`.
- **MCP:** global `$GROK_HOME/config.toml`, `~/.claude.json`, and
  `~/.cursor/mcp.json`; project `<project>/.grok/config.toml`,
  `<project>/.mcp.json`, `<project>/.cursor/mcp.json`, and matching Claude project entries.

## Kiro CLI

- **Skills:** global `$KIRO_HOME/skills/` (default `~/.kiro/skills/`); project
  `<project>/.kiro/skills/`.
- **Instructions:** global `$KIRO_HOME/steering/**/*.md`; project nested `AGENTS.md` and
  `.kiro/steering/**/*.md`.
- **MCP:** global `$KIRO_HOME/settings/mcp.json`; project
  `<project>/.kiro/settings/mcp.json`.

## OpenCode

- **Skills:** global `~/.config/opencode/skills/` and `~/.claude/skills/`; project nested
  `**/{.opencode,.claude}/skills/`.
- **Instructions:** global `~/.config/opencode/AGENTS.md`; OpenCode V1 can fall back to
  `~/.claude/CLAUDE.md`. Project nested `AGENTS.md`; V1 can use `CLAUDE.md` when a
  nonempty `AGENTS.md` is absent in that directory. AgentHub checks `opencode --version`
  to disable the Claude fallback for V2; when the CLI version cannot be determined, it
  retains the V1 fallback.
- **MCP:** global `~/.config/opencode/opencode.json` or `opencode.jsonc`; project
  `<project>/opencode.json` or `opencode.jsonc`. The JSON file takes priority when both exist.

## Qwen Code

- **Skills:** global `$QWEN_HOME/skills/` (default `~/.qwen/skills/`); project
  `<project>/.qwen/skills/`.
- **Instructions:** global `$QWEN_HOME/QWEN.md`; project nested `AGENTS.md`, root
  `QWEN.md`, and `<project>/.qwen/QWEN.local.md`.
- **MCP:** global `$QWEN_HOME/settings.json`; project `<project>/.qwen/settings.json`.

## Scan behavior and limits

- Skill directories need a `SKILL.md`. Cursor and OpenCode apply additional metadata checks;
  other providers also accept a skill directory name when frontmatter is incomplete.
- Nested project-wide skill scans and instruction scans skip dependency and generated directories
  such as `node_modules`. Skill and instruction scans have a maximum depth of 12 and a budget of
  20,000 entries per scan.
- Instruction files must be nonempty. MCP configuration values are sanitized before they reach
  the UI: AgentHub displays metadata and environment variable names, not raw credentials.
- This page is maintained alongside the provider code in
  `src/main/kotlin/com/shutterstar/agenthub/environment/{skills,instructions,mcp}/discovery/`.

## Config inventory (2026-09-30)

Environment → **Config** lists existing settings files in both project and agent views. Each row
opens in the editor and supports Reveal in Files / Copy Path. Details include format, byte size,
modification time and allowlisted highlights. The summary includes the Config count.

| Agent | Global files | Project files (also nested inventory) |
|---|---|---|
| Antigravity | `~/.gemini/antigravity-cli/settings.json`, `~/.gemini/config/hooks.json` | `.agents/hooks.json` |
| Claude Code | `$CLAUDE_CONFIG_DIR/settings.json` (default `~/.claude/settings.json`), `~/.claude.json` | `.claude/settings.json`, `.claude/settings.local.json` |
| Cline | `$CLINE_DATA_DIR/globalState.json` (default `~/.cline/data/globalState.json`) | — |
| Codex | `$CODEX_HOME/config.toml` (default `~/.codex/config.toml`) | `.codex/config.toml` |
| Copilot | `$COPILOT_HOME/{settings.json,permissions-config.json,permissions-config}` (default `~/.copilot/`) | `.github/copilot/settings.json`, `.github/copilot/settings.local.json` |
| Cursor | `$CURSOR_CONFIG_DIR/cli-config.json`, or `$XDG_CONFIG_HOME/cursor/cli-config.json` on non-Windows systems when set, else `~/.cursor/cli-config.json` | `.cursor/cli.json` |
| Grok | `$GROK_HOME/config.toml` (default `~/.grok/config.toml`) | `.grok/config.toml` |
| Kiro | `$KIRO_HOME/settings/cli.json` (default `~/.kiro/settings/cli.json`) | — |
| OpenCode | `$XDG_CONFIG_HOME/opencode/{opencode.json,opencode.jsonc,cli.json}` (default `~/.config/opencode/`) | `opencode.json`, `opencode.jsonc` |
| Qwen | `~/.qwen/settings.json` | `.qwen/settings.json`, `.qwen/settings.local.json` |

Only installed agents are scanned. Nested configurations are inventory, **not a calculation of
which settings a current CLI session uses**. CLI flags, managed policy and trust can change effective
settings. Global Config is available even if an agent has no indexed projects.

The scan skips generated/dependency directories using the shared project exclusions, does not follow
symbolic links, and visits at most 20,000 entries / eight directory levels per provider. Contents are
read up to 1 MiB; empty, malformed, unreadable or larger files remain inventory without highlights.
No hooks execute and no configuration files are modified. Credentials (`auth.json`, `oauth_creds.json`,
`secrets.json`, Cline `settings/providers.json`, modern Copilot `config.json`) are excluded.

Highlights use a closed per-agent schema: recognized model identifiers, fixed permission/approval/sandbox
modes, and hook-group/plugin/rule counts. Unknown values, commands, rules, URLs, environment values and
raw content are neither shown nor persisted. Custom model identifiers outside the conservative grammar
are omitted. Explicit bypass/unrestricted settings appear in the collapsible warnings bar; they do not
block opening files or index persistence. MCP entries stay under MCP, even when the same file has a Config row.

Verified against [Claude settings](https://code.claude.com/docs/en/settings),
[Cursor CLI configuration](https://prod.cursor.com/docs/cli/reference/configuration),
[Kiro CLI settings](https://kiro.dev/docs/cli/reference/settings/),
[Copilot configuration directory](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-config-dir-reference),
[Cline storage architecture](https://github.com/cline/cline/blob/main/.clinerules/storage.md),
[OpenCode configuration](https://opencode.ai/v2/docs/config) and [CLI settings](https://opencode.ai/v2/docs/cli/config),
[Qwen settings](https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/),
[Grok configuration reference](https://github.com/xai-org/grok-build/blob/main/crates/codegen/xai-grok-pager/docs/user-guide/26-config-reference.md),
[Antigravity CLI settings](https://www.antigravity.google/docs/cli/reference/) and
[hooks](https://www.antigravity.google/docs/hooks).
