package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import java.nio.file.Path

interface FileLinkStrategy {
    fun canLink(source: Path, target: Path): Boolean

    fun createLink(source: Path, target: Path): LinkResult
}

sealed interface LinkResult {
    data class Success(val effectiveMode: EffectiveSyncMode) : LinkResult

    data class Failure(val reason: String) : LinkResult
}
