# MiMo Code - by Xiaomi

## Installation

```shell
# Install with npm
npm install -g @mimo-ai/cli

# Update to latest version
npm update --quiet --no-fund -g @mimo-ai/cli

# Uninstall
npm uninstall -g @mimo-ai/cli
```

> via [mimo.xiaomi.com/mimocode](https://mimo.xiaomi.com/mimocode)


## Get Version

```
% mimo --version
```

## Usage

MiMo Code is a terminal-native AI coding assistant that reads and writes code, runs
commands, manages Git, and keeps persistent memory across sessions. It ships multiple
agents and intelligent context management stored under `.mimocode/`.

```
% mimo
# launches the interactive terminal interface
#   Tab       switch between agents (build, plan, compose)
#   /goal     set a stopping condition for autonomous work
#   /dream    extract persistent knowledge from recent sessions
#   /distill  package repeated workflows into reusable skills
```

## Features

- Multiple agents: build (full access), plan (read-only), compose (orchestration)
- Persistent memory: project knowledge, checkpoints, and task progress in `.mimocode/`
- Automatic context checkpoints and reconstruction near token limits
- Tree-shaped task tracking (`T1`, `T1.1`, …) preserved across sessions
- Parallel subagents with lifecycle tracking
- Provider flexibility: MiMo Auto, Xiaomi MiMo Platform, custom OpenAI-compatible APIs
