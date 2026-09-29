package com.vpnproject.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedConfigRendererTest {
    @Test
    fun rendersPinnedOpenVpnConfigWithoutChangingOtherLines() {
        val text = """
            client
            proto tcp
            remote de.example.com 443
            auth-user-pass
        """.trimIndent() + "\n"
        val config = ConfigImporter.parse(text)
        val rendered = PinnedConfigRenderer.render(config, config.endpoints.single(), "8.8.8.8")

        assertTrue(rendered.startsWith("# pinned by VPN Project\n# de.example.com -> 8.8.8.8\n"))
        assertTrue(rendered.contains("remote 8.8.8.8 443\n"))
        assertTrue(rendered.contains("auth-user-pass\n"))
    }

    @Test
    fun rendersPinnedWireGuardConfig() {
        val text = """
            [Interface]
            PrivateKey = example

            [Peer]
            PublicKey = peer
            Endpoint = vpn.example.com:51820
        """.trimIndent() + "\n"
        val config = ConfigImporter.parse(text)
        val rendered = PinnedConfigRenderer.render(config, config.endpoints.single(), "1.1.1.1")

        assertTrue(rendered.startsWith("# pinned by VPN Project\n# vpn.example.com -> 1.1.1.1\n"))
        assertTrue(rendered.contains("Endpoint = 1.1.1.1:51820\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesPrivatePinnedAddress() {
        val text = """
            client
            remote de.example.com 443 tcp
        """.trimIndent()
        val config = ConfigImporter.parse(text)
        PinnedConfigRenderer.render(config, config.endpoints.single(), "10.10.34.35")
    }
}
