package com.shutterstar.agenthub.environment.skills.ui

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

internal data class SkillBrowserState(
    val context: SkillBrowserContext,
    val snapshot: SkillBrowserSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Caller supplies worker execution and EDT delivery. Late results never replace a newer scope. */
internal class SkillBrowserController(
    private val executor: Executor,
    private val deliver: (() -> Unit) -> Unit,
    private val discover: (SkillBrowserContext) -> SkillBrowserSnapshot,
    private val changed: (SkillBrowserState) -> Unit,
    private val cachedSnapshot: (SkillBrowserContext) -> SkillBrowserSnapshot? = { null },
) : AutoCloseable {
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private var state: SkillBrowserState? = null

    fun refresh(context: SkillBrowserContext) {
        if (closed) return
        val ticket = generation.incrementAndGet()
        val cached = state?.takeIf { it.context.key == context.key }?.snapshot ?: runCatching { cachedSnapshot(context) }.getOrNull()
        state = SkillBrowserState(context, cached, loading = true)
        changed(state!!)
        try {
            executor.execute {
                if (closed || ticket != generation.get()) return@execute
                val result = runCatching { discover(context) }
                deliver {
                    if (!closed && ticket == generation.get()) {
                        state = result.fold(
                            { SkillBrowserState(context, it) },
                            { SkillBrowserState(context, cached, error = "Skill discovery failed (${it.javaClass.simpleName})") },
                        )
                        changed(state!!)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            state = SkillBrowserState(context, cached, error = "Skill discovery is unavailable")
            changed(state!!)
        }
    }

    override fun close() {
        closed = true
        generation.incrementAndGet()
    }
}
