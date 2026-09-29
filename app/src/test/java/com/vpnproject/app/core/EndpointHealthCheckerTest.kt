package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EndpointHealthCheckerTest {
    @Test
    fun bestEffortSkipsUdpOnlyProtocolsUntilEngineProbeExists() {
        val checker = EndpointHealthChecker(nowEpochMs = { 123L })
        val resolved = ResolvedEndpointCandidate(
            endpoint = EndpointCandidate("wg.example", 51820, VpnProtocol.WIREGUARD),
            ip = "8.8.8.8",
            provider = DohProvider.GOOGLE,
            ttlSeconds = 60,
            expiresAtEpochMs = 60_000L
        )

        val result = checker.checkBestEffort(resolved)

        assertFalse(result.reachable)
        assertEquals(ProbeKind.UNSUPPORTED, result.probeKind)
        assertEquals(123L, result.checkedAtEpochMs)
    }
}
