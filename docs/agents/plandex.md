# Plandex - by Plandex

## Installation

```shell
# Install with curl (macOS / Linux)
curl -sL https://plandex.ai/install.sh | bash

# Windows (via WSL)
wsl bash -c "curl -sL https://plandex.ai/install.sh | bash"

# Update to latest version
plandex upgrade

# Uninstall
rm -f $(which plandex) $(which pdx)
```

> via [plandex.ai](https://plandex.ai)


## Get Version

```
% plandex --version
```

## Usage

Plandex is an open-source terminal AI coding agent built for large projects. It plans and
executes complex tasks across many files, keeping changes in a sandbox until you apply
them. Start it in your project directory with `plandex` (or the short `pdx` alias).

```
% plandex      # start the REPL in your project
% pdx          # short alias
```

## Features

- 2M-token effective context window with smart file loading
- Fast project maps via tree-sitter (30+ languages)
- Configurable autonomy from full automation to fine-grained control
- Sandboxed changes with branches and full revision tracking
- Automated debugging of terminal commands and browser apps
- Git integration with automatic commit messages
