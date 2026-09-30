package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigImporter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class V2RayRuntimeConfigBuilderTest {
    @Test
    fun buildsVlessRealityTunConfig() {
        val imported = ConfigImporter.parse(
            "vless://00000000-0000-4000-8000-000000000000@example.com:443?security=reality&type=tcp&sni=www.microsoft.com&fp=chrome&pbk=publicKey&sid=abcd&spx=%2F#reality"
        )

        val runtime = V2RayRuntimeConfigBuilder.build(imported)

        assertTrue(runtime.configJson.contains("\"protocol\": \"tun\""))
        assertTrue(runtime.configJson.contains("\"protocol\": \"vless\""))
        assertTrue(runtime.configJson.contains("\"vnext\""))
        assertTrue(runtime.configJson.contains("\"security\": \"reality\""))
        assertTrue(runtime.configJson.contains("\"publicKey\": \"publicKey\""))
        assertTrue(runtime.configJson.contains("\"shortId\": \"abcd\""))
    }

    @Test
    fun buildsVmessWebSocketTlsConfigFromSubscription() {
        val vmessJson = """
            {"v":"2","ps":"nl-ws","add":"edge.example.net","port":"443","id":"00000000-0000-4000-8000-000000000000","scy":"auto","net":"ws","type":"none","host":"front.example.net","path":"/ws","tls":"tls","sni":"sni.example.net","fp":"chrome"}
        """.trimIndent()
        val vmessLink = "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))}"
        val subscription = Base64.getEncoder().encodeToString(vmessLink.toByteArray(StandardCharsets.UTF_8))
        val imported = ConfigImporter.parse(subscription)

        val runtime = V2RayRuntimeConfigBuilder.build(imported)

        assertTrue(runtime.configJson.contains("\"protocol\": \"vmess\""))
        assertTrue(runtime.configJson.contains("\"wsSettings\""))
        assertTrue(runtime.configJson.contains("\"Host\": \"front.example.net\""))
        assertTrue(runtime.configJson.contains("\"serverName\": \"sni.example.net\""))
    }

    @Test
    fun buildsVlessHttpUpgradeConfig() {
        val imported = ConfigImporter.parse(
            "vless://00000000-0000-4000-8000-000000000000@api.example.ir:8880?security=none&type=httpupgrade&host=front.example.com&path=%2Fupgrade#hu"
        )

        val runtime = V2RayRuntimeConfigBuilder.build(imported)

        assertTrue(runtime.configJson.contains("\"network\": \"httpupgrade\""))
        assertTrue(runtime.configJson.contains("\"httpupgradeSettings\""))
        assertTrue(runtime.configJson.contains("\"path\": \"/upgrade\""))
        assertTrue(runtime.configJson.contains("\"host\": \"front.example.com\""))
    }

    @Test
    fun buildsVmessTcpHttpHeaderAndAlterId() {
        val vmessJson = """
            {"v":"2","ps":"tcp-http","add":"edge.example.net","port":"80","id":"00000000-0000-4000-8000-000000000000","aid":"2","scy":"auto","net":"tcp","type":"http","host":"www.netlify.com","path":"/front","tls":"none"}
        """.trimIndent()
        val imported = ConfigImporter.parse(
            "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))}"
        )

        val runtime = V2RayRuntimeConfigBuilder.build(imported)

        assertTrue(runtime.configJson.contains("\"alterId\": 2"))
        assertTrue(runtime.configJson.contains("\"tcpSettings\""))
        assertTrue(runtime.configJson.contains("\"type\": \"http\""))
        assertTrue(runtime.configJson.contains("\"path\": [\"/front\"]"))
        assertTrue(runtime.configJson.contains("\"Host\": [\"www.netlify.com\"]"))
    }
}
