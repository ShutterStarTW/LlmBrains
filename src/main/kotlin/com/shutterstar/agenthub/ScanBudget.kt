package com.shutterstar.agenthub

/** Bounded entry counter shared by every filesystem scan (sessions, skills, instructions, MCP plugins). */
class ScanBudget(
    private var remaining: Int,
) {
    fun hasRemaining(): Boolean = remaining > 0

    fun remaining(): Int = remaining.coerceAtLeast(0)

    fun consume(count: Int = 1): Boolean {
        if (remaining < count) return false
        remaining -= count
        return true
    }
}
