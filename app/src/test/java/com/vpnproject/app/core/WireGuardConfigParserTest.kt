package com.vpnproject.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigParserTest {
    @Test
    fun warnsWhenAllowedIpsDoesNotIncludeIpv6DefaultRoute() {
        val config = WireGuardConfigParser.parse(configWithAllowedIps("0.0.0.0/0"))

        assertTrue(config.warnings.any { it.contains("refuse to start") && it.contains("IPv6") })
        assertFalse(WireGuardConfigParser.hasIpv6DefaultRoute(config.originalText))
    }

    @Test
    fun recognizesCompressedAndExpandedIpv6DefaultRoutes() {
        assertTrue(WireGuardConfigParser.hasIpv6DefaultRoute(configWithAllowedIps("0.0.0.0/0, ::/0")))
        assertTrue(WireGuardConfigParser.hasIpv6DefaultRoute(configWithAllowedIps("0.0.0.0/0, 0:0:0:0:0:0:0:0/0")))

        val config = WireGuardConfigParser.parse(configWithAllowedIps("0.0.0.0/0, ::/0"))
        assertFalse(config.warnings.any { it.contains("refuse to start") })
    }

    private fun configWithAllowedIps(allowedIps: String): String = """
        [Interface]
        PrivateKey = test-private-key
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = test-public-key
        AllowedIPs = $allowedIps
        Endpoint = vpn.example.net:51820
    """.trimIndent()
}
