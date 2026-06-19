# Antigravity CLI - by Google

## Installation

```shell
# Install with curl (macOS / Linux)
curl -fsSL https://antigravity.google/cli/install.sh | bash

# Windows (PowerShell)
irm https://antigravity.google/cli/install.ps1 | iex

# Update to latest version
agy update

# Uninstall
rm -f ~/.local/bin/agy
```

> via [antigravity.google](https://antigravity.google/product/antigravity-cli)


## Get Version

```
% agy --version
```

## Usage

Antigravity CLI is a terminal-based AI coding agent that shares the core engine of the
Antigravity 2.0 GUI. It understands your codebase, makes multi-file edits with your
permission, and runs commands directly from the terminal — optimized for SSH and remote
sessions.

```
% agy
# launches the interactive terminal UI
#   /logout   clear saved credentials
```

## Features

- Shared core agent engine with the Antigravity 2.0 GUI
- Multi-step reasoning, multi-file editing, and tool calling
- Lightweight TUI optimized for SSH / remote sessions
- Bidirectional settings sync with the GUI app
- Session export to continue work in the full application
- Browser-based Google sign-in with SSH fallback
