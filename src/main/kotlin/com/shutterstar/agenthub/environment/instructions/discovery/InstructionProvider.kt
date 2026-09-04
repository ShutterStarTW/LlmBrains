package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject

interface InstructionProvider {
    val agentId: String

    fun discoverGlobal(): List<InstructionSource>

    fun discoverProject(project: DiscoveredProject): List<InstructionSource>
}
