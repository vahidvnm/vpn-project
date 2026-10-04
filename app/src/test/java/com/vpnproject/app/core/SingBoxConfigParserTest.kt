package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun ignoresNonSingBoxJson() {
        assertFalse(SingBoxConfigParser.looksLikeSingBox("{\"outbounds\":[{\"protocol\":\"vless\"}]}"))
    }
}
