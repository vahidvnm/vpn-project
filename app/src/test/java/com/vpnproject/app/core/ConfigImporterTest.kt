package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImporterTest {
    @Test
    fun parsesOpenVpnRemoteLinesWithGlobalProtoAndVerifyName() {
        val text = """
            client
            dev tun
            proto tcp
            remote de-fra.examplevpn.com 443
            remote us-nyc.examplevpn.com 8443 udp
            verify-x509-name server.examplevpn.com name
            auth-user-pass
        """.trimIndent()

        val config = ConfigImporter.parse(text, "provider.ovpn")

        assertEquals(ConfigKind.OPENVPN, config.kind)
        assertTrue(config.hasAuthUserPass)
        assertEquals(2, config.endpoints.size)
        assertEquals(
            EndpointCandidate(
                host = "de-fra.examplevpn.com",
                port = 443,
                protocol = VpnProtocol.OPENVPN_TCP,
                verifyHost = "server.examplevpn.com"
            ),
            config.endpoints[0]
        )
        assertEquals(VpnProtocol.OPENVPN_UDP, config.endpoints[1].protocol)
    }

    @Test
    fun parsesOpenVpnConnectionBlockRemote() {
        val text = """
            client
            <connection>
            remote nl.provider.example 1443 tcp-client
            </connection>
        """.trimIndent()

        val config = ConfigImporter.parse(text)

        assertEquals(1, config.endpoints.size)
        assertEquals("nl.provider.example", config.endpoints[0].host)
        assertEquals(1443, config.endpoints[0].port)
        assertEquals(VpnProtocol.OPENVPN_TCP, config.endpoints[0].protocol)
        assertEquals("nl.provider.example", config.endpoints[0].verifyHost)
        assertFalse(config.warnings.isEmpty())
    }

    @Test
    fun parsesWireGuardPeerEndpoint() {
        val text = """
            [Interface]
            PrivateKey = example
            Address = 10.2.0.2/32

            [Peer]
            PublicKey = peer
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent()

        val config = ConfigImporter.parse(text, "wg.conf")

        assertEquals(ConfigKind.WIREGUARD, config.kind)
        assertEquals(
            EndpointCandidate("vpn.example.com", 51820, VpnProtocol.WIREGUARD),
            config.endpoints.single()
        )
    }

    @Test(expected = ConfigParseException::class)
    fun rejectsUnknownConfig() {
        ConfigImporter.parse("hello world")
    }
}
