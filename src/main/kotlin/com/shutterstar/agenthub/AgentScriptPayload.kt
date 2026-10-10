package com.shutterstar.agenthub

/** Escapes record separators; scripts decode fields only after splitting the payload. */
internal fun encodeAgentScriptField(value: String): String =
    value.replace("%", "%25").replace("|", "%7C").replace("~", "%7E")
        .replace("\r", "%0D").replace("\n", "%0A")

internal fun agentScriptPayload(agent: CodingAgent): String =
    listOf(agent.id, agent.name, agent.command, agent.versionArgs, agent.updateHint, agent.platformInstallHint)
        .joinToString("|", transform = ::encodeAgentScriptField)
