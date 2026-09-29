package com.vpnproject.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpClassifierTest {
    @Test
    fun acceptsPublicIpv4() {
        assertTrue(IpClassifier.isIpv4Literal("1.1.1.1"))
        assertTrue(IpClassifier.isPublicIpv4("8.8.8.8"))
        assertTrue(IpClassifier.isPublicIpv4("185.199.108.133"))
    }

    @Test
    fun rejectsReservedOrInvalidIpv4() {
        assertTrue(IpClassifier.isReservedIpv4("10.10.34.35"))
        assertTrue(IpClassifier.isReservedIpv4("192.168.1.1"))
        assertTrue(IpClassifier.isReservedIpv4("172.16.0.1"))
        assertTrue(IpClassifier.isReservedIpv4("100.64.1.1"))
        assertTrue(IpClassifier.isReservedIpv4("198.51.100.10"))
        assertTrue(IpClassifier.isReservedIpv4("203.0.113.10"))
        assertFalse(IpClassifier.isPublicIpv4("999.1.1.1"))
        assertFalse(IpClassifier.isIpv4Literal("vpn.example.com"))
    }

    @Test
    fun classifiesPublicIpv6Literals() {
        assertTrue(IpClassifier.isIpv6Literal("2606:4700:d0::a29f:c001"))
        assertTrue(IpClassifier.isPublicIpv6("2606:4700:d0::a29f:c001"))
        assertTrue(IpClassifier.isPublicIpv6("[2606:4700:d0::a29f:c001]"))
    }

    @Test
    fun rejectsReservedOrInvalidIpv6() {
        assertFalse(IpClassifier.isPublicIpv6("::1"))
        assertFalse(IpClassifier.isPublicIpv6("fc00::1"))
        assertFalse(IpClassifier.isPublicIpv6("fe80::1"))
        assertFalse(IpClassifier.isPublicIpv6("2001:db8::1"))
        assertFalse(IpClassifier.isIpv6Literal("vpn.example.com"))
    }
}
