package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayRoutePolicyTest {
    @Test
    fun fullTunnelCapturesIpv4AndIpv6DefaultRoutes() {
        assertEquals(
            listOf(IpCidrRoute("0.0.0.0", 0), IpCidrRoute("::", 0)),
            XrayRoutePolicy.FULL_TUNNEL_ROUTES
        )
        assertEquals("172.19.0.1", XrayRoutePolicy.TUN_IPV4_ADDRESS)
        assertEquals("fd00:1111:2222:3333::1", XrayRoutePolicy.TUN_IPV6_ADDRESS)
    }

    @Test
    fun lanBypassIncludesPrivateAndLinkLocalRangesForBothFamilies() {
        val ranges = XrayRoutePolicy.LAN_BYPASS_CIDRS

        assertTrue("10/8 must remain local.", "10.0.0.0/8" in ranges)
        assertTrue("RFC1918 172/12 must remain local.", "172.16.0.0/12" in ranges)
        assertTrue("RFC1918 192.168/16 must remain local.", "192.168.0.0/16" in ranges)
        assertTrue("IPv4 link-local must remain local.", "169.254.0.0/16" in ranges)
        assertTrue("IPv6 ULA must remain local.", "fc00::/7" in ranges)
        assertTrue("IPv6 link-local must remain local.", "fe80::/10" in ranges)
        assertTrue("IPv6 loopback must remain local.", "::1/128" in ranges)
    }
}
