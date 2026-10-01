package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class V2RaySubscriptionParserTest {
    @Test
    fun extractsPlainAndBase64SubscriptionLinks() {
        val vless = "vless://00000000-0000-4000-8000-000000000000@edge.example.com:443?security=tls&type=ws#Edge"
        val trojan = "trojan://secret@trojan.example.com:443?security=tls#Trojan"
        val encoded = Base64.getEncoder().encodeToString("$vless\n$trojan".toByteArray(StandardCharsets.UTF_8))

        val links = V2RaySubscriptionParser.extractLinks(encoded)

        assertEquals(listOf(vless, trojan), links)
    }

    @Test
    fun deduplicatesDirectAndDecodedLinks() {
        val vmessJson = "{\"v\":\"2\",\"ps\":\"node\",\"add\":\"edge.example.net\",\"port\":\"443\",\"id\":\"00000000-0000-4000-8000-000000000000\",\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\",\"tls\":\"tls\"}"
        val vmess = "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))}"
        val encoded = Base64.getEncoder().encodeToString(vmess.toByteArray(StandardCharsets.UTF_8))

        val links = V2RaySubscriptionParser.extractLinks("$vmess\n$encoded")

        assertEquals(listOf(vmess), links)
    }
}
