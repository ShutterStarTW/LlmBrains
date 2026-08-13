# Muse Code - by Meta

## Installation

```shell
# Install with curl (macOS / Linux)
curl -fsSL https://dev.meta.ai/install.sh | bash

# Windows (via WSL)
wsl bash -c "curl -fsSL https://dev.meta.ai/install.sh | bash"

# Update (re-run the installer; Muse Code also auto-updates hourly)
curl -fsSL https://dev.meta.ai/install.sh | bash

# Uninstall
rm -f ~/.local/bin/muse ~/.local/bin/muse-bin-*
```

> via [dev.meta.ai/docs/muse-code](https://dev.meta.ai/docs/muse-code)


## Get Version

```
% muse --version
```

## Usage

Muse Code is Meta's terminal coding agent, powered by the Muse Spark model family. It reads
your codebase, plans changes, writes and edits code, and validates the results, coordinating
multiple persistent subagents for larger tasks. There is no native Windows build &mdash; it
runs under WSL2 on Windows.

```
% muse
# interactive terminal UI with slash commands and approvals

% muse exec "<prompt>"
# headless mode for scripts and CI
```

## Features

- Multi-agent orchestration for repo-scale software engineering tasks
- Interactive terminal UI and headless `exec` mode for CI
- OS-level sandboxing enabled from first run, with approval workflows for commands
- Project-specific skills, rules and hooks
- Browser sign-in or `META_API_KEY` for headless/CI authentication