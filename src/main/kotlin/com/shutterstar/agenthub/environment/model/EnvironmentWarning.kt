package com.shutterstar.agenthub.environment.model

data class EnvironmentWarning(
    val capability: String,
    val agentId: String?,
    val scope: String,
    val message: String,
)
