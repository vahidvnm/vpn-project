package com.vpnproject.app.core

enum class NetworkType {
    WIFI,
    MOBILE,
    OTHER,
    UNKNOWN
}

data class NetworkKey(
    val type: NetworkType,
    val identifier: String = "unknown"
)

data class EndpointHealthState(
    val endpointKey: String,
    val networkKey: NetworkKey,
    val lastGoodIp: String? = null,
    val lastSuccessAtEpochMs: Long? = null,
    val lastFailureAtEpochMs: Long? = null,
    val failureCount: Int = 0,
    val lastLatencyMs: Long? = null
)

/** In-memory per-network learning cache. Encrypted persistence is a later phase. */
class RouteHealthCache(
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    private val states = mutableMapOf<String, EndpointHealthState>()

    @Synchronized
    fun stateFor(endpoint: EndpointCandidate, networkKey: NetworkKey): EndpointHealthState? =
        states[stateKey(endpoint, networkKey)]

    @Synchronized
    fun record(
        originalEndpoint: EndpointCandidate,
        networkKey: NetworkKey,
        result: HealthResult
    ): EndpointHealthState {
        val key = stateKey(originalEndpoint, networkKey)
        val previous = states[key]
        val updated = if (result.reachable) {
            EndpointHealthState(
                endpointKey = endpointKey(originalEndpoint),
                networkKey = networkKey,
                lastGoodIp = result.candidate.host,
                lastSuccessAtEpochMs = result.checkedAtEpochMs.takeIf { it > 0L } ?: nowEpochMs(),
                lastFailureAtEpochMs = previous?.lastFailureAtEpochMs,
                failureCount = 0,
                lastLatencyMs = result.latencyMs
            )
        } else {
            EndpointHealthState(
                endpointKey = endpointKey(originalEndpoint),
                networkKey = networkKey,
                lastGoodIp = previous?.lastGoodIp,
                lastSuccessAtEpochMs = previous?.lastSuccessAtEpochMs,
                lastFailureAtEpochMs = result.checkedAtEpochMs.takeIf { it > 0L } ?: nowEpochMs(),
                failureCount = (previous?.failureCount ?: 0) + 1,
                lastLatencyMs = previous?.lastLatencyMs
            )
        }
        states[key] = updated
        return updated
    }

    @Synchronized
    fun scoreFor(
        resolved: ResolvedEndpointCandidate,
        networkKey: NetworkKey,
        latencyMs: Long?
    ): Int? {
        val state = stateFor(resolved.endpoint, networkKey)
        return HealthScorer.score(
            latencyMs = latencyMs,
            recentFailureCount = state?.failureCount ?: 0,
            wasLastGood = state?.lastGoodIp == resolved.ip
        )
    }

    private fun stateKey(endpoint: EndpointCandidate, networkKey: NetworkKey): String =
        "${networkKey.type}:${networkKey.identifier}:${endpointKey(endpoint)}"

    private fun endpointKey(endpoint: EndpointCandidate): String =
        "${endpoint.protocol}:${endpoint.host.lowercase()}:${endpoint.port}"
}
