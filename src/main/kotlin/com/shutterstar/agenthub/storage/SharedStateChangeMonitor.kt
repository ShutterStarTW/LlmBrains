package com.shutterstar.agenthub.storage

/** Baselines the first observation; subsequent observations identify external state changes. */
class SharedStateChangeMonitor {
    private var lastStamp: String? = null

    @Synchronized
    fun observe(stamp: String): Boolean {
        val changed = lastStamp != null && lastStamp != stamp
        lastStamp = stamp
        return changed
    }
}
