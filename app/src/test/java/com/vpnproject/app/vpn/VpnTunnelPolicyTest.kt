package com.vpnproject.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelPolicyTest {
    @Test
    fun defaultPolicyBuildsFullIpv4TunnelWithControlledDns() {
        val policy = VpnTunnelPolicy.defaultFullTunnel()

        assertEquals(TunAddress("10.111.0.2", 32), policy.tunAddress)
        assertEquals(listOf(TunRoute("0.0.0.0", 0)), policy.routes)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), policy.dnsServers)
        assertTrue("IPv6 must be captured by default.", policy.blockIpv6OutsideTunnel)
        assertTrue(policy.validate().isEmpty())
    }

    @Test
    fun rejectsPrivateDnsServers() {
        val policy = VpnTunnelPolicy.defaultFullTunnel().copy(
            dnsServers = listOf("192.168.1.1")
        )

        assertTrue(policy.validate().any { it.contains("DNS server") })
    }
}
