# Oh My Pi - by can1357

## Installation

```shell
# Install with npm
npm install -g @oh-my-pi/pi-coding-agent

# Update to latest version
npm update --quiet --no-fund -g @oh-my-pi/pi-coding-agent

# Uninstall
npm uninstall -g @oh-my-pi/pi-coding-agent
```

> via [omp.sh](https://omp.sh) &middot; [github.com/can1357/oh-my-pi](https://github.com/can1357/oh-my-pi)

## Get Version

```
% omp --version
```

## Usage

```
% cd your-project
% omp
# interactive TUI session

% omp -p "What files are in the current directory?"
# non-interactive print mode

% omp acp
# editor protocol (ACP) mode

% omp --mode rpc
# RPC mode
```

## Features

- Coding-first fork of [Pi](pi.md) by Mario Zechner
- LSP/DAP integration, browser and desktop tools
- Subagents, sessions and memory backends
- 60+ providers, ACP support
