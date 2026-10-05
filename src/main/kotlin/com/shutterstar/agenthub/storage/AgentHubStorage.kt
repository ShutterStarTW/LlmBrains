package com.shutterstar.agenthub.storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.shutterstar.agenthub.WslSupport
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Production stores use the shared home; standalone/default test services remain in memory. */
object AgentHubStorage {
    private val sharedHome by lazy {
        AgentHubHome(warn = { message ->
            LOG.warning(message)
            ApplicationManager.getApplication()?.invokeLater {
                runCatching {
                    NotificationGroupManager.getInstance().getNotificationGroup("AgentHub")
                        .createNotification("AgentHub data", message, NotificationType.WARNING).notify(null)
                }
            }
        })
    }

    fun home(): AgentHubHome? {
        val application = ApplicationManager.getApplication() ?: return null
        if (application.isUnitTestMode || System.getProperty("agenthub.test.mode") == "true") return null
        return sharedHome
    }

    fun runtimeId(): String = if (WslSupport.isActive()) "wsl-${WslSupport.settings.distro.ifBlank { "default" }}" else "host"

    fun <S : Any> state(name: String, type: Class<S>, factory: () -> S): StateStore<S> =
        home()?.let { SharedXmlStore(it, "state/$name.xml", type, factory) } ?: MemoryStateStore(factory())

    fun <S : Any> cache(name: String, type: Class<S>, factory: () -> S): StateStore<S> =
        home()?.let { RuntimeStateStore(it, name, type, factory, ::runtimeId) } ?: MemoryStateStore(factory())

    fun migrateLegacy() {
        val home = home() ?: return
        runCatching {
            LegacyStateMigration(home, Path.of(PathManager.getConfigPath()), Path.of(PathManager.getSystemPath())).migrate()
        }.onFailure {
            home.warning("AgentHub could not finish importing this IDE's previous data. The original data is preserved; import will retry at the next start.")
        }
    }

    private val LOG = Logger.getLogger(AgentHubStorage::class.java.name)
}

/** The environment can change while an IDE stays open; select the runtime on every access. */
class RuntimeStateStore<S : Any>(
    private val home: AgentHubHome,
    private val name: String,
    private val type: Class<S>,
    private val factory: () -> S,
    private val runtimeId: () -> String,
    private val statIntervalMillis: Long = 500,
) : StateStore<S> {
    override val persistent: Boolean get() = true
    private val stores = ConcurrentHashMap<String, SharedXmlStore<S>>()
    private fun current(runtime: String = runtimeId()): SharedXmlStore<S> = stores.computeIfAbsent(runtime) {
        SharedXmlStore(home, home.cachePath(it, "$name.xml"), type, factory, statIntervalMillis = statIntervalMillis)
    }
    override fun checkWritable() = current().checkWritable()
    override fun partition(): String = runtimeId()
    override fun bound(): StateStore<S> {
        val runtime = runtimeId()
        val selected = current(runtime)
        return object : StateStore<S> by selected {
            override fun stamp(): String = "$runtime:${selected.stamp()}"
            override fun externalStamp(): String = "$runtime:${selected.externalStamp()}"
        }
    }
    override fun snapshot(): S = current().snapshot()
    override fun update(transform: (S) -> S): S = current().update(transform)
    override fun stamp(): String {
        val runtime = runtimeId()
        return "$runtime:${current(runtime).stamp()}"
    }
    override fun externalStamp(): String {
        val runtime = runtimeId()
        return "$runtime:${current(runtime).externalStamp()}"
    }
}
