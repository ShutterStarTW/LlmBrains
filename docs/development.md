# Development

AgentHub is a JetBrains IDE plugin built with Gradle and the IntelliJ Platform Gradle Plugin. This page covers building the plugin from source.

## Requirements

- **JDK 17+** (a recent GraalVM or Temurin build works)
- **Gradle** — use the bundled wrapper (`gradlew` / `gradlew.bat`); no separate install needed
- A JetBrains IDE **2024.1+** (platform build `241+`) to run/test the plugin

## Build the plugin

```bash
# Produce the installable plugin ZIP
./gradlew buildPlugin
```

The output lands in `build/distributions/agenthub-<version>.zip`. Install it via
**Settings/Preferences > Plugins > ⚙ > Install Plugin from Disk…**.

## Run in a sandbox IDE

```bash
# Launch a sandbox IDE with the plugin pre-installed
./gradlew runIde
```

On the Windows development machine, run `.\gradlew.bat runIde --offline` from the repository root
in an interactive terminal and keep it running while using the sandbox IDE. Confirm the IDE window
actually opens; starting `idea64.exe` directly is not the plugin development workflow.

## Tests

```bash
./gradlew test
```

Tests target the Java 17 toolchain. Coverage uses JaCoCo 0.8.14, which supports Java 25 class
files when Gradle itself is launched by a newer local JDK.

## Shared Skills synchronization

The sync engine lives under `environment/skills/sync/`; keep it pure JDK except for the
`persistence` package and the application-scoped `SkillSyncApplicationService` composition root.
UI code must call that facade and must not mutate skill directories directly. Every new mutating
request needs a dry-run plan, optimistic revalidation, target-isolated result, audit entry, and a
recovery/undo story. Tests should use temporary real directories and injected stores/targets.

Backups and operation journals are stored below the IDE system directory in
`agenthub/skill-backups`. Never persist skill contents in IntelliJ XML state. Windows links are
junctions; copy mode stages next to the destination and atomically renames into place. WSL launch
mode is currently outside Skills discovery/sync scope, so do not infer a WSL home from launcher
settings inside a target adapter.

## Verify project discovery without the UI

On Windows, run the read-only smoke CLI to inspect the currently discovered projects,
participating agents, session counts, last activity, and provider warnings:

```powershell
pwsh -File .\src\test\scripts\run-project-discovery.ps1
```

Project paths and normalized Git remotes are hidden by default. Include them when validating
project identity and merging:

```powershell
pwsh -File .\src\test\scripts\run-project-discovery.ps1 -ShowPaths
```

The command reads bounded session data, including user prompts for counts and a first-prompt title.
It does not modify agent files or print conversation content. The AgentHub menu also exposes
**Run project discovery** as its final item while this source repository is open; it launches
the same script in an IDE terminal. The script delegates compilation and execution to the
`runProjectDiscovery` Gradle task.

## Verify MCP discovery without exposing configuration values

On Windows, run the read-only MCP smoke CLI after changing any Antigravity, Claude, Cline,
Codex, Copilot, Cursor, Grok, Kiro, OpenCode, or Qwen MCP provider:

```powershell
pwsh -File .\src\test\scripts\run-mcp-discovery.ps1
```

The command reports only provider and normalized-server counts. It deliberately hides server
names, paths, commands, arguments, URLs, and configuration values. The script delegates to the
`runMcpDiscovery` Gradle task.

## Run tests on Windows

Run the complete test suite with IDEA's bundled Java runtime and the standalone JUnit launcher:

```powershell
pwsh -File .\src\test\scripts\run-all-tests.ps1
```

For reproducible wide/narrow Swing screenshots during UI review:

```powershell
pwsh -File .\src\test\scripts\run-all-tests.ps1 -ScreenshotDir .\tmp\ui-screenshots
```

The script compiles test classes offline, then supplies the IntelliJ Platform runtime JARs used
by persistence tests. Using IDEA's bundled runtime keeps the Java class-file version aligned
with the locally installed IntelliJ Platform.

If the script stops with `No cached version of org.junit.jupiter:junit-jupiter:5.10.2 available for
offline mode` (the Gradle cache lost the JUnit aggregator artifact and Java has no network here),
compile the tests with IDEA's `kotlinc` instead and run the same JUnit launcher: build the main
classes with `gradle compileKotlin --offline`; compile every file under `src/test/kotlin` with
`kotlinc` against `build/classes/kotlin/main`, the JUnit JARs in `tmp/junit-libs` (a local, untracked folder you create; `run-all-tests.ps1` expects `junit-platform-console-standalone-1.10.2.jar` there) and the IntelliJ
platform JARs that `run-all-tests.ps1` lists, passing
`-Xfriend-paths=build/classes/kotlin/main` (without it, tests cannot see `internal` declarations)
and putting the long classpath and file list in an `@argfile` (the command line is too long for
Windows otherwise); then start `junit-platform-console-standalone execute --scan-class-path=<test
output dir>` with the same runtime classpath and `--add-opens` flags as the script.

## Verify IDE-aware project opening

The Projects tab detects installed JetBrains IDEs in the background. Select a discovered
project and check that the IDE selector marks a suitable installed product as
**recommended**. The recommendation uses project-root markers such as `build.gradle.kts`,
`composer.json`, `package.json`, `pyproject.toml`, `go.mod`, solution files, and Android
manifests. You can always override it in the selector.

