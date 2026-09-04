package com.shutterstar.agenthub.projects.model

import java.time.Instant

/**
 * Shared "most recently active first" sort comparators for the project/session model types.
 *
 * These were previously written as identical `compareByDescending<T> { ... }.thenBy { ... }`
 * chains repeated at 5-9 call sites across the discovery providers and the persistence mapper.
 * Each such call site compiles to its own small anonymous `Comparator` class (Kotlin inlines
 * `compareByDescending`/`thenBy`, so the resulting object is materialized per call site, not
 * shared) - defining the comparator once here and referencing it by value collapses those
 * duplicate classes into one.
 */
internal object ProjectComparators {
    val discoveredProjectByRecency: Comparator<DiscoveredProject> =
        compareByDescending<DiscoveredProject> { it.lastActivity ?: Instant.MIN }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.identity.id }

    val agentSessionByRecency: Comparator<AgentSession> =
        compareByDescending<AgentSession> { it.updatedAt ?: it.startedAt ?: Instant.MIN }
            .thenBy { it.id }

    val rawAgentProjectByRecency: Comparator<RawAgentProject> =
        compareByDescending<RawAgentProject> { it.updatedAt ?: it.startedAt ?: Instant.MIN }
            .thenBy { it.sessionId }
}
