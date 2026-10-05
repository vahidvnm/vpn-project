package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class V2RayLinkInspectorTest {
    @Test
    fun labelsReality() {
        val descriptor = V2RayLinkInspector.inspectLink(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&type=tcp&sni=www.example.com&pbk=KEY&sid=1#r"
        )
        assertEquals("Reality", descriptor?.shortLabel)
        assertTrue(descriptor?.runtimeSupported == true)
    }

    @Test
    fun labelsHttpUpgradeWithCaseInsensitiveParams() {
        val descriptor = V2RayLinkInspector.inspectLink(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?Security=tls&Type=HTTPUpgrade&host=front.example&path=%2Fup#hu"
        )
        assertEquals("HTTPUpgrade", descriptor?.shortLabel)
    }

    @Test
    fun labelsGrpcTlsFromVmess() {
        val vmess = """
            {"v":"2","ps":"🇩🇪 Germany / Berlin / fast grpc","add":"example.com","port":"443","id":"11111111-1111-1111-1111-111111111111","net":"grpc","tls":"tls","path":"svc"}
        """.trimIndent()
        val link = "vmess://${Base64.getEncoder().encodeToString(vmess.toByteArray(StandardCharsets.UTF_8))}"
        val descriptor = V2RayLinkInspector.inspectLink(link)
        assertEquals("gRPC/TLS", descriptor?.shortLabel)
        assertEquals("🇩🇪 Germany / Berlin", descriptor?.displayName)
    }

    @Test
    fun labelsTcpHttpCamouflage() {
        val descriptor = V2RayLinkInspector.inspectLink(
            "vless://11111111-1111-1111-1111-111111111111@example.com:80?type=tcp&headerType=http&host=front.example#tcp-http"
        )
        assertEquals("TCP HTTP", descriptor?.shortLabel)
    }

    @Test
    fun readsFirstLinkFromEncodedSubscription() {
        val subscription = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws#ws"
        val encoded = Base64.getEncoder().encodeToString(subscription.toByteArray(StandardCharsets.UTF_8))
        val descriptor = V2RayLinkInspector.inspect(encoded)
        assertEquals("WS/TLS", descriptor?.shortLabel)
    }

    @Test
    fun decodesClashUppercaseEscapedFlagsInDisplayNames() {
        assertEquals(
            "US 🇺🇸 / @Raydikalx",
            V2RayLinkInspector.safeDisplayName("US \\U0001F1FA\\U0001F1F8 | @Raydikalx | C1F61F")
        )
    }

    @Test
    fun rejectsRawLinkOrSecretLikeDisplayNames() {
        assertNull(V2RayLinkInspector.safeDisplayName("vless://uuid@example.com:443?security=reality"))
        assertNull(V2RayLinkInspector.safeDisplayName("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun marksUnsupportedRuntimeTransport() {
        val descriptor = V2RayLinkInspector.inspectLink(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=kcp#old-kcp"
        )
        assertEquals("mKCP", descriptor?.shortLabel)
        assertFalse(descriptor?.runtimeSupported == true)
        assertEquals("Unsupported transport kcp", descriptor?.runtimeIssue)
    }

    @Test
    fun ignoresNonConfigText() {
        assertNull(V2RayLinkInspector.inspect("hello, no vpn secrets here"))
    }
}
