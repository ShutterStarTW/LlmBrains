# ForgeCode - by Antinomy

## Installation

```shell
# Install with curl (macOS / Linux)
curl -fsSL https://forgecode.dev/cli | sh

# Windows (via WSL)
wsl bash -c "curl -fsSL https://forgecode.dev/cli | sh"

# Update to latest version
forge update

# Uninstall
rm -f $(which forge) && rm -rf ~/.forge
```

> via [forgecode.dev](https://forgecode.dev)


## Get Version

```
% forge --version
```

## Usage

ForgeCode is a terminal-native CLI coding agent that integrates directly into your ZSH
shell — type `:` to talk to ForgeCode while keeping your existing aliases and Oh My Zsh
plugins working. It uses a multi-agent architecture and a fast context engine for large
codebases.

```
% forge
# or, inside ZSH, type ':' followed by your request
```

## Features

- ZSH-native — invoke with `:` inside your terminal
- Multi-agent architecture (FORGE, MUSE, SAGE) for research, planning, execution
- 100+ LLM providers (Anthropic, OpenAI, Google, DeepSeek, Mistral, Meta)
- Fast context engine for large codebases without bloating the context window
- Skills system for reusable workflows
