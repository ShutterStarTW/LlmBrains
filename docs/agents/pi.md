# Pi - by Mario Zechner

## Installation

```shell
# Install with npm
npm install -g @mariozechner/pi-coding-agent

# Update to latest version
npm update --quiet --no-fund -g @mariozechner/pi-coding-agent

# Uninstall
npm uninstall -g @mariozechner/pi-coding-agent
```

> via [pi.dev](https://pi.dev) &middot; [github.com/earendil-works/pi](https://github.com/earendil-works/pi)

## Get Version

```
% pi --version
```

## Usage

```
% cd your-project
% pi
# interactive TUI session with read/write/edit/bash tools

% pi -p "What files are in the current directory?"
# non-interactive print mode
```

## Features

- Minimal, opinionated terminal coding agent harness
- Multi-provider (Anthropic, OpenAI, Google and more)
- Interactive TUI with autocomplete, streaming, tree-based branching, resume and auto-save
- Extensible via TypeScript extensions, Skills, prompt templates and themes, shareable as Pi packages
- Interactive, print, JSON, RPC and SDK modes
