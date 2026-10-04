# SWE-agent - by SWE-agent

## Installation

```shell
# Install with pip
pip install sweagent

# Update to latest version
pip install --upgrade --upgrade-strategy eager sweagent

# Uninstall
pip uninstall -y sweagent
```

> via [swe-agent.com](https://swe-agent.com)


## Get Version

```
% sweagent --version
```

## Usage

SWE-agent is a research project: an autonomous software engineering agent that resolves GitHub issues, writes tests and fixes bugs.

```
Usage: sweagent [options] [command]

SWE-agent - Autonomous software engineering agent

Commands:
  run        Run SWE-agent on a task or GitHub issue
  run-batch  Run SWE-agent on multiple tasks

Options:
  --version          Output the version number
  --help             Display help for command
```

## Features

- Resolves real GitHub issues autonomously
- Integrates with SWE-bench benchmark
- Supports multiple LLM backends
- Agent-Computer Interface (ACI) for tool use
- Configurable agent strategies and scaffolding
- Batch processing for multiple issues