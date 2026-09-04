package com.shutterstar.agenthub.environment.instructions.model

data class InstructionSource(
    val path: String,
    val scope: InstructionScope,
    val agentIds: Set<String>,
    val type: InstructionType,
    val projectName: String? = null,
)
