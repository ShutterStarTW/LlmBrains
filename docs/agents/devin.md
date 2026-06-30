# Devin - by Cognition

## Installation

### macOS / Linux

```shell
# Install
curl -fsSL https://cli.devin.ai/install.sh | bash

# Update
devin update

# Uninstall (add --clean to also remove config/history)
devin uninstall --force
```

### Windows

```powershell
# Install
irm https://static.devin.ai/cli/setup.ps1 | iex

# or with winget
winget install --id CognitionAI.DevinCLI
```

> via [devin.ai/cli](https://devin.ai/cli) &middot; [docs.devin.ai/cli/reference/commands](https://docs.devin.ai/cli/reference/commands)

## Get Version

```
% devin --version
```

## Usage

Devin for Terminal is a local coding agent with full access to your codebase, tools and
environment, with the option to hand a session off to a Devin cloud agent that keeps working
when you close your laptop.

```
% cd your-project
% devin init
% devin run --prompt "implement the feature"
```

## Features

- Local CLI agent with deep Devin Cloud integration (local-to-cloud handoff)
- Choose any frontier model (Opus, GPT, or Cognition's SWE models)
- Works on macOS, Linux, WSL, and Windows
- Paid: requires a Devin subscription
