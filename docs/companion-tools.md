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
