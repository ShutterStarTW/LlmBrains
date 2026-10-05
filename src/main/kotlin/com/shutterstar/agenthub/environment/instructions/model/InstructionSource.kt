package com.shutterstar.agenthub.environment.instructions.model

data class InstructionSource(
    val path: String,
    val scope: InstructionScope,
    val agentIds: Set<String>,
    val type: InstructionType,
    val projectName: String? = null,
    /**
     * Per-agent info text about this file, shown in the Environment detail strip (e.g. Claude Code
     * not applying a project `AGENTS.md` because a `CLAUDE.md` exists, or Qwen Code loading a nested
     * `QWEN.md` only when started in its directory). Never hides the file.
     */
    val agentNotes: Map<String, String> = emptyMap(),
)
