package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigParserTest {
    @Test
    fun parsesVlessRealityOutbound() {
        val json = """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "🇩🇪 Germany / Reality",
                  "server": "edge.example.com",
                  "server_port": 443,
                  "uuid": "11111111-1111-1111-1111-111111111111",
                  "tls": {
                    "enabled": true,
                    "server_name": "www.microsoft.com",
                    "reality": { "enabled": true, "public_key": "PUB", "short_id": "abc" }
                  }
                }
              ]
            }
        """.trimIndent()

        val config = SingBoxConfigParser.parse(json)

        assertEquals(ConfigKind.SING_BOX, config.kind)
        assertEquals("🇩🇪 Germany / Reality", config.name)
        assertEquals(
            EndpointCandidate(
                host = "edge.example.com",
                port = 443,
                protocol = VpnProtocol.SING_BOX_REALITY,
                verifyHost = "www.microsoft.com"
            ),
            config.endpoints.single()
        )
        assertTrue(config.warnings.any { it.contains("sing-box JSON import is experimental") })
        assertFalse(config.warnings.joinToString(" ").contains("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun parsesTrojanWebSocketTlsOutbound() {
        val json = """
            {
              "outbounds": [{
                "type": "trojan",
                "tag": "trojan-ws",
                "server": "cdn.example.net",
                "server_port": "443",
                "password": "secret",
                "tls": { "enabled": true, "server_name": "front.example.net" },
                "transport": { "type": "ws", "path": "/ws", "headers": { "Host": "front.example.net" } }
              }]
            }
        """.trimIndent()

        val config = SingBoxConfigParser.parse(json)

        assertEquals(ConfigKind.SING_BOX, config.kind)
        assertEquals(VpnProtocol.SING_BOX_TLS, config.endpoints.single().protocol)
        assertEquals("cdn.example.net", config.endpoints.single().host)
        assertEquals("front.example.net", config.endpoints.single().verifyHost)
        assertTrue(config.warnings.any { it.contains("WS/TLS") })
    }


    @Test
    fun exposesXrayShareLinkForSupportedOutbound() {
        val json = """
            {
              "outbounds": [{
                "type": "vless",
                "tag": "de-reality",
                "server": "edge.example.com",
                "server_port": 443,
                "uuid": "11111111-1111-1111-1111-111111111111",
                "flow": "xtls-rprx-vision",
                "tls": {
                  "enabled": true,
                  "server_name": "www.microsoft.com",
                  "utls": { "fingerprint": "chrome" },
                  "reality": { "enabled": true, "public_key": "PUB", "short_id": "abc" }
                }
              }]
            }
        """.trimIndent()

        val link = SingBoxConfigParser.firstXrayShareLink(json)

        assertNotNull(link)
        assertTrue(link!!.startsWith("vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?"))
        assertTrue(link.contains("security=reality"))
        assertTrue(link.contains("pbk=PUB"))
        assertTrue(link.contains("flow=xtls-rprx-vision"))
    }

    @Test
    fun exposesShadowsocksShareLinkForSupportedOutbound() {
        val json = """
            { "outbounds": [{ "type": "shadowsocks", "tag": "ss", "server": "ss.example.com", "server_port": 8388, "method": "aes-128-gcm", "password": "secret" }] }
        """.trimIndent()

        val link = SingBoxConfigParser.firstXrayShareLink(json)

        assertNotNull(link)
        assertTrue(link!!.startsWith("ss://"))
        assertTrue(link.contains("@ss.example.com:8388#ss"))
    }


    @Test
    fun bracketsIpv6HostInGeneratedShareLink() {
        val json = """
            { "outbounds": [{ "type": "trojan", "tag": "ipv6", "server": "2001:db8::1", "server_port": 443, "password": "secret", "tls": { "enabled": true, "server_name": "front.example" } }] }
        """.trimIndent()

        val link = SingBoxConfigParser.firstXrayShareLink(json)

        assertNotNull(link)
        assertTrue(link!!.contains("@[2001:db8::1]:443?"))
    }

    @Test
    fun ignoresNonSingBoxJson() {
        assertFalse(SingBoxConfigParser.looksLikeSingBox("{\"outbounds\":[{\"protocol\":\"vless\"}]}"))
    }
}
