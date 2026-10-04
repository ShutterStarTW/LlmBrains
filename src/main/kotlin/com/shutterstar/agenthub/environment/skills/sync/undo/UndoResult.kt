package com.shutterstar.agenthub.environment.skills.sync.undo

import com.shutterstar.agenthub.environment.skills.sync.model.SyncError
import java.nio.file.Path

data class UndoResult(
    val operationId: String,
    val restoredPaths: List<Path>,
    val removedPaths: List<Path>,
    val errors: List<SyncError>,
)
