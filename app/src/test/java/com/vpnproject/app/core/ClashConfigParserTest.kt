package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun exposesXrayShareLinkForSupportedProxy() {
        val yaml = """
            proxies:
              - name: Germany WS
                type: trojan
                server: cdn.example.net
                port: 443
                password: secret
                network: ws
                tls: true
                sni: front.example.net
                ws-opts:
                  path: /ws
                  headers:
                    Host: front.example.net
        """.trimIndent()

        val link = ClashConfigParser.firstXrayShareLink(yaml)

        assertNotNull(link)
        assertTrue(link!!.startsWith("trojan://secret@cdn.example.net:443?"))
        assertTrue(link.contains("security=tls"))
        assertTrue(link.contains("type=ws"))
        assertTrue(link.contains("sni=front.example.net"))
    }

    @Test
    fun splitsTopLevelClashSubscriptionIntoSingleProxyConfigs() {
        val yaml = """
            port: 7890
            proxies:
            - name: US SS
              server: 66.23.204.210
              port: 16995
              type: ss
              cipher: aes-128-gcm
              password: secret-one
              udp: true
            - name: US Node Two
              server: 167.88.62.124
              port: 22324
              type: vmess
              uuid: 04621bae-ab36-11ec-b909-0242ac120002
              alterId: 0
              cipher: auto
              tls: false
              network: tcp
            proxy-groups: []
        """.trimIndent()

        val proxyTexts = ClashConfigParser.splitProxyTexts(yaml)

        assertEquals(2, proxyTexts.size)
        assertTrue(proxyTexts[0].startsWith("proxies:\n- name: US SS"))
        assertTrue(proxyTexts[1].contains("type: vmess"))
        assertEquals("US SS", ClashConfigParser.parse(proxyTexts[0]).name)
        assertEquals("US Node Two", ClashConfigParser.parse(proxyTexts[1]).name)
    }

    @Test
    fun ignoresNonClashYaml() {
        assertFalse(ClashConfigParser.looksLikeClash("port: 7890\nproxy-groups: []"))
    }
}
