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
}
