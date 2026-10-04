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
}

class SharedStorageException(message: String) : IllegalStateException(message)
