package com.shutterstar.agenthub.projects.model

data class ProjectIdentity(
    val id: String,
    val canonicalPath: String?,
    val gitRoot: String?,
    val gitRemote: String?,
)
