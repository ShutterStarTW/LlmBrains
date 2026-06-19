# Aider - by Aider AI

## Installation

```shell
# Install with pip
pip install aider-install && aider-install

# Update to latest version
uv tool upgrade aider-chat || pipx upgrade aider-chat

# Uninstall
uv tool uninstall aider-chat || pipx uninstall aider-chat || pip uninstall -y aider-chat
pip uninstall -y aider-install
```

> via [aider.chat](https://aider.chat)


## Get Version

```
% aider --version
aider 0.82.0
```

## Usage

```
usage: aider [-h] [--model MODEL] [--opus] [--sonnet] [--haiku]
             [--4] [--4o] [--mini] [--4-turbo] [--35turbo]
             [--deepseek] [--o1-mini] [--o1-preview]
             [--list-models [MODEL]] [--openai-api-key OPENAI_API_KEY]
             [--anthropic-api-key ANTHROPIC_API_KEY]
             [--openai-api-base OPENAI_API_BASE]
             [--openai-api-type OPENAI_API_TYPE]
             [--openai-api-version OPENAI_API_VERSION]
             [--openai-api-deployment-id OPENAI_API_DEPLOYMENT_ID]
             [--openai-organization-id OPENAI_ORGANIZATION_ID]
             [--model-settings-file MODEL_SETTINGS_FILE]
             [--model-metadata-file MODEL_METADATA_FILE]
             [--verify-ssl | --no-verify-ssl] [--edit-format EDIT_FORMAT]
             [--architect] [--weak-model WEAK_MODEL]
             [--editor-model EDITOR_MODEL]
             [--editor-edit-format EDITOR_EDIT_FORMAT]
             [--show-model-warnings | --no-show-model-warnings]
             [--max-chat-history-tokens MAX_CHAT_HISTORY_TOKENS]
             [--env-file ENV_FILE] [--input-history-file INPUT_HISTORY_FILE]
             [--chat-history-file CHAT_HISTORY_FILE]
             [--restore-chat-history | --no-restore-chat-history]
             [--llm-history-file LLM_HISTORY_FILE]
             [--dark-mode] [--light-mode] [--pretty | --no-pretty]
             [--stream | --no-stream] [--user-input-color USER_INPUT_COLOR]
             [--tool-output-color TOOL_OUTPUT_COLOR]
             [--tool-error-color TOOL_ERROR_COLOR]
             [--tool-warning-color TOOL_WARNING_COLOR]
             [--assistant-output-color ASSISTANT_OUTPUT_COLOR]
             [--code-theme CODE_THEME] [--show-diffs]
             [--git | --no-git] [--gitignore | --no-gitignore]
             [--aiderignore AIDERIGNORE] [--subtree-only]
             [--auto-commits | --no-auto-commits]
             [--dirty-commits | --no-dirty-commits]
             [--attribute-author | --no-attribute-author]
             [--attribute-committer | --no-attribute-committer]
             [--attribute-commit-message-author | --no-attribute-commit-message-author]
             [--attribute-commit-message-committer | --no-attribute-commit-message-committer]
             [--commit] [--commit-prompt COMMIT_PROMPT]
             [--dry-run | --no-dry-run] [--skip-sanity-check-repo]
             [--lint] [--lint-cmd LINT_CMD] [--auto-lint | --no-auto-lint]
             [--test-cmd TEST_CMD] [--auto-test | --no-auto-test] [--test]
             [--file FILE] [--read READ] [--vim]
             [--chat-language CHAT_LANGUAGE] [--version] [--check-update]
             [--skip-check-update] [--apply APPLY] [--apply-clipboard-edits]
             [--yes-always] [-v] [--show-repo-map] [--show-prompts]
             [--exit] [--message MESSAGE] [--message-file MESSAGE_FILE]
             [--encoding ENCODING] [-c CONFIG] [--gui] [--suggest-shell-commands]
             [--fancy-input | --no-fancy-input] [--detect-urls | --no-detect-urls]
             [--editor EDITOR]
             [FILE ...]
```

## Features

- Edits multiple files at once for large refactors
- Git-aware: auto-commits after each change
- Supports 100+ LLM models (OpenAI, Anthropic, Gemini, local models via Ollama)
- Architect mode for planning before coding
- Built-in linting and test integration