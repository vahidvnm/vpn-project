package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket

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

    @Test
    fun tcpProbeFailsBeforeConnectWhenSocketCannotBeProtected() {
        val checker = EndpointHealthChecker(
            nowEpochMs = { 456L },
            socketProtector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean = false
                override fun protect(socket: DatagramSocket): Boolean = false
            }
        )
        val resolved = ResolvedEndpointCandidate(
            endpoint = EndpointCandidate("vpn.example", 443, VpnProtocol.OPENVPN_TCP),
            ip = "8.8.8.8",
            provider = DohProvider.CLOUDFLARE,
            ttlSeconds = 60,
            expiresAtEpochMs = 60_000L
        )

        val result = checker.checkTcp(resolved)

        assertFalse(result.reachable)
        assertEquals(ProbeKind.TCP_CONNECT, result.probeKind)
        assertEquals(456L, result.checkedAtEpochMs)
        assertTrue(result.reason.orEmpty().contains("protect"))
    }
}
