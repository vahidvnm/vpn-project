package com.vpnproject.app.profile

import com.vpnproject.app.core.ConfigImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileFactoryTest {
    @Test
    fun createsXrayProfileMetadataWithoutSecretsInSummary() {
        val imported = ConfigImporter.parse(
            "vless://00000000-0000-4000-8000-000000000000@api.example.ir:8880?security=none&type=httpupgrade&path=%2F#Iran%20HTTPUpgrade"
        )

        val profile = VpnProfileFactory.fromImportedConfig(imported, nowEpochMs = 123L)

        assertEquals(VpnProfileKind.XRAY, profile.kind)
        assertEquals("Iran HTTPUpgrade", profile.name)
        assertEquals("api.example.ir", profile.endpoints.single().host)
        assertEquals(8880, profile.endpoints.single().port)
        assertTrue(profile.summary().contains("V2Ray/Xray"))
        assertFalse(profile.summary().contains("00000000-0000-4000-8000-000000000000"))
    }

    @Test
    fun createsSingBoxProfileMetadata() {
        val imported = ConfigImporter.parse(
            """
                { "outbounds": [{
                  "type": "trojan",
                  "tag": "singbox tls",
                  "server": "sb.example.net",
                  "server_port": 443,
                  "password": "secret",
                  "tls": { "enabled": true, "server_name": "front.example.net" }
                }] }
            """.trimIndent()
        )

        val profile = VpnProfileFactory.fromImportedConfig(imported, nowEpochMs = 234L)

        assertEquals(VpnProfileKind.SING_BOX, profile.kind)
        assertEquals("singbox tls", profile.name)
        assertEquals("SING_BOX_TLS", profile.endpoints.single().protocol)
        assertEquals("sb.example.net", profile.endpoints.single().host)
        assertFalse(profile.summary().contains("secret"))
    }

    @Test
    fun createsWireGuardProfileMetadata() {
        val imported = ConfigImporter.parse(
            """
                [Interface]
                PrivateKey = secret
                Address = 10.2.0.2/32

                [Peer]
                PublicKey = peer
                AllowedIPs = 0.0.0.0/0
                Endpoint = wg.example.com:51820
            """.trimIndent(),
            "wg.conf"
        )

        val profile = VpnProfileFactory.fromImportedConfig(imported, nowEpochMs = 456L)

        assertEquals(VpnProfileKind.WIREGUARD, profile.kind)
        assertEquals("wg", profile.name)
        assertEquals("WIREGUARD", profile.endpoints.single().protocol)
        assertEquals("wg.example.com", profile.endpoints.single().host)
    }
}
