package com.shutterstar.agenthub.environment.model

import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.skills.model.AgentSkill

data class AgentEnvironment(
    val agentId: String,
    val skills: List<AgentSkill>,
    val mcpServers: List<McpServer>,
    val instructions: List<InstructionSource>,
    val warnings: List<EnvironmentWarning> = emptyList(),
)
