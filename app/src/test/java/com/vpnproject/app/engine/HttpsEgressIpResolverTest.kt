package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsEgressIpResolverTest {
    @Test
    fun extractsIpFromCloudflareTrace() {
        val resolver = HttpsEgressIpResolver(endpoints = emptyList())

        assertEquals(
            "8.8.8.8",
            resolver.extractIpv4("fl=123\nip=8.8.8.8\nts=123")
        )
    }

    @Test
    fun extractsIpFromPlainTextOrJson() {
        val resolver = HttpsEgressIpResolver(endpoints = emptyList())

        assertEquals("1.1.1.1", resolver.extractIpv4("{\"ip\":\"1.1.1.1\"}"))
    }

    @Test
    fun extractsFirstPublicIpWhenPrivateIpAppearsEarlier() {
        val resolver = HttpsEgressIpResolver(endpoints = emptyList())

        assertEquals("8.8.4.4", resolver.extractIpv4("private=10.0.0.1 public=8.8.4.4"))
    }

    @Test
    fun rejectsPrivateIpFromResponse() {
        val resolver = HttpsEgressIpResolver(endpoints = emptyList())

        assertNull(resolver.extractIpv4("ip=192.168.1.10"))
    }
}
