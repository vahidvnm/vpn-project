package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.ConfigParseException
import com.vpnproject.app.core.ImportedConfig
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class V2RayRuntimeConfigBuilderTest {
    @Test
    fun buildsRealityTcpSettingsFromVlessLink() {
        val json = buildJson(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?security=reality&type=tcp&sni=www.microsoft.com&fp=chrome&pbk=PUBLICKEY&sid=abc123&spx=%2F#Reality"
        )

        assertContains(json, "\"network\": \"tcp\"")
        assertContains(json, "\"security\": \"reality\"")
        assertContains(json, "\"realitySettings\"")
        assertContains(json, "\"serverName\": \"www.microsoft.com\"")
        assertContains(json, "\"fingerprint\": \"chrome\"")
        assertContains(json, "\"publicKey\": \"PUBLICKEY\"")
        assertContains(json, "\"shortId\": \"abc123\"")
        assertContains(json, "\"spiderX\": \"/\"")
    }

    @Test
    fun buildsGrpcTlsSettingsFromTrojanLink() {
        val json = buildJson(
            "trojan://pass@example.com:443" +
                "?security=tls&type=grpc&serviceName=myService&authority=front.example&sni=sni.example&fp=random#Grpc"
        )

        assertContains(json, "\"network\": \"grpc\"")
        assertContains(json, "\"grpcSettings\"")
        assertContains(json, "\"serviceName\": \"myService\"")
        assertContains(json, "\"multiMode\": false")
        assertContains(json, "\"authority\": \"front.example\"")
        assertContains(json, "\"tlsSettings\"")
        assertContains(json, "\"serverName\": \"sni.example\"")
    }

    @Test
    fun buildsWebSocketTlsSettingsFromVlessLink() {
        val json = buildJson(
            "vless://11111111-1111-1111-1111-111111111111@edge.example:443" +
                "?type=ws&security=tls&host=front.example&path=%2Fws%3Fed%3D2560&sni=front.example#WS"
        )

        assertContains(json, "\"network\": \"ws\"")
        assertContains(json, "\"wsSettings\"")
        assertContains(json, "\"path\": \"/ws?ed=2560\"")
        assertContains(json, "\"Host\": \"front.example\"")
        assertContains(json, "\"tlsSettings\"")
    }

    @Test
    fun buildsTcpHttpHeaderFromVmessLink() {
        val vmess = """
            {
              "v": "2",
              "ps": "tcp-http",
              "add": "tcp.example",
              "port": "80",
              "id": "11111111-1111-1111-1111-111111111111",
              "aid": "2",
              "scy": "auto",
              "net": "tcp",
              "type": "http",
              "host": "www.example.com",
              "path": "/front"
            }
        """.trimIndent()
        val link = "vmess://${Base64.getEncoder().encodeToString(vmess.toByteArray(StandardCharsets.UTF_8))}"
        val json = buildJson(link)

        assertContains(json, "\"network\": \"tcp\"")
        assertContains(json, "\"alterId\": 2")
        assertContains(json, "\"tcpSettings\"")
        assertContains(json, "\"type\": \"http\"")
        assertContains(json, "\"path\": [\"/front\"]")
        assertContains(json, "\"Host\": [\"www.example.com\"]")
    }

    @Test
    fun buildsHttpUpgradeTlsSettingsFromVlessLink() {
        val json = buildJson(
            "vless://11111111-1111-1111-1111-111111111111@upgrade.example:443" +
                "?type=httpupgrade&security=tls&host=front.example&path=%2Fupgrade&sni=front.example#HttpUpgrade"
        )

        assertContains(json, "\"network\": \"httpupgrade\"")
        assertContains(json, "\"httpupgradeSettings\"")
        assertContains(json, "\"path\": \"/upgrade\"")
        assertContains(json, "\"host\": \"front.example\"")
        assertContains(json, "\"tlsSettings\"")
    }

    @Test
    fun buildsFromJsonArrayWrappedShareLink() {
        val link = "vless://11111111-1111-1111-1111-111111111111@edge.example:443" +
            "?type=ws&security=tls&host=front.example&path=%2Fws&sni=front.example#Wrapped"
        val json = buildJson("{\"nodes\":[\"$link\"]}")

        assertContains(json, "\"network\": \"ws\"")
        assertContains(json, "\"wsSettings\"")
    }


    @Test
    fun buildsFromSingBoxVlessRealityOutbound() {
        val singBox = """
            {
              "outbounds": [{
                "type": "vless",
                "tag": "sing-box reality",
                "server": "edge.example.com",
                "server_port": 443,
                "uuid": "11111111-1111-1111-1111-111111111111",
                "flow": "xtls-rprx-vision",
                "tls": {
                  "enabled": true,
                  "server_name": "www.microsoft.com",
                  "utls": { "fingerprint": "chrome" },
                  "reality": { "enabled": true, "public_key": "PUBLICKEY", "short_id": "abc123" }
                }
              }]
            }
        """.trimIndent()
        val runtime = buildRuntime(ConfigKind.SING_BOX, singBox)

        assertContains(runtime.note, "Prepared sing-box VLESS edge.example.com:443")
        assertContains(runtime.configJson, "\"security\": \"reality\"")
        assertContains(runtime.configJson, "\"publicKey\": \"PUBLICKEY\"")
        assertContains(runtime.configJson, "\"serverName\": \"www.microsoft.com\"")
        assertContains(runtime.configJson, "\"flow\": \"xtls-rprx-vision\"")
    }

    @Test
    fun buildsFromClashTrojanWebSocketTlsProxy() {
        val clash = """
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
        val runtime = buildRuntime(ConfigKind.CLASH, clash)

        assertContains(runtime.note, "Prepared Clash TROJAN cdn.example.net:443")
        assertContains(runtime.configJson, "\"network\": \"ws\"")
        assertContains(runtime.configJson, "\"wsSettings\"")
        assertContains(runtime.configJson, "\"path\": \"/ws\"")
        assertContains(runtime.configJson, "\"serverName\": \"front.example.net\"")
    }

    @Test
    fun unsupportedSingBoxMapperFailsClearlyWithoutSecret() {
        val singBox = """
            { "outbounds": [{ "type": "vless", "server": "edge.example.com", "server_port": 443, "uuid": "11111111-1111-1111-1111-111111111111", "transport": { "type": "quic" } }] }
        """.trimIndent()

        val error = assertThrows(ConfigParseException::class.java) {
            buildRuntime(ConfigKind.SING_BOX, singBox)
        }

        assertContains(error.message.orEmpty(), "Unsupported V2Ray/Xray transport quic")
        assertTrue("Secret UUID leaked in error: ${error.message}", !error.message.orEmpty().contains("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun buildsCoreOnlyDelayProbeConfigWithoutTunInbound() {
        val runtime = V2RayRuntimeConfigBuilder.buildDelayProbe(
            ImportedConfig(
                kind = ConfigKind.V2RAY,
                originalText = "vless://11111111-1111-1111-1111-111111111111@edge.example:443?type=ws&security=tls&host=front.example&path=%2Fws&sni=front.example#Delay",
                endpoints = emptyList()
            )
        )

        assertContains(runtime.note, "real-delay probe without Android VPN/TUN")
        assertContains(runtime.configJson, "\"inbounds\": []")
        assertContains(runtime.configJson, "\"protocol\": \"vless\"")
        assertContains(runtime.configJson, "\"wsSettings\"")
    }

    @Test
    fun customDnsServersAreAppliedToRuntimeConfig() {
        val runtime = V2RayRuntimeConfigBuilder.build(
            ImportedConfig(
                kind = ConfigKind.V2RAY,
                originalText = "vless://11111111-1111-1111-1111-111111111111@edge.example:443?type=ws&security=tls&host=front.example&path=%2Fws&sni=front.example#Dns",
                endpoints = emptyList()
            ),
            dnsServers = listOf("9.9.9.9", "223.5.5.5", "localhost")
        )

        assertContains(runtime.configJson, "\"dns\": { \"servers\": [\"9.9.9.9\", \"223.5.5.5\", \"localhost\"] }")
    }

    @Test
    fun advancedXrayOptionsAreAppliedToRuntimeConfig() {
        val runtime = V2RayRuntimeConfigBuilder.build(
            ImportedConfig(
                kind = ConfigKind.V2RAY,
                originalText = "vless://11111111-1111-1111-1111-111111111111@edge.example:443?type=ws&security=tls&host=front.example&path=%2Fws&sni=front.example#Advanced",
                endpoints = emptyList()
            ),
            sniffingEnabled = false,
            muxEnabled = true,
            muxConcurrency = 4,
            logLevel = "info"
        )

        assertContains(runtime.configJson, "\"log\": { \"loglevel\": \"info\" }")
        assertContains(runtime.configJson, "\"sniffing\": { \"enabled\": false }")
        assertContains(runtime.configJson, "\"mux\": { \"enabled\": true, \"concurrency\": 4 }")
    }

    @Test
    fun optionalLoopbackHttpInboundIsAddedForPostConnectChecks() {
        val runtime = V2RayRuntimeConfigBuilder.build(
            ImportedConfig(
                kind = ConfigKind.V2RAY,
                originalText = "vless://11111111-1111-1111-1111-111111111111@edge.example:443?type=ws&security=tls&host=front.example&path=%2Fws&sni=front.example#LocalCheck",
                endpoints = emptyList()
            ),
            localHttpProxyPort = 23456
        )

        assertContains(runtime.configJson, "\"tag\": \"loopback-http\"")
        assertContains(runtime.configJson, "\"listen\": \"127.0.0.1\"")
        assertContains(runtime.configJson, "\"port\": 23456")
        assertContains(runtime.configJson, "\"protocol\": \"http\"")
        assertTrue(runtime.localHttpProxyPort == 23456)
    }

    @Test
    fun unsupportedTransportFailsClearly() {
        val error = assertThrows(ConfigParseException::class.java) {
            buildJson(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                    "?security=tls&type=kcp#Unsupported"
            )
        }

        assertContains(error.message.orEmpty(), "Unsupported V2Ray/Xray transport kcp")
    }

    @Test
    fun realityWithoutPublicKeyFailsClearly() {
        val error = assertThrows(ConfigParseException::class.java) {
            buildJson(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                    "?security=reality&type=tcp&sni=www.example.com#BrokenReality"
            )
        }

        assertContains(error.message.orEmpty(), "REALITY link is missing public key")
    }

    private fun buildJson(link: String): String = buildRuntime(ConfigKind.V2RAY, link).configJson

    private fun buildRuntime(kind: ConfigKind, text: String): V2RayRuntimeConfig = V2RayRuntimeConfigBuilder.build(
        ImportedConfig(
            kind = kind,
            originalText = text,
            endpoints = emptyList()
        )
    )

    private fun assertContains(text: String, expected: String) {
        assertTrue("Expected <$expected> in:\n$text", text.contains(expected))
    }
}
