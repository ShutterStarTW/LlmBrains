# Environment discovery paths

This page lists the paths **AgentHub currently scans** for skills, instruction files, and MCP
servers. It describes AgentHub's implementation, which may cover a different set of sources than
the agent CLI itself. The list applies to the seventeen agents with environment providers.

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
  the separately marked `synced/` pack; project `<project>/.claude/skills/` and nested
  `<project>/**/.claude/skills/` (monorepo packages; a session started above a package loads
  them once Claude works on files there).
- **Instructions:** global `$CLAUDE_CONFIG_DIR/CLAUDE.md` and `rules/**/*.md`; project
  nested `CLAUDE.md`, `CLAUDE.local.md`, and `.claude/rules/**/*.md`. Project `AGENTS.md`
  is always listed, with an info line when Claude Code does not apply it. Claude Code uses it only
  as a fallback (v2.1.277+): when the project root has no `CLAUDE.md`, `.claude/CLAUDE.md`, or
  `CLAUDE.local.md`, and a nested `AGENTS.md` only when its own directory has none of them. The "Project instructions"
  setting that reads both files is not inspected. `AGENTS.override.md`, `AGENTS.local.md`
  and anything under `.agents/` are not read by Claude Code, and there is no native global
  `AGENTS.md`.
- **MCP:** global `~/.claude.json` (`$CLAUDE_CONFIG_DIR/.claude.json` when the variable is
  set); project `<project>/.mcp.json` and matching project entries in that same file.
- **Sessions:** `$CLAUDE_CONFIG_DIR/projects/` (default `~/.claude/projects/`).

## Cline

- **Skills:** global `~/.cline/skills/`; project `<project>/.cline/skills/`,
  `<project>/.clinerules/skills/`, and `<project>/.claude/skills/`.
- **Instructions:** global Markdown and text rules under `~/.cline/rules/`,
  `~/Documents/Cline/Rules/`, and `~/Cline/Rules/`; project nested `AGENTS.md`,
  `.cursorrules`, `.windsurfrules`, `.clinerules/**/*.{md,txt}`, and
  `.cline/rules/**/*.{md,txt}`.
- **MCP:** global `$CLINE_DATA_DIR/settings/cline_mcp_settings.json` (default
  `~/.cline/data/settings/cline_mcp_settings.json`); project `<project>/.cline/mcp.json`.

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

## Freebuff (`freebuff`)

Sources: the open-source [Codebuff](https://github.com/CodebuffAI/codebuff) CLI (`cli/src/utils/config-dir.ts`,
`chat-history.ts`, `chat-meta.ts`, `cli/src/project-files.ts`, `cli/src/utils/agent-dir-trust.ts`,
`common/src/constants/knowledge.ts`, `sdk/src/agents/load-mcp-config.ts`, `sdk/src/skills/load-skills.ts`). Checked
2026-10-05 against `freebuff` 0.2.16.

The config directory is `~/.config/manicode`, or `FREEBUFF_CONFIG_DIR` (an absolute path).

- **Sessions:** `<config dir>/projects/<project folder name>/chats/<chatId>/` — the chat id is the start time
  (`2026-10-05T18-07-03.513Z`). `chat-messages.json` is an array; a typed prompt has `"variant":"user"`. The folder name is
  only the *base name* of the project root, so the project path is read from `run-state.json`
  (`sessionState.fileContext.projectRoot`, the first key of the file, which can be over a megabyte); a chat whose run state
  does not name it is not listed. The first prompt comes from the `chat-meta.json` sidecar (cut to 100 characters). Chats
  without `chat-messages.json` or without a typed prompt are skipped. Resume: `freebuff --continue <chatId>` in the project.
- **Skills:** Freebuff has no skill folder of its own. It reads `<project>/.claude/skills`, `<project>/.agents/skills`,
  `~/.claude/skills` and `~/.agents/skills`; the `.agents` roots are the shared provider's, `.claude/skills` is listed as a
  compatibility root. There is no sync target.
