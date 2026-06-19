# Cody CLI - by Sourcegraph

## Installation

```shell
# Install with npm
npm install -g @sourcegraph/cody

# Update to latest version
npm update --quiet --no-fund -g @sourcegraph/cody

# Uninstall
npm uninstall -g @sourcegraph/cody
```

> via [sourcegraph.com/cody](https://sourcegraph.com/cody)


## Get Version

```
% cody --version
```

## Usage

Cody is Sourcegraph's AI coding assistant. It uses the latest LLMs together with your
development context — including Sourcegraph's code search across your repositories — to
help you understand, write, and fix code. The CLI brings Cody chat and context to the
terminal.

```
% cody
# chat with Cody about your codebase from the terminal
```

## Features

- Chat with AI about your code, generate code, and edit code
- Codebase context powered by Sourcegraph's Search API
- Context filters to exclude repositories
- Customizable prompts for recurring workflows
- Works alongside the VS Code, JetBrains, and Visual Studio extensions
