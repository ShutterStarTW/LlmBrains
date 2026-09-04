package com.shutterstar.agenthub.projects.discovery

internal class ScanBudget(
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
