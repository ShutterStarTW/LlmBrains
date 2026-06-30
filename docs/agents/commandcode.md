# Command Code

## Installation

```shell
# Install with npm
npm install -g command-code

# Update to latest version
npm update --quiet --no-fund -g command-code

# Uninstall
npm uninstall -g command-code
```

> via [commandcode.ai](https://commandcode.ai) &middot; [github.com/CommandCodeAI/command-code](https://github.com/CommandCodeAI/command-code)

> **Windows:** Command Code is hidden on Windows in AgentHub because its launch command is `cmd`,
> which collides with the built-in Windows `cmd.exe`.

## Get Version

```
% cmd --version
```

## Usage

Command Code is a CLI-first coding agent (in the same category as Claude Code) that continuously
learns your coding taste and builds a profile in `.commandcode/taste/`.

```
% cd your-project
% cmd
# describe a task in natural language and let the agent work
```

## Features

- Continuous reinforcement learning of your coding patterns and preferences
- Custom `/agents` and persistent `/memory` across sessions
- Built-in tools: file ops, shell, agentic `/review`, web search, MCP, sub-agents, checkpoints
