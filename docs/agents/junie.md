# Junie CLI - by JetBrains

## Installation

### macOS / Linux

```shell
# Install (official installer, adds the `junie` shim to ~/.local/bin)
curl -fsSL https://junie.jetbrains.com/install.sh | bash

# Update (stages the latest build, applied on the next launch)
junie update

# Uninstall
rm -f ~/.local/bin/junie && rm -rf ~/.local/share/junie
```

### Windows

Use the official PowerShell installer, which adds the `junie` shim to `~/.local/bin` and your user PATH:

```powershell
# Install
irm https://junie.jetbrains.com/install.ps1 | iex

# Uninstall
Remove-Item -Force "$env:USERPROFILE\.local\bin\junie.bat" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$env:USERPROFILE\.local\share\junie" -ErrorAction SilentlyContinue
```

> Junie updates itself automatically on launch; `junie update` stages the latest build manually.
> After installing, restart the IDE so the updated PATH is picked up.

> via [junie.jetbrains.com](https://junie.jetbrains.com)


## Get Version

```
% junie --version
```

## Usage

Junie is JetBrains' LLM-agnostic AI coding agent that ships code from your terminal,
IDE, or CI/CD pipeline. Describe a task in natural language (fix a bug, implement a
feature, review a PR) and Junie works on it.

```
% cd your-project
% junie
# describe a task in natural language and let the agent work on your codebase
```

## Features

- LLM-agnostic: works with models from OpenAI, Anthropic, Google and Grok
- Runs from the terminal, in any IDE, and in CI/CD pipelines (GitHub, GitLab)
- Authenticates with a JetBrains account or a third-party API key
- Works on macOS, Windows, and Linux (requires Node.js)