- **Instructions:** the knowledge files `AGENTS.md`, `CLAUDE.md` and `*.knowledge.md`; per directory only the highest-priority
  one of `AGENTS.md` / `CLAUDE.md` is used (a `CLAUDE.md` next to an `AGENTS.md` is listed with an info line). User level:
  `~/.AGENTS.md`, else `~/.CLAUDE.md` (dot-prefixed).
- **MCP:** the `mcpServers` map of `mcp.json` in `<project>/.agents`, `<project>/../.agents` (a monorepo root) and `~/.agents`.
  A repository's `.agents` is only loaded after the user trusted it (or with `--trust-agents`); AgentHub lists what the files declare.
- **Config:** `settings.json` in the config directory — inventory only (`credentials.json` is not read).

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

## Junie CLI (`junie`)

Sources: the [Junie docs](https://junie.jetbrains.com/docs/) — environment variables, CLI configuration, agent skills,
guidelines and memory, MCP configuration — and the structure of the sessions on disk. Checked 2026-10-05 against Junie CLI 3419.7.

`JUNIE_HOME` replaces the default `~/.junie`.

- **Sessions:** `<junie home>/sessions/<sessionId>/summary.json` (`sessionId`, `createdAt`/`updatedAt` in milliseconds,
  `projectDir`, `lifecycle`) and `events.jsonl`, where each typed prompt is a `UserPromptEvent` (`presentablePrompt`, else
  `prompt`). `sessions/index.jsonl` repeats the summaries and is the fallback for a folder without `summary.json`. The
  Markdown `transcript.md` is not read. A session that never ran a task (nothing but an empty transcript, or an event
  stream without a prompt) is not listed. Resume: `junie --resume --session-id=<id>` in the project.
- **Skills:** `~/.junie/skills` and `<project>/.junie/skills` (the project skill wins over a user skill of the same name);
  `~/.agents/skills` and `<project>/.agents/skills` are the shared provider's. `JUNIE_SKILL_LOCATIONS`, `--skill-location`,
  extension-provided and built-in skills are not modelled.
- **Instructions:** global `~/.junie/AGENTS.md`; project `.junie/AGENTS.md`, `AGENTS.md`, `.junie/playbook.md`, the Markdown
  files of `.junie/rules/` and the legacy `.junie/guidelines.md` / `.junie/guidelines/`. Every file found is listed; which
  one Junie applies when several exist is Junie's rule and is not asserted.
- **MCP:** the `mcpServers` map of `~/.junie/mcp/mcp.json` and `<project>/.junie/mcp/mcp.json`. `JUNIE_MCP_LOCATIONS`, the
  `mcp-locations` key of `config.json` and extension-provided servers are not modelled.
- **Config:** `config.json`, `settings.json`, `allowlist.json` and `sandbox.json` in the junie home and the project's
  `.junie/config.json` — inventory only. `secure_credentials.json`, `authentication-key` and `dpapi_credentials/` are not read.

## Kilo Code CLI

Sources: the [Kilo docs](https://kilo.ai/docs/cli) (skills, MCP, CLI) and the open-source
[`Kilo-Org/kilocode`](https://github.com/Kilo-Org/kilocode) CLI (an OpenCode fork; config directories,
instruction files, session database, `--session`). Checked 2026-10-05.

- **Sessions:** the SQLite database `$XDG_DATA_HOME/kilo/kilo.db` (default `~/.local/share/kilo/`, also on
  Windows) and channel databases `kilo-<channel>.db`; same `session`/`message`/`part` schema as OpenCode,
  read-only. There is no legacy JSON layout. Resume: `kilo --session <id>`.
- **Skills:** `skills/` in `~/.config/kilo`, `~/.kilo`, `~/.kilocode` (legacy), `$KILO_CONFIG_DIR` and the VS Code
  extension's global storage (`…/Code/User/globalStorage/kilocode.kilo-code`); project `.kilo/skills` and
  `.kilocode/skills`. The Claude compatibility root `~/.claude/skills` and `.claude/skills` is read by default
  (switched off by `KILO_DISABLE_CLAUDE_CODE[_SKILLS]`, not modelled); the shared `.agents/skills` is the shared
  provider's. The sync target writes the documented `~/.kilo/skills` and `<project>/.kilo/skills`.
- **Instructions:** global `$KILO_CONFIG_DIR/AGENTS.md` or `~/.config/kilo/AGENTS.md`, falling back to
  `~/.claude/CLAUDE.md`; project nested `AGENTS.md`, and `CLAUDE.md` as a fallback when no `AGENTS.md` is in the
  same directory (a shadowed `CLAUDE.md` is listed with an info line). The deprecated `CONTEXT.md` is not read.
- **MCP:** the `mcp` key of `kilo.json` / `kilo.jsonc` in `~/.config/kilo` (also `config.json`), `~/.kilo`,
  `~/.kilocode`, `$KILO_CONFIG_DIR`, the single file `$KILO_CONFIG`, and in the project root, `.kilo/` and `.kilocode/`.
- **Config:** `kilo.json(c)`, `config.json`, `tui.json(c)` in `~/.config/kilo`; project `kilo.json(c)`,
  `.kilo/` and `.kilocode/` `kilo.json(c)`, `.kilo/tui.json(c)`.

## Kimi Code (`kimi`)

Sources: the [Kimi Code docs](https://github.com/MoonshotAI/kimi-code/tree/main/docs/en) (`configuration/data-locations.md`,
`customization/skills.md`, `customization/mcp.md`, `customization/agents.md`, `guides/sessions.md`) and the session metadata
types of the open-source CLI. Checked 2026-10-05. This is the npm `@moonshot-ai/kimi-code`; the archived Python `kimi-cli`
kept its data in `~/.kimi` and is **not** read.

The data root is `~/.kimi-code`, or `KIMI_CODE_HOME` (the generic `~/.agents` resources stay in the real home).

- **Sessions:** `<data root>/sessions/<workDirKey>/<sessionId>/state.json` (`cwd`, `title`, `createdAt`, `updatedAt`, `archived`;
  archived sessions are skipped). User turns are the `turn_begin` records (`userInput`) of `agents/main/wire.jsonl`; sub-agent
  wire files are not counted. Resume: `kimi --session <id>`.
- **Skills:** `$KIMI_CODE_HOME/skills` and `<project>/.kimi-code/skills`; the generic `~/.agents/skills` and `.agents/skills`
  are the shared provider's. Flat `<name>.md` skills, `extra_skill_dirs` and built-in skills are not modelled.
- **Instructions:** global `$KIMI_CODE_HOME/AGENTS.md`, `$KIMI_CODE_HOME/SYSTEM.md` (replaces the main agent's system prompt) and
  the generic `~/.agents/AGENTS.md`; project `AGENTS.md` files and `.kimi-code/AGENTS.md`.
- **MCP:** the `mcpServers` map of `$KIMI_CODE_HOME/mcp.json` and `<project>/.kimi-code/mcp.json`. Plugin-provided servers are not
  modelled.
- **Config:** `config.toml` and `tui.toml` in the data root — inventory only.

## Kiro CLI

- **Skills:** global `$KIRO_HOME/skills/` (default `~/.kiro/skills/`); project
  `<project>/.kiro/skills/`.
- **Instructions:** global `$KIRO_HOME/steering/**/*.md`; project nested `AGENTS.md` and
  `.kiro/steering/**/*.md`.
- **MCP:** global `$KIRO_HOME/settings/mcp.json`; project
  `<project>/.kiro/settings/mcp.json`.

## MiMo Code (`mimo`)

Sources: the open-source [MiMo Code](https://github.com/XiaomiMiMo/MiMo-Code) CLI, an OpenCode fork
(`packages/shared/src/global.ts`, `packages/cli/src/{storage/db.ts,skill/index.ts,session/instruction.ts}`). Checked
2026-10-05 against `@mimo-ai/cli` 0.1.15.

With `MIMOCODE_HOME` set the directories are `<MIMOCODE_HOME>/{data,config}`; otherwise `$XDG_DATA_HOME/mimocode`
(`~/.local/share/mimocode`) and `$XDG_CONFIG_HOME/mimocode` (`~/.config/mimocode`), on Windows too.

- **Sessions:** the SQLite database `mimocode.db` (and channel databases `mimocode-<channel>.db`) in the data directory — the
  same `session`/`message`/`part` schema as OpenCode, opened read-only. Sub-agent sessions (`parent_id`) are not filtered.
  Resume: `mimo --session <id>`.
- **Skills:** every `SKILL.md` below `skill/` and `skills/` of the config directory, `$MIMOCODE_CONFIG_DIR` and the project's
  `.mimocode/`; `.agents/skills` is the shared provider's. The `.claude`, `.codex` and `.opencode` compatibility roots are
  opt-in in MiMo (`MIMOCODE_ENABLE_*_SKILLS`, off by default) and are not listed. Built-in skills are not modelled.
- **Instructions:** global — the first existing of `$MIMOCODE_CONFIG_DIR/AGENTS.md`, `<config dir>/AGENTS.md`,
  `~/.claude/CLAUDE.md`; project — `AGENTS.md`, with `CLAUDE.md` only when no `AGENTS.md` exists or it has fewer than 500
  characters (a `CLAUDE.md` MiMo therefore ignores is listed with an info line). The deprecated `CONTEXT.md` is not modelled.
- **MCP:** the `mcp` map of `mimocode.json(c)` in the config directory, `$MIMOCODE_CONFIG_DIR`, `$MIMOCODE_CONFIG`, the project
  root and `<project>/.mimocode/` (same shape as OpenCode).
- **Config:** `mimocode.json(c)` and `tui.json(c)` in the config directory, `mimocode.json(c)` in the project root and
  `.mimocode/` — highlights as for OpenCode (model, small model, plugin count, permission mode).

## Mistral Vibe (`vibe`)

Sources: the `mistral-vibe` Python package (`vibe/core/paths/*`, `vibe/core/session/*`,
`vibe/core/config/{harness_files,models.py,vibe_schema.py}`) and `vibe --help`. Checked 2026-10-05 against `mistral-vibe` 2.25.8.

The vibe home is `~/.vibe`, or `VIBE_HOME`; `~/.agents` stays in the real home.

- **Sessions:** `<vibe home>/logs/session/session_<yyyymmdd>_<hhmmss>_<short id>/` with `meta.json` (`session_id`,
  `start_time`/`end_time`, `title`, `parent_session_id`, `archived_at`, `environment.working_directory`) and
  `messages.jsonl`. A typed prompt is `{"role": "user", "injected": false}`; harness-injected context has `"injected": true`.
  Sub-agent sessions (with a parent) and archived sessions are skipped; `active/` holds lock files only. Resume:
  `vibe --resume <sessionId>`.
- **Skills:** `<vibe home>/skills` and `<project>/.vibe/skills` (project root only, no nested scan); `~/.agents/skills` and
  `<project>/.agents/skills` are the shared provider's. `skill_paths`, plugin skills and `builtin-skills` are not modelled.
- **Instructions:** global `<vibe home>/AGENTS.md`; project `AGENTS.md` files (Vibe reads them from the project root up to
  its trust root and injects sub-directory ones when it reads a file there). Custom prompts in `.vibe/prompts/` are not modelled.
- **MCP:** the `[[mcp_servers]]` tables (`name`, `transport`, `command` as a string or list, `args`, `env`, `url`, `auth`) of
  `<vibe home>/config.toml` and `<project>/.vibe/config.toml` (applied only for trusted folders). Only environment-variable
  names are kept. Connector and plugin servers are not modelled.
- **Config:** `config.toml` (user and project) — inventory only; the `.env` file and `trusted_folders.toml` are not read.

## Oh My Pi (`omp`)

Sources: the [Oh My Pi docs](https://github.com/can1357/oh-my-pi/tree/main/docs) — `session.md`, `config-usage.md`,
`skills.md`, `mcp-config.md`, `context-files.md`, `cli-reference.md`, `environment-variables.md`. Checked 2026-10-05.

The native agent directory is `~/.omp/agent`; `PI_CONFIG_DIR` renames the `.omp` directory, `PI_CODING_AGENT_DIR` replaces the
whole agent directory for the default profile, and a named profile (`OMP_PROFILE`, else legacy `PI_PROFILE`) lives in
`~/.omp/profiles/<name>/agent`. Relocation to `$XDG_*_HOME/omp` is not modelled.

- **Sessions:** `<agent dir>/sessions/<encoded-cwd>/<timestamp>_<sessionId>.jsonl`. The directory name is a lossy encoding of
  the cwd, so the working directory is read from the `type:"session"` header (after the fixed-width `type:"title"` slot).
  Only the top-level `*.jsonl` of a bucket is a session (the same-named folders hold artifacts and sub-agent transcripts). A
  user prompt is a `type:"message"` entry with `role:"user"` and `attribution:"user"` (or none, in old files). Resume:
  `omp --resume <id>`.
- **Skills:** `skills/<name>/SKILL.md` and `managed-skills/` in the agent directory; per project `.omp/skills` and the foreign
  project roots `.claude/skills` and `.codex/skills`, which OMP loads by default. The shared `.agents/skills` is the shared
  provider's. Foreign user-level roots are opt-in in OMP; skillshare/plugin packages and custom directories are not modelled.
- **Instructions:** global `AGENTS.md` and the sticky `RULES.md` of the agent directory; project `.omp/AGENTS.md`,
  `.omp/RULES.md` and standalone `AGENTS.md` files (not those inside other dot-directories). The cross-tool context files
  (`.claude/CLAUDE.md`, `.gemini/GEMINI.md`, …) and the "nearest non-empty `.omp`" rule are not modelled.
- **MCP:** the `mcpServers` map of `mcp.json` / `.mcp.json` in the agent directory, in the project's `.omp/`, and the portable
  root `mcp.json` / `.mcp.json`. Servers OMP imports from other tools belong to those tools' providers.
- **Config:** `config.yml` / `config.yaml` in the agent directory and the project's `.omp/config.yml` — inventory only (YAML).

## OpenCode

- **Skills:** global `~/.config/opencode/skills/`, `~/.claude/skills/`, and `skills/` in the directory
  named by `$OPENCODE_CONFIG_DIR`; project nested `**/{.opencode,.claude}/skills/`.
- **Instructions:** global `~/.config/opencode/AGENTS.md`; OpenCode V1 can fall back to
  `~/.claude/CLAUDE.md`. Project nested `AGENTS.md`; V1 can use `CLAUDE.md` when a
  nonempty `AGENTS.md` is absent in that directory. AgentHub checks `opencode --version`
  to disable the Claude fallback for V2; when the CLI version cannot be determined, it
  retains the V1 fallback.
- **MCP:** global `$XDG_CONFIG_HOME/opencode/opencode.json` or `opencode.jsonc` (default
  `~/.config/opencode/`) and the same files in the directory named by `$OPENCODE_CONFIG_DIR`;
  project `<project>/opencode.json` or `opencode.jsonc` and `<project>/.opencode/opencode.json`
  or `opencode.jsonc` (OpenCode loads both). In each directory the JSON file takes priority
  when both exist. The single custom config file named by `$OPENCODE_CONFIG` is read as global.

## Qwen Code

- **Skills:** global `$QWEN_HOME/skills/` (default `~/.qwen/skills/`); project
  `<project>/.qwen/skills/`.
- **Instructions:** global `$QWEN_HOME/QWEN.md` and `$QWEN_HOME/rules/**/*.md`; project nested
  `AGENTS.md`, `QWEN.md` (one below the root carries an info line: Qwen applies it only when
  started in that directory), `<project>/.qwen/QWEN.local.md`, and `<project>/.qwen/rules/**/*.md`
  (path-based rules).
- **MCP:** global `$QWEN_HOME/settings.json`; project `<project>/.qwen/settings.json`.
- **Sessions:** the runtime directory's `projects/`: `$QWEN_RUNTIME_DIR`, else `$QWEN_HOME`,
  else `~/.qwen`.

## Native Resume commands

**Resume** in the Sessions list reopens the session in its own agent. AgentHub runs the command below in a
terminal in the project directory, with the native session ID it read from the agent's session store (IDs outside
`[A-Za-z0-9._:-]` are refused, not quoted). Checked against each CLI's `--help` on 2026-10-05.

| Agent | Command |
|---|---|
| Antigravity CLI | `agy --conversation <id>` |
| Claude Code | `claude --resume <id>` |
| Cline | `cline --id <id>` |
| Codex CLI | `codex resume <id>` |
| GitHub Copilot CLI | `copilot --resume <id>` |
| Cursor CLI | `cursor-agent --resume <id>` |
| Freebuff | `freebuff --continue <chatId>` |
| Grok Build | `grok --resume <id>` |
| Junie CLI | `junie --resume --session-id=<id>` |
| Kilo Code CLI | `kilo --session <id>` |
| Kimi Code | `kimi --session <id>` |
| Kiro CLI | `kiro-cli chat --resume-id <id>` |
| MiMo Code | `mimo --session <id>` |
| Oh My Pi | `omp --resume <id>` |
| OpenCode | `opencode --session <id>` |
| Mistral Vibe | `vibe --resume <id>` |
| Qwen Code | `qwen --resume <id>` |

## Paths found by file presence only

AgentHub lists a file when it exists at one of the paths above; that does **not** always mean the agent itself
documents the location. Treat these as "found", not as confirmed native support:

- **Antigravity CLI:** the legacy and `antigravity/` roots next to `antigravity-cli/`, and the plugin-folder
  `skills/`, `rules/` locations.
- **Codex CLI:** `.codex/skills/` (legacy; the shared `.agents/skills/` is the documented location).
- **Freebuff:** `.claude/skills` (a compatibility root of its skill loader; Freebuff has no skill folder of its own).
- **Qwen Code:** nested `QWEN.md` in subdirectories (loaded only when Qwen Code is started in that directory).
- **`CLAUDE_CONFIG_DIR`:** only the Claude Code providers follow it. Other agents' reads of `~/.claude/...`
  (Cursor, Grok, OpenCode, Cline, Copilot) are scanned at the default home, because none of them is known to
  follow the variable (checked 2026-10-05: no reference in the OpenCode, Cursor and Copilot CLI installs; Cline
  uses it only to import Claude Code sessions; the Grok binary is compressed, so inconclusive).

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
| Freebuff | `$FREEBUFF_CONFIG_DIR/settings.json` (default `~/.config/manicode/settings.json`) | — |
| Grok | `$GROK_HOME/config.toml` (default `~/.grok/config.toml`) | `.grok/config.toml` |
| Junie | `$JUNIE_HOME/{config.json,settings.json,allowlist.json,sandbox.json}` (default `~/.junie/`) | `.junie/config.json` |
| Kilo | `$XDG_CONFIG_HOME/kilo/{kilo.json,kilo.jsonc,config.json,tui.json,tui.jsonc}` (default `~/.config/kilo/`) | `kilo.json(c)`, `.kilo/kilo.json(c)`, `.kilocode/kilo.json(c)`, `.kilo/tui.json(c)` |
| Kimi Code | `$KIMI_CODE_HOME/{config.toml,tui.toml}` (default `~/.kimi-code/`) | — |
| Kiro | `$KIRO_HOME/settings/cli.json` (default `~/.kiro/settings/cli.json`) | — |
| MiMo Code | `<config dir>/{mimocode.json,mimocode.jsonc,tui.json,tui.jsonc}` (default `~/.config/mimocode/`) | `mimocode.json(c)`, `.mimocode/mimocode.json(c)`, `.mimocode/tui.json(c)` |
| Mistral Vibe | `$VIBE_HOME/config.toml` (default `~/.vibe/config.toml`) | `.vibe/config.toml` |
| Oh My Pi | `<agent dir>/{config.yml,config.yaml}` (default `~/.omp/agent/`) | `.omp/config.yml`, `.omp/config.yaml` |
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
