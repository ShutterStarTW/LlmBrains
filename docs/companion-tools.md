# Companion Tools

Companion tools are **not coding agents** but CLI utilities that work alongside them — usage/cost
tracking, context packing, skill management. AgentHub launches, detects, installs and updates them
through the same flow as agents, but they live in their own **Companion Tools** section in the
toolbar dropdown and in **Settings → Tools → AgentHub**, and are **off by default** (opt-in).

Enable the ones you want from the Companion Tools table in Settings; once installed they appear in
the toolbar dropdown under a *Companion Tools* heading.

---

## ccusage — by ryoppippi

Token & cost usage analysis for coding-agent CLIs, read from local log files (Claude Code, Codex,
OpenCode, Amp, Droid, Codebuff/Freebuff, Hermes and more). Privacy-first: nothing is uploaded.

```shell
# Install
npm install -g ccusage

# Update
npm update --quiet --no-fund -g ccusage

# Uninstall
npm uninstall -g ccusage
```

> via [ccusage.com](https://ccusage.com) · [github.com/ryoppippi/ccusage](https://github.com/ryoppippi/ccusage)

```
% ccusage --version
% ccusage          # daily / weekly / monthly / session usage report
```

---

## Claude Code Router — by musistudio

Routes Claude Code (and compatible clients) requests to other model providers you choose, with
API-key rotation and usage statistics.

```shell
# Install
npm install -g @musistudio/claude-code-router

# Update
npm update --quiet --no-fund -g @musistudio/claude-code-router

# Uninstall
npm uninstall -g @musistudio/claude-code-router
```

> via [ccrdesk.top](https://ccrdesk.top) · [github.com/musistudio/claude-code-router](https://github.com/musistudio/claude-code-router)

```
% ccr --version
% ccr code         # start Claude Code routed through ccr
```

---

## Claude-Code-Usage-Monitor — by Maciek-roboblog

Real-time, predictive token & cost monitor for Claude Code: forecasts limits, shows session
summaries and rate-limit tracking directly in the terminal.

```shell
# Install
pip install claude-monitor

# Update
pip install --upgrade --upgrade-strategy eager claude-monitor

# Uninstall
pip uninstall -y claude-monitor
```

> via [github.com/Maciek-roboblog/Claude-Code-Usage-Monitor](https://github.com/Maciek-roboblog/Claude-Code-Usage-Monitor)

```
% claude-monitor --version
% claude-monitor   # live usage dashboard with forecasting
```

---

## code2prompt — by mufeedvh

Converts a codebase into a single LLM prompt with source tree, Handlebars templating, git-diff
inclusion and token counting. Written in Rust.

```shell
# Install
cargo install code2prompt

# Update
cargo install code2prompt --force

# Uninstall
cargo uninstall code2prompt
```

> via [code2prompt.dev](https://code2prompt.dev) · [github.com/mufeedvh/code2prompt](https://github.com/mufeedvh/code2prompt)

```
% code2prompt --version
% code2prompt .    # generate a prompt from the current directory
```

---

## CodeGrab — by epilande

Interactive TUI repo-context packer: pick files with glob-filtering and gitignore support, output
as markdown/plain/XML with token estimates and secret redaction.

```shell
# Install
brew install epilande/tap/codegrab

# Update
brew upgrade codegrab

# Uninstall
brew uninstall codegrab
```

> via [github.com/epilande/codegrab](https://github.com/epilande/codegrab)

```
% grab --version
% grab             # launch the interactive TUI file picker
```

---

## CodeRabbit CLI — by CodeRabbit

AI code reviews directly in the terminal on staged/unstaged/committed diffs, with line-by-line
feedback; supports a `--prompt-only` mode for handing findings to other agents. Not available on
Windows yet.

```shell
# Install
curl -fsSL https://cli.coderabbit.ai/install.sh | sh

# Update
curl -fsSL https://cli.coderabbit.ai/install.sh | sh

# Uninstall
rm -f $(which coderabbit) $(which cr)
```

> via [coderabbit.ai/cli](https://www.coderabbit.ai/cli)

```
% coderabbit --version
% coderabbit review   # AI review of the current diff
```

---

## LiteLLM — by BerriAI

Self-hosted, OpenAI-compatible gateway in front of 100+ LLM providers, with cost tracking and
budget/guardrail enforcement.

```shell
# Install
pip install 'litellm[proxy]'

# Update
pip install --upgrade --upgrade-strategy eager 'litellm[proxy]'

# Uninstall
pip uninstall -y litellm
```

> via [litellm.ai](https://www.litellm.ai) · [github.com/BerriAI/litellm](https://github.com/BerriAI/litellm)

```
% litellm --version
% litellm --model gpt-4o   # start the proxy gateway
```

---

## Repomix — by yamadashy

Packs your entire repository into a single, AI-friendly file (with token counts and secret
filtering) that you can feed to any coding agent or LLM.

```shell
# Install
npm install -g repomix

# Update
npm update --quiet --no-fund -g repomix

# Uninstall
npm uninstall -g repomix
```

> via [repomix.com](https://repomix.com) · [github.com/yamadashy/repomix](https://github.com/yamadashy/repomix)

```
% repomix --version
% cd your-project
% repomix          # writes repomix-output.xml in the current directory
```

---

## Skills — by Vercel

Installs reusable **agent skills** (procedural instruction sets) into your coding agents. Skills
follow a shared specification and work across many CLI agents (Claude Code, Codex, Cursor and more).

```shell
# Install
npm install -g skills

# Update
npm update --quiet --no-fund -g skills

# Uninstall
npm uninstall -g skills
```

> via [skills.sh](https://www.skills.sh) · [github.com/vercel-labs/skills](https://github.com/vercel-labs/skills)

```
% skills --version
% skills add <owner/repo>    # install a skill into your agents
```

---

## TokenTracker — by TokenTracker

Local-first token & cost dashboard that auto-detects 25 AI coding tools and aggregates usage and
cost on your machine (dashboard at `localhost:7680`, plus native menu-bar / tray apps). Only token
counts and timestamps are read — never prompts or file contents.

```shell
# Install
npm install -g tokentracker-cli

# Update
npm update --quiet --no-fund -g tokentracker-cli

# Uninstall
npm uninstall -g tokentracker-cli
```

> via [tokentracker.cc](https://www.tokentracker.cc) · [github.com/mm7894215/TokenTracker](https://github.com/mm7894215/TokenTracker)

```
% tokentracker --version
% tokentracker     # opens the local usage dashboard
```

---

## Tokscale — by junhoyeo

TUI-based token & cost dashboard with a "contribution graph" and daily/monthly breakdowns across
15+ AI coding tools (OpenCode, Claude Code, OpenClaw, Pi, Codex, Gemini, Cursor and more).

```shell
# Install
npm install -g tokscale

# Update
npm update --quiet --no-fund -g tokscale

# Uninstall
npm uninstall -g tokscale
```

> via [tokscale.ai](https://tokscale.ai) · [github.com/junhoyeo/tokscale](https://github.com/junhoyeo/tokscale)

```
% tokscale --version
% tokscale         # opens the TUI usage dashboard
```
