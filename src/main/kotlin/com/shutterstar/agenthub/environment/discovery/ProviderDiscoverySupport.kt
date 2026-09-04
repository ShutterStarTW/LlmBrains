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
                logFailure(providerAgentId, scope, errorType)
                warnings += EnvironmentWarning(capability, providerAgentId, scope, failureMessage(errorType))
            }
        }
        return records to warnings
    }

    private fun failureMessage(errorType: String): String = "Discovery failed: $errorType"
}
