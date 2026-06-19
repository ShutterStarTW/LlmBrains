# Kiro CLI - by Kiro

## Installation

```shell
# Install with curl
curl -fsSL https://cli.kiro.dev/install | bash

# Update to latest version
kiro-cli update

# Uninstall
kiro-cli uninstall || rm -f ~/.local/bin/kiro-cli ~/.local/bin/kiro-cli-chat
```

> via [kiro.dev/cli](https://kiro.dev/cli/)


## Get Version

```
% kiro-cli --version
```

## Usage

Kiro CLI is the terminal interface for the Kiro agentic IDE, enabling spec-driven development and automated task execution from the command line.

```
Usage: kiro-cli [options] [command]

Kiro CLI - Spec-driven AI coding agent

Commands:
  update     Update Kiro CLI to the latest version

Options:
  -v, --version      Output the version number
  -h, --help         Display help for command
```

## Features

- Spec-driven development: define requirements, let Kiro implement them
- Autonomous task execution from the terminal
- Integration with the Kiro IDE
- Hooks system for workflow automation