Opening the current product uses the JetBrains project API. Opening another installed
product starts its launcher with the project directory as a separate argument. Detection is
limited to the current IDE, standard JetBrains/Toolbox installation locations, and launchers
available on `PATH`; it does not scan arbitrary drives.

## Code formatting

Follow the existing Kotlin style (four-space indentation and trailing commas). No ktlint Gradle
task is configured.

## Adding a new CLI agent

Every new built-in agent touches several files. Missing one of the easy-to-forget steps
(marked *) is the most common mistake when contributing a new agent — use this list as a
checklist, in order:

1. **`src/main/kotlin/com/shutterstar/agenthub/CodingAgents.kt`** — add a new `CodingAgent(...)`
   entry to the `all` list, inserted alphabetically **by `id`**. The data class requires `id`,
   `name`, `command`, `installHint`, `updateHint`, and `url`; set `uninstallHint` and `provider`
   where applicable. Optional fields include `versionArgs`,
   `installHintWindows`/`uninstallHintWindows`, `devUrl`, `unsupportedOnWindows`
   (for agents that are WSL-only or have no native Windows binary).
2. No test change is needed: `CodingAgentsTest` has no agent-count assertion.
3. **`src/main/resources/META-INF/plugin.xml`**:
   - \* the *Supported agents* list — add a new `<a href="url">Name</a> &mdash; Provider` `<li>`
     (alphabetical).
   - \* `change-notes` — add a new version block describing the agent.
   - The "40+ built-in agents" line is not an exact count and does not need to change.
4. \* **`README.md`** — add a row to the *Supported CLI Agents* table (alphabetical). The
   "40+ built-in agents" line stays as-is.
5. \* **`docs/index.md`** — the same table row.
6. \* **`docs/agents/<id>.md`** — a new agent page (copy the format of an existing one). The
   `awesome-pages` MkDocs plugin picks it up in the nav automatically — `mkdocs.yml` does not
   need editing.
7. \* **`src/main/resources/favicons/<rootDomain>.png`** — a transparent favicon. The filename
   comes from `FaviconLoader.rootDomain(url host)`: normally the **last two domain segments**
   (`freebuff.com/cli` → `freebuff.com.png`); for `.github.io`/`.gitlab.io` it's the **full
   host** (e.g. `vinhnx.github.io.png`). Source the brand logo/favicon, downscale to 64², keep
   the background transparent (color-key it if the source has a solid background). If multiple
   agents share a root domain (e.g. `github.com`), resolve the clash with the optional
   `CodingAgent.faviconKey` field — when set, `FaviconLoader` uses it as the resource key
   (`/favicons/<faviconKey>.png`) instead of the root domain (see the `kode`/`copilot` entries
   for examples).
8. `CodingAgents.defaultActiveIds` (optional) — only if the agent should be active by default on
   a fresh install (currently the top 10).
9. **`url` should only be set when the agent has its own website.** If it's just a GitHub repo
   (i.e. `url` and `devUrl` would be the same link, or `devUrl` would stay empty), set
   `url = ""` and put the link only in `devUrl` (shown in the Settings "Source" column) — leave
   the Website column empty rather than duplicating the link. In that case also set an explicit
   `faviconKey = "github.com"` (see step 7), since an empty `url` gives `FaviconLoader` nothing
   to derive a domain from. If the agent does have its own product/docs page on a domain other
   than `github.com`, keep that as `url` and put the repo in `devUrl`.

The list above covers adding the agent itself. A release additionally needs a `VERSION.md`
bump, a `CHANGES.md` line, a new `plugin.xml` change-notes block, a
`docs/blog/posts/<date>-vX.Y.Z.md` post, and a local `git tag vX.Y.Z` on the release commit —
pushed explicitly with `git push origin vX.Y.Z`, since a plain `git push` does not push tags.

### Adding a companion tool

Companion tools (usage/cost trackers, context packers, skill managers, …) are not coding
agents — they live in a separate, opt-in registry so they don't affect the agent count:

1. **`CompanionTools.kt`** — add a `CodingAgent(...)` entry to its own `all` list, alphabetically
   by `id` (not `CodingAgents.all`).
2. **`CompanionToolsTest.kt`** — bump the `companion tool count is N` assertion (and its test
   name). The "no collision with agent IDs" test protects itself automatically.
3. `plugin.xml` (companion tools list + change-notes), `README.md` / `docs/index.md`
   (Companion Tools table), `docs/companion-tools.md` (one section per tool), and a favicon.
4. `CodingAgents.all` and `defaultActiveIds` do **not** change for a companion tool.

Companion tools are automatically picked up by detection (`CodingAgents.detectable()`), appear
in the toolbar's "Companion Tools" section, and get a 🧩 marker on launch (agents get 🤖).

## Documentation

The docs site is built with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/):

```bash
pip install mkdocs-material mkdocs-awesome-pages-plugin mkdocs-rss-plugin
mkdocs serve   # preview at http://localhost:8000
```

Pushing to `main` triggers the GitHub Actions workflow, which runs `mkdocs build` and
deploys the site to GitHub Pages automatically.
