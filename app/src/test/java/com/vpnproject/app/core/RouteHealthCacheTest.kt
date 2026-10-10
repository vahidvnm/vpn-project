package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteHealthCacheTest {
    @Test
    fun scorerAppliesFailurePenaltyAndLastSuccessBonus() {
        assertEquals(50, HealthScorer.score(latencyMs = 50, recentFailureCount = 0, wasLastGood = false))
        assertEquals(2050, HealthScorer.score(latencyMs = 50, recentFailureCount = 2, wasLastGood = false))
        assertEquals(0, HealthScorer.score(latencyMs = 50, recentFailureCount = 0, wasLastGood = true))
        assertNull(HealthScorer.score(latencyMs = null))
    }

    @Test
    fun doesNotReuseHealthForAnotherNetworkHandle() {
        val cache = RouteHealthCache()
        val endpoint = EndpointCandidate("vpn.example", 443, VpnProtocol.OPENVPN_TCP)
        val currentNetwork = NetworkKey(NetworkType.WIFI, "android-network-101")
        val differentNetwork = NetworkKey(NetworkType.WIFI, "android-network-202")
        val result = HealthResult(
            candidate = endpoint.copy(host = "8.8.8.8", source = CandidateSource.DNS_OVER_HTTPS),
            reachable = true,
            latencyMs = 80,
            checkedAtEpochMs = 1_000L
        )

        cache.record(endpoint, currentNetwork, result)

        assertNotNull(cache.stateFor(endpoint, currentNetwork))
        assertNull(cache.stateFor(endpoint, differentNetwork))
    }

    @Test
    fun recordsSuccessAndFailurePerNetwork() {
        var now = 1_000L
        val cache = RouteHealthCache(nowEpochMs = { now })
        val endpoint = EndpointCandidate("vpn.example", 443, VpnProtocol.OPENVPN_TCP)
        val network = NetworkKey(NetworkType.WIFI, "test-wifi")
        val success = HealthResult(
            candidate = endpoint.copy(host = "8.8.8.8", source = CandidateSource.DNS_OVER_HTTPS),
            reachable = true,
            latencyMs = 80,
            checkedAtEpochMs = now
        )

        val successState = cache.record(endpoint, network, success)
        assertEquals("8.8.8.8", successState.lastGoodIp)
        assertEquals(0, successState.failureCount)

        now += 500L
        val failure = HealthResult(
            candidate = endpoint.copy(host = "1.1.1.1", source = CandidateSource.DNS_OVER_HTTPS),
            reachable = false,
            checkedAtEpochMs = now
        )
        val failureState = cache.record(endpoint, network, failure)

        assertEquals("8.8.8.8", failureState.lastGoodIp)
        assertEquals(1, failureState.failureCount)
        assertEquals(now, failureState.lastFailureAtEpochMs)
    }
}
