package com.shutterstar.agenthub.environment.skills.sync.undo

/** Read-only description shown before a persisted operation is reversed. */
internal data class UndoPreview(
    val operationId: String,
    val skillId: String,
    val affectedAgents: Set<String>,
    val affectedPaths: List<String>,
    val backupCount: Int,
)
