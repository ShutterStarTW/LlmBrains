# ForgeCode - by Antinomy

## Installation

```shell
# Install (macOS / Linux / Windows; Node.js required)
npm install -g forgecode

# Update to latest version
npm update -g forgecode

# Uninstall
npm uninstall -g forgecode
```

> Official npm wrapper: [antinomyhq/npm-forgecode](https://github.com/antinomyhq/npm-forgecode); the postinstall step downloads the native binary for your platform. The former `curl -fsSL https://forgecode.dev/cli | sh` installer returns 404.


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
