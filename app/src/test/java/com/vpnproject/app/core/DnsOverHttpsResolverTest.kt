package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsOverHttpsResolverTest {
    @Test
    fun dohProvidersPreferIpLiteralEndpointsToAvoidPoisonedSystemDns() {
        assertTrue(DohProvider.CLOUDFLARE.endpointUrl.contains("1.1.1.1"))
        assertTrue(DohProvider.GOOGLE.endpointUrl.contains("8.8.8.8"))
        assertTrue(DohProvider.QUAD9.endpointUrl.contains("9.9.9.9"))
    }

    @Test
    fun parsesPublicARecordsAndFiltersReservedAnswers() {
        val resolver = DnsOverHttpsResolver(
            transport = FakeDohTransport(
                mapOf(
                    DohProvider.CLOUDFLARE to """
                        {
                          "Status": 0,
                          "Answer": [
                            {"name":"vpn.example.","type":1,"TTL":120,"data":"8.8.8.8"},
                            {"name":"vpn.example.","type":1,"TTL":120,"data":"10.0.0.1"},
                            {"name":"vpn.example.","type":28,"TTL":120,"data":"2001:db8::1"}
                          ]
                        }
                    """.trimIndent()
                )
            ),
            providers = listOf(DohProvider.CLOUDFLARE),
            nowEpochMs = { 1_000L }
        )

        val result = resolver.resolveA("VPN.Example.")

        assertEquals("vpn.example", result.hostname)
        assertFalse(result.fromCache)
        assertEquals(listOf("8.8.8.8"), result.addresses.map { it.ip })
        assertEquals(120L, result.addresses.single().ttlSeconds)
        assertEquals(121_000L, result.addresses.single().expiresAtEpochMs)
    }

    @Test
    fun cachesPositiveAnswersUntilTtlExpires() {
        var now = 10_000L
        val transport = FakeDohTransport(
            mapOf(
                DohProvider.GOOGLE to """
                    {"Status":0,"Answer":[{"name":"vpn.example.","type":1,"TTL":60,"data":"1.1.1.1"}]}
                """.trimIndent()
            )
        )
        val resolver = DnsOverHttpsResolver(
            transport = transport,
            providers = listOf(DohProvider.GOOGLE),
            nowEpochMs = { now }
        )

        val first = resolver.resolveA("vpn.example")
        now += 1_000L
        val second = resolver.resolveA("vpn.example")

        assertEquals(1, transport.calls.size)
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(first.addresses, second.addresses)
    }

    @Test
    fun reportsReservedLiteralAddressAsUnusable() {
        val resolver = DnsOverHttpsResolver(transport = FakeDohTransport(emptyMap()))

        val result = resolver.resolveA("192.168.1.20")

        assertTrue(result.addresses.isEmpty())
        assertTrue(result.errors.single().contains("reserved"))
    }

    @Test
    fun reportsPublicIpv6LiteralAsNoIpv4PinningNeeded() {
        val transport = FakeDohTransport(emptyMap())
        val resolver = DnsOverHttpsResolver(transport = transport)

        val result = resolver.resolveA("[2606:4700:d0::a29f:c001]")

        assertTrue(result.addresses.isEmpty())
        assertTrue(result.errors.single().contains("public IPv6 literal"))
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun returnsPublicLiteralAddressWithoutDohCall() {
        val transport = FakeDohTransport(emptyMap())
        val resolver = DnsOverHttpsResolver(transport = transport, nowEpochMs = { 5_000L })

        val result = resolver.resolveA("8.8.4.4")

        assertEquals(listOf("8.8.4.4"), result.addresses.map { it.ip })
        assertEquals(0, transport.calls.size)
    }

    private class FakeDohTransport(
        private val responses: Map<DohProvider, String>
    ) : DohTransport {
        val calls = mutableListOf<Pair<DohProvider, String>>()

        override fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String {
            calls += provider to hostname
            return responses[provider] ?: error("No fake response for $provider")
        }
    }
}
