package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointDiscoveryTest {
    @Test
    fun tiesResolvedIpsBackToOriginalEndpointAndVerificationHost() {
        val resolver = DnsOverHttpsResolver(
            transport = SingleAnswerTransport,
            providers = listOf(DohProvider.CLOUDFLARE),
            nowEpochMs = { 2_000L }
        )
        val discovery = EndpointDiscovery(resolver)
        val endpoint = EndpointCandidate(
            host = "nl.provider.example",
            port = 443,
            protocol = VpnProtocol.OPENVPN_TCP,
            verifyHost = "server.provider.example"
        )

        val result = discovery.discover(endpoint)
        val resolved = result.resolved.single()

        assertEquals(endpoint, resolved.endpoint)
        assertEquals("9.9.9.9", resolved.ip)
        assertEquals("server.provider.example", resolved.verifyHost)
        assertEquals(
            EndpointCandidate(
                host = "9.9.9.9",
                port = 443,
                protocol = VpnProtocol.OPENVPN_TCP,
                verifyHost = "server.provider.example",
                source = CandidateSource.DNS_OVER_HTTPS
            ),
            resolved.asPinnedEndpointCandidate()
        )
    }

    private object SingleAnswerTransport : DohTransport {
        override fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String =
            """{"Status":0,"Answer":[{"type":1,"TTL":90,"data":"9.9.9.9"}]}"""
    }
}
