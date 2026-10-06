package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.model.EnvironmentWarning

internal object ProviderDiscoverySupport {
    fun <Provider, Record> collect(
        providers: List<Provider>,
        capability: String,
        scope: String,
        agentId: (Provider) -> String?,
        logFailure: (String?, String, String) -> Unit,
        discover: (Provider) -> List<Record>,
    ): Pair<List<Record>, List<EnvironmentWarning>> {
        val records = mutableListOf<Record>()
        val warnings = mutableListOf<EnvironmentWarning>()
        for (provider in providers) {
            val providerAgentId = agentId(provider)
            try {
                records += discover(provider)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                val message = failureMessage("InterruptedException")
                logFailure(providerAgentId, scope, "InterruptedException")
                warnings += EnvironmentWarning(capability, providerAgentId, scope, message)
                break
            } catch (error: Exception) {
                val errorType = error.javaClass.simpleName
                logFailure(providerAgentId, scope, describe(error))
                warnings += EnvironmentWarning(capability, providerAgentId, scope, failureMessage(errorType))
            } catch (error: NotImplementedError) {
                // A file system provider that lacks an operation (the IDE's WSL one) must cost one provider, not the whole scan.
                val errorType = error.javaClass.simpleName
                logFailure(providerAgentId, scope, describe(error))
                warnings += EnvironmentWarning(capability, providerAgentId, scope, failureMessage(errorType))
            }
        }
        return records to warnings
    }

    private fun failureMessage(errorType: String): String = "Discovery failed: $errorType"

    /** The error type and where it came from (class, method, line - no paths or values), for the log. */
    private fun describe(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.shutterstar.") } ?: error.stackTrace.firstOrNull()
        val detail = error.message?.take(80)?.takeIf { error is NotImplementedError }?.let { ": $it" }.orEmpty()
        return error.javaClass.simpleName + detail + (frame?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "")
    }
}
