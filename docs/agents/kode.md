# Kode - by shareAI-lab

## Installation

```shell
# Install with npm
npm install -g @shareai-lab/kode

# Update to latest version
npm update --quiet --no-fund -g @shareai-lab/kode

# Uninstall
npm uninstall -g @shareai-lab/kode
```

> via [github.com/shareAI-lab/Kode-cli](https://github.com/shareAI-lab/Kode-cli)

> **Note:** Kode runs in YOLO mode by default (equivalent to `--dangerously-skip-permissions`).
> Use `kode --safe` to enable permission checks on important projects.

## Get Version

```
% kode --version
```

## Usage

Kode is an open-source AI assistant that lives in your terminal. It understands your codebase,
edits files, runs commands, and handles entire workflows. It supports the AGENTS.md standard and
an `@`-mention system for consulting specific models or delegating to sub-agents.

```
% cd your-project
% kode
# describe a task in natural language and let the agent work
```

## Features

- Open-source, terminal-native coding agent
- `@ask-<model>` to consult models, `@run-agent-<name>` to delegate to sub-agents
- AGENTS.md standard support (plus legacy `.claude` compatibility)
