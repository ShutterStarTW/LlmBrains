package com.shutterstar.agenthub.storage

/** Updates are read/modify/write transactions. Snapshots must not be changed by their caller. */
interface StateStore<S : Any> {
    val persistent: Boolean get() = false
    /** Captures the runtime for work that completes asynchronously. */
    fun bound(): StateStore<S> = this
    fun partition(): String = ""
    fun checkWritable() = Unit
    fun snapshot(): S
    fun update(transform: (S) -> S): S
    fun stamp(): String
    /**
     * Changes only when the data was written by someone else (another IDE or process); this store's
     * own [update] calls do not move it. Use it to detect external changes without reacting to our own saves.
     */
    fun externalStamp(): String = stamp()
}

class MemoryStateStore<S : Any>(private var state: S) : StateStore<S> {
    private var generation = 0L
    @Synchronized override fun snapshot(): S = state
    @Synchronized override fun update(transform: (S) -> S): S {
        state = transform(state)
        generation++
        return state
    }
    @Synchronized override fun stamp(): String = generation.toString()
    /** Nobody else can write an in-memory store. */
    override fun externalStamp(): String = "memory"
}

class SharedStorageException(message: String) : IllegalStateException(message)
