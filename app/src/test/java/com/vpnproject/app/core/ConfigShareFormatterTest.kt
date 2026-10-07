package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ConfigShareFormatterTest {
    @Test
    fun singleConfigKeepsOriginalTextAndChoosesUsefulExtension() {
        val raw = "[Interface]\nPrivateKey = secret\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = peer\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = vpn.example.com:51820"
        val payload = ConfigShareFormatter.singleConfig(ConfigShareEntry("Work / tunnel", raw))

        assertNotNull(payload)
        assertEquals("Work _ tunnel.conf", payload?.fileName)
        assertEquals("text/plain", payload?.mimeType)
        assertEquals(raw, payload?.content)
    }

    @Test
    fun textBundleIncludesEverySelectedEntryInOrder() {
        val payload = ConfigShareFormatter.textBundle(
            listOf(
                ConfigShareEntry("First", "vless://first@example.com:443"),
                ConfigShareEntry("Second", "trojan://second@example.com:443")
            ),
            "Provider"
        )

        assertNotNull(payload)
        val exported = requireNotNull(payload)
        assertEquals("Provider.txt", exported.fileName)
        assertTrue(exported.content.contains("# 1. First\nvless://first@example.com:443"))
        assertTrue(exported.content.contains("# 2. Second\ntrojan://second@example.com:443"))
        assertTrue(exported.content.indexOf("First") < exported.content.indexOf("Second"))
    }

    @Test
    fun v2RaySubscriptionIsImportableBase64AndContainsAllLinks() {
        val entries = listOf(
            ConfigShareEntry("One", "vless://one@example.com:443?type=tcp"),
            ConfigShareEntry("Two", "trojan://two@example.com:443")
        )
        val payload = ConfigShareFormatter.v2RaySubscription(entries, "Provider")

        assertNotNull(payload)
        val exported = requireNotNull(payload)
        assertEquals("Provider.sub", exported.fileName)
        val decoded = String(Base64.getDecoder().decode(exported.content), StandardCharsets.UTF_8)
        assertEquals("vless://one@example.com:443?type=tcp\ntrojan://two@example.com:443", decoded)
        assertEquals(2, V2RaySubscriptionParser.extractLinks(exported.content).size)
    }

    @Test
    fun v2RaySubscriptionIsNotOfferedForMixedOrNonLinkEntries() {
        val mixed = listOf(
            ConfigShareEntry("One", "vless://one@example.com:443"),
            ConfigShareEntry("Two", "[Interface]\nPrivateKey = secret")
        )

        assertNull(ConfigShareFormatter.v2RaySubscription(mixed, "Mixed"))
        assertNull(ConfigShareFormatter.textBundle(emptyList(), "Empty"))
    }
}
