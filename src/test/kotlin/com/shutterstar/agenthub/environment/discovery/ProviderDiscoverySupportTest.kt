package com.shutterstar.agenthub.environment.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderDiscoverySupportTest {
    private fun collect(vararg providers: String, logged: MutableList<String> = mutableListOf(), discover: (String) -> List<String>) =
        ProviderDiscoverySupport.collect(
            providers = providers.toList(),
            capability = "test",
            scope = "global",
            agentId = { it },
            logFailure = { agent, _, detail -> logged += "$agent: $detail" },
            discover = discover,
        )

    @Test
    fun `a failing provider costs only its own records`() {
        val (records, warnings) = collect("a", "b", "c") { agent ->
            if (agent == "b") error("boom") else listOf("$agent-record")
        }

        assertEquals(listOf("a-record", "c-record"), records)
        assertEquals(listOf("b"), warnings.map { it.agentId })
        assertEquals("Discovery failed: IllegalStateException", warnings.single().message)
    }

    @Test
    fun `a file system provider that lacks an operation does not end the whole scan`() {
        // The IDE's WSL file system provider answers some options with NotImplementedError, which is an Error.
        val logged = mutableListOf<String>()

        val (records, warnings) = collect("a", "b", "c", logged = logged) { agent ->
            if (agent == "b") throw NotImplementedError("An operation is not implemented: READ + NOFOLLOW_LINKS") else listOf("$agent-record")
        }

        assertEquals(listOf("a-record", "c-record"), records)
        assertEquals("Discovery failed: NotImplementedError", warnings.single().message)
        assertTrue(logged.single().startsWith("b: NotImplementedError: An operation is not implemented: READ + NOFOLLOW_LINKS"), logged.single())
    }
}
