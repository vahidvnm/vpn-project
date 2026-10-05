package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashConfigParserTest {
    @Test
    fun parsesVlessRealityYamlProxy() {
        val yaml = """
            mixed-port: 7890
            proxies:
              - name: 🇩🇪 Germany / Reality
                type: vless
                server: edge.example.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                network: tcp
                tls: true
                servername: www.microsoft.com
                reality-opts:
                  public-key: PUB
                  short-id: abc
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)

        assertEquals(ConfigKind.CLASH, config.kind)
        assertEquals("🇩🇪 Germany / Reality", config.name)
        assertEquals(
            EndpointCandidate(
                host = "edge.example.com",
                port = 443,
                protocol = VpnProtocol.CLASH_REALITY,
                verifyHost = "www.microsoft.com"
            ),
            config.endpoints.single()
        )
        assertTrue(config.warnings.any { it.contains("Clash/Clash.Meta import is experimental") })
        assertFalse(config.warnings.joinToString(" ").contains("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun parsesInlineTrojanWebSocketTlsProxy() {
        val yaml = """
            proxies:
              - { name: Germany WS, type: trojan, server: cdn.example.net, port: 443, password: secret, network: ws, tls: true, sni: front.example.net }
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)

        assertEquals(ConfigKind.CLASH, config.kind)
        assertEquals("Germany WS", config.name)
        assertEquals("cdn.example.net", config.endpoints.single().host)
        assertEquals(VpnProtocol.CLASH_TLS, config.endpoints.single().protocol)
        assertEquals("front.example.net", config.endpoints.single().verifyHost)
        assertTrue(config.warnings.any { it.contains("WS/TLS") })
    }

    @Test
    fun ignoresNonClashYaml() {
        assertFalse(ClashConfigParser.looksLikeClash("port: 7890\nproxy-groups: []"))
    }
}
