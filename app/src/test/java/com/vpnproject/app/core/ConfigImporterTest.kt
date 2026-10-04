package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

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

    @Test
    fun parsesVlessTlsShareLink() {
        val text = "vless://00000000-0000-4000-8000-000000000000@cdn.example.com:443?security=tls&type=ws&sni=sni.example.com&host=front.example.com&path=%2Fws#Iran%20fallback"

        val config = ConfigImporter.parse(text, "v2ray.txt")

        assertEquals(ConfigKind.V2RAY, config.kind)
        assertEquals("v2ray.txt", config.name)
        assertEquals(
            EndpointCandidate(
                host = "cdn.example.com",
                port = 443,
                protocol = VpnProtocol.V2RAY_TLS,
                verifyHost = "sni.example.com"
            ),
            config.endpoints.single()
        )
        assertTrue(config.warnings.any { it.contains("Detected WS/TLS") })
        assertTrue(config.warnings.any { it.contains("experimental embedded Xray engine") })
    }

    @Test
    fun warnsWhenV2RayTransportIsNotRuntimeMappedYet() {
        val text = "vless://00000000-0000-4000-8000-000000000000@cdn.example.com:443?security=tls&type=kcp#old-kcp"

        val config = ConfigImporter.parse(text)

        assertEquals(ConfigKind.V2RAY, config.kind)
        assertTrue(config.warnings.any { it.contains("Embedded Xray support warning") && it.contains("Unsupported transport kcp") })
    }

    @Test
    fun parsesBase64VmessSubscription() {
        val vmessJson = """
            {"v":"2","ps":"nl-ws","add":"edge.example.net","port":"443","id":"00000000-0000-4000-8000-000000000000","aid":"0","net":"ws","type":"none","host":"front.example.net","path":"/ws","tls":"tls","sni":"sni.example.net"}
        """.trimIndent()
        val vmessLink = "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))}"
        val subscription = Base64.getEncoder().encodeToString(vmessLink.toByteArray(StandardCharsets.UTF_8))

        val config = ConfigImporter.parse(subscription)

        assertEquals(ConfigKind.V2RAY, config.kind)
        assertEquals("nl-ws", config.name)
        assertEquals("edge.example.net", config.endpoints.single().host)
        assertEquals(443, config.endpoints.single().port)
        assertEquals(VpnProtocol.V2RAY_TLS, config.endpoints.single().protocol)
        assertEquals("sni.example.net", config.endpoints.single().verifyHost)
    }

    @Test
    fun parsesSingBoxJsonConfig() {
        val text = """
            {
              "outbounds": [{
                "type": "vless",
                "tag": "singbox-reality",
                "server": "sb.example.com",
                "server_port": 443,
                "uuid": "00000000-0000-4000-8000-000000000000",
                "tls": { "enabled": true, "server_name": "www.example.com", "reality": { "enabled": true, "public_key": "PUB" } }
              }]
            }
        """.trimIndent()

        val config = ConfigImporter.parse(text)

        assertEquals(ConfigKind.SING_BOX, config.kind)
        assertEquals("sb.example.com", config.endpoints.single().host)
        assertEquals(VpnProtocol.SING_BOX_REALITY, config.endpoints.single().protocol)
    }

    @Test
    fun parsesTrojanRealityAsTcpDiagnosticEndpoint() {
        val text = "trojan://password@example.org:443?security=reality&sni=www.microsoft.com&type=tcp#reality"

        val config = ConfigImporter.parse(text)

        assertEquals(ConfigKind.V2RAY, config.kind)
        assertEquals(VpnProtocol.V2RAY_REALITY, config.endpoints.single().protocol)
        assertEquals("www.microsoft.com", config.endpoints.single().verifyHost)
        assertTrue(config.warnings.any { it.contains("REALITY") })
    }

    @Test(expected = ConfigParseException::class)
    fun rejectsUnknownConfig() {
        ConfigImporter.parse("hello world")
    }
}
