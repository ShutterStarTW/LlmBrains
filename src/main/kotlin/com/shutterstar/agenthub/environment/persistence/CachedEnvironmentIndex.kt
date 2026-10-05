package com.shutterstar.agenthub.environment.persistence

import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.storage.StateStore
import java.util.concurrent.TimeUnit

/**
 * Reads an [EnvironmentIndexState] store without copying and decoding the whole index on every call:
 * the decoded view is rebuilt only when the store's stamp moves. Writes are skipped when a project's
 * content is unchanged, so repeated scans do not rewrite the shared file or look like external changes.
 */
internal class CachedEnvironmentIndex(private val store: StateStore<EnvironmentIndexState>) {
    private class View(
        val stamp: String,
        val state: EnvironmentIndexState,
        val decoded: Map<String, ProjectEnvironment>,
    )

    @Volatile
    private var view: View? = null

    private fun currentView(): View {
        val stamp = store.stamp()
        view?.takeIf { it.stamp == stamp }?.let { return it }
        val state = store.snapshot()
        return View(stamp, state, EnvironmentIndexStateMapper.decode(state)).also { view = it }
    }

    fun decoded(): Map<String, ProjectEnvironment> = currentView().decoded

    /** Returns false when the project was already stored with identical content (and a recent timestamp). */
    fun record(updated: EnvironmentIndexProjectState, nowMillis: Long, maxProjects: Int): Boolean {
        val existing = currentView().state.projects.firstOrNull { it.projectId == updated.projectId }
        if (existing != null &&
            nowMillis - existing.refreshedAtEpochMillis < TimeUnit.HOURS.toMillis(REFRESH_STAMP_HOURS) &&
            existing == updated.copy(refreshedAtEpochMillis = existing.refreshedAtEpochMillis)
        ) {
            return false
        }
        store.update { previous ->
            val projects = previous.projects
                .filterNot { it.projectId == updated.projectId }
                .plus(updated)
                .sortedByDescending { it.refreshedAtEpochMillis }
                .take(maxProjects)
                .toMutableList()
            EnvironmentIndexState(projects = projects)
        }
        return true
    }

    private companion object {
        /** Unchanged content still gets its timestamp renewed this often, so least-recently-used eviction stays meaningful. */
        const val REFRESH_STAMP_HOURS = 1L
    }
}
