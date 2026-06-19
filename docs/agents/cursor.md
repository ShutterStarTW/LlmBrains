# Cursor CLI - by Cursor

## Installation

```shell
# Install with curl (macOS / Linux)
curl https://cursor.com/install -fsS | bash

# Windows (PowerShell)
irm 'https://cursor.com/install?win32=true' | iex

# Update to latest version
cursor-agent update

# Uninstall
rm -f ~/.local/bin/cursor-agent ~/.local/bin/agent && rm -rf ~/.local/share/cursor-agent
```

> via [cursor.com/cli](https://cursor.com/cli)


## Get Version

```
% cursor-agent --version
```

## Usage

The Cursor CLI runs Cursor's AI agents directly from the terminal, GitHub Actions, and
automation scripts. It can plan, search, and build code with access to frontier models,
and supports both an interactive agent mode and non-interactive scripting.

```
% cursor-agent
# interactive agent mode:
#   /model    switch between AI models
#   @files    reference files
#   !shell    run shell commands
```

## Features

- Access to the latest models (Anthropic, OpenAI, Gemini, Cursor, xAI)
- Interactive agent mode with `/commands`, `@files`, and `!shell`
- Scriptable for automation (docs updates, security reviews, etc.)
- Shell mode with safety checks
- GitHub Actions integration for CI/CD workflows
