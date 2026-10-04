# Session statistics

AgentHub reads session metadata from the agents' own files and read-only databases. Measurements are saved in AgentHub's project index and refreshed together with session discovery. No TokenTracker installation, cache or API is required.

## Available fields

Info rows show only nonempty text and positive numeric measurements. Missing and zero-valued measurements are hidden in both the details and compact summary; measured zeros remain in the index.

| Provider | Native source | Additional session details |
| --- | --- | --- |
| Claude | Project JSONL transcripts | Custom or AI title, models, input/output/cache/reasoning tokens, context input, usage responses, tools and errors, edit prompts, subagent calls, timing |
| Codex | Rollout JSONL and session index | Thread title, models, cumulative input/output/cache/reasoning tokens, context/window, tools, edit prompts, subagent calls, timing |
| Grok | `updates.jsonl`, summary and chat history | Models, reported input/output/cache/reasoning tokens, recorded complete cost, API time, tools/errors and timing |
| Qwen | Saved chat JSONL | Native manual/automatic title, models, reported tokens/cache/reasoning, context/window, tools/errors, edit prompts and timing |
| Copilot | Session `events.jsonl` | Models, per-call and shutdown token totals, cache/reasoning, API time, model/tool errors, tool activity, modified-file and line counts, premium requests and nano AI units |
| Cline | Session messages JSON, located through session JSON or SQLite | Models, terminal-turn input/output/cache metrics, recorded complete or partial cost, tools/errors, edit prompts and event timing |
| OpenCode | Read-only SQLite message/part tables; legacy message JSON | Models, input/output/cache/reasoning tokens, recorded complete or partial cost and timing. SQLite tool parts also supply tool/error and edit-prompt counts |
| Kiro | Session JSON and adjacent transcript JSONL | Routed models when recorded, tools/errors, edit prompts, context-window size, reported context percentage and elapsed time |
| Cursor | `meta.json` and `prompt_history.json` | Native title, prompt count, consecutive repeated prompts and elapsed time. The binary conversation store is not decoded |
| Antigravity | Conversation metadata and transcript steps | Native title, prompts, tools, edit prompts and event timing. Inspected transcript steps do not provide token usage or model pricing |

All providers retain their existing project/session IDs, source locations, recorded dates and first-prompt fallback where available. Consecutive repeated prompts are counted from actual user input; the count does not imply that the previous response failed.

## Accounting

- Claude response usage is deduplicated by message and request ID; tool calls by tool ID.
- Codex cumulative usage replaces earlier totals instead of being added repeatedly.
- Qwen, Cline and Codex input counters include cached input. The details label makes this explicit; cache tokens are not added again to the total.
- OpenCode keeps reasoning separate from ordinary output. AgentHub includes it in displayed output and retains the reasoning breakdown. Explicit native token totals take precedence over derived sums.
- Copilot per-call prompt counts include cache reads. AgentHub splits that category and prefers the native cumulative shutdown token breakdown when present. Premium requests and nano AI units are not converted into USD.
- Recorded USD amounts are the agent's own recorded figures, which may be estimates rather than invoices. An incomplete Cline/OpenCode sum is labelled as partial. AgentHub does not fetch model prices to invent missing costs.
- Active time sums positive gaps between recorded events of at most 30 minutes. It is an event-based measure, not model API duration. Cursor only exposes elapsed time. Kiro omits active time because its inspected transcript does not timestamp every assistant event.
- Kiro's reported context percentage is rounded to two decimal places. Context occupancy is not treated as billable token usage.

Only allowlisted model identifiers and numeric measurements are persisted as statistics. Message text, tool arguments, connection details and credentials are not stored in the statistics map.

## Verification

Run `./gradlew runProjectDiscovery --args=--statistics` to inspect which measurement fields are present for local sessions without printing transcript text. Unit tests cover native format fixtures, duplicate usage, cumulative counters, token categories, costs, titles, persistence and session-detail selection.

Native format references: [Qwen chat recording](https://github.com/QwenLM/qwen-code/blob/main/packages/core/src/services/chatRecordingService.ts), [Cline messages contract](https://github.com/cline/cline/blob/main/sdk/packages/core/docs/messages-contract-v1.md), [Copilot usage metrics](https://docs.github.com/en/copilot/how-tos/copilot-sdk/features/usage-and-billing), [OpenCode session messages](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/message-v2.ts).
