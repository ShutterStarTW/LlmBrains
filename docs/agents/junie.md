# Junie CLI - by JetBrains

## Installation

```shell
# Install
npm install -g @jetbrains/junie

# Update
npm update -g @jetbrains/junie

# Uninstall
npm uninstall -g @jetbrains/junie
```

Other options: the installer script (`curl -fsSL https://junie.jetbrains.com/install.sh | bash`, `junie update` to update) and Homebrew.

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