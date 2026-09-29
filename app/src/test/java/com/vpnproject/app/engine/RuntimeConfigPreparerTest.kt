package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigImporter
import com.vpnproject.app.core.DnsOverHttpsResolver
import com.vpnproject.app.core.DohProvider
import com.vpnproject.app.core.DohTransport
import com.vpnproject.app.core.EndpointDiscovery
import org.junit.Assert.assertEquals
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
        assertEquals("8.8.8.8", selection.pinnedIp)
        assertEquals(51820, selection.port)
        assertTrue(selection.configText.contains("Endpoint = 8.8.8.8:51820"))
        assertTrue(selection.note.contains("wg.example.com:51820"))
    }
}
