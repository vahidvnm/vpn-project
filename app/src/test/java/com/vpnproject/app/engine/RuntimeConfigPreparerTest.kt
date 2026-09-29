package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigImporter
import com.vpnproject.app.core.DnsOverHttpsResolver
import com.vpnproject.app.core.DohProvider
import com.vpnproject.app.core.DohTransport
import com.vpnproject.app.core.EndpointDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigPreparerTest {
    @Test
    fun preparesPinnedWireGuardRuntimeConfig() {
        val imported = ConfigImporter.parse(
            """
                [Interface]
                PrivateKey = example
                Address = 10.0.0.2/32

                [Peer]
                PublicKey = peer
                AllowedIPs = 0.0.0.0/0
                Endpoint = wg.example.com:51820
            """.trimIndent()
        )
        val preparer = RuntimeConfigPreparer(
            EndpointDiscovery(
                DnsOverHttpsResolver(
                    transport = object : DohTransport {
                        override fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String =
                            """{"Answer":[{"type":1,"TTL":60,"data":"8.8.8.8"}]}"""
                    },
                    providers = listOf(DohProvider.CLOUDFLARE),
                    nowEpochMs = { 1_000L }
                )
            )
        )

        val selection = preparer.prepareWireGuard(imported)

        assertEquals("wg.example.com", selection.originalHost)
        assertEquals("8.8.8.8", selection.selectedEndpointHost)
        assertEquals(51820, selection.port)
        assertTrue(selection.wasPinned)
        assertTrue(selection.configText.contains("Endpoint = 8.8.8.8:51820"))
        assertTrue(selection.note.contains("wg.example.com:51820"))
    }

    @Test
    fun keepsPublicIpv4LiteralWireGuardEndpointWithoutDoh() {
        val text = """
            [Interface]
            PrivateKey = example
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = peer
            AllowedIPs = 0.0.0.0/0
            Endpoint = 149.88.97.122:51820
        """.trimIndent()
        val imported = ConfigImporter.parse(text)
        val preparer = RuntimeConfigPreparer(failingEndpointDiscovery())

        val selection = preparer.prepareWireGuard(imported)

        assertEquals("149.88.97.122", selection.selectedEndpointHost)
        assertFalse(selection.wasPinned)
        assertEquals(text, selection.configText)
        assertTrue(selection.note.contains("DNS pinning is not needed"))
    }

    @Test
    fun keepsPublicIpv6LiteralWireGuardEndpointWithoutDoh() {
        val text = """
            [Interface]
            PrivateKey = example
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = peer
            AllowedIPs = 0.0.0.0/0
            Endpoint = [2606:4700:d0::a29f:c001]:51820
        """.trimIndent()
        val imported = ConfigImporter.parse(text)
        val preparer = RuntimeConfigPreparer(failingEndpointDiscovery())

        val selection = preparer.prepareWireGuard(imported)

        assertEquals("2606:4700:d0::a29f:c001", selection.selectedEndpointHost)
        assertFalse(selection.wasPinned)
        assertEquals(text, selection.configText)
        assertTrue(selection.note.contains("public IPv6 endpoint"))
    }

    @Test
    fun preparesPinnedOpenVpnTcpConfigForExternalHandoff() {
        val imported = ConfigImporter.parse(
            """
                client
                proto udp
                remote udp.example.com 1194 udp
                remote tcp.example.com 443 tcp
            """.trimIndent()
        )
        val preparer = RuntimeConfigPreparer(
            EndpointDiscovery(
                DnsOverHttpsResolver(
                    transport = object : DohTransport {
                        override fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String =
                            """{"Answer":[{"type":1,"TTL":60,"data":"9.9.9.9"}]}"""
                    },
                    providers = listOf(DohProvider.CLOUDFLARE),
                    nowEpochMs = { 1_000L }
                )
            )
        )

        val selection = preparer.prepareOpenVpn(imported)

        assertEquals("tcp.example.com", selection.originalHost)
        assertEquals("9.9.9.9", selection.selectedEndpointHost)
        assertTrue(selection.wasPinned)
        assertTrue(selection.configText.contains("remote 9.9.9.9 443 tcp"))
        assertTrue(selection.note.contains("OpenVPN TCP"))
    }

    private fun failingEndpointDiscovery(): EndpointDiscovery = EndpointDiscovery(
        DnsOverHttpsResolver(
            transport = object : DohTransport {
                override fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String =
                    error("Resolver should not be called for literal endpoints")
            },
            providers = listOf(DohProvider.CLOUDFLARE)
        )
    )
}
