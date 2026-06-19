# LeanCTL - by LeanCTL

## Installation

```shell
# Install with npm
npm install -g leanctl-bin

# Update to latest version
npm update --quiet --no-fund -g leanctl-bin

# Uninstall
npm uninstall -g leanctl-bin
```

> via [leanctl.com](https://leanctl.com)


## Get Version

```
% leanctl --version
```

## Usage

LeanCTL is a terminal-native AI coding agent written in Rust that reads, writes, tests,
and commits code. Its signature feature is **LeanCTX compression** — automatic token
optimization that cuts API costs by 68–80% with no configuration.

```
% leanctl init        # configure your AI provider and API key
% leanctl             # launch the interactive TUI
#   /model       switch AI providers mid-session
#   /benchmark   token analysis for a file
#   /compact     compact conversation history
```

## Features

- LeanCTX compression — 8 automatic modes for major token savings
- Bring-your-own-key: Claude, GPT, DeepSeek, Ollama, Groq, OpenAI-compatible APIs
- 23 built-in tools (file ops, git, web search, MCP support)
- Full-screen TUI with syntax highlighting and diff previews
- Local SQLite session memory
- Adaptive "thinking steering" that tunes reasoning depth per task
