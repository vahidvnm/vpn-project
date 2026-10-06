package com.vpnproject.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutingInputParserTest {
    @Test
    fun dnsParserKeepsOnlyPublicIpv4AndCanonicalizesDuplicates() {
        val result = VpnRoutingInputParser.parseDnsServers(
            "1.1.1.1, 8.8.8.8; 001.001.001.001\n192.168.1.1 127.0.0.1 localhost ::1"
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.servers)
        assertEquals(listOf("192.168.1.1", "127.0.0.1", "localhost", "::1"), result.rejectedEntries)
        assertEquals(0, result.omittedCount)
    }

    @Test
    fun dnsParserRejectsReservedAndNonPublicAddresses() {
        val result = VpnRoutingInputParser.parseDnsServers(
            "0.0.0.0 10.0.0.1 100.64.0.1 169.254.1.1 198.18.0.1 224.0.0.1"
        )

        assertTrue(result.servers.isEmpty())
        assertEquals(6, result.rejectedEntries.size)
    }

    @Test
    fun dnsParserCapsDistinctServerListAndReportsOmittedEntries() {
        val result = VpnRoutingInputParser.parseDnsServers(
            "1.1.1.1 8.8.8.8 9.9.9.9 208.67.222.222 94.140.14.14",
            maxServers = 3
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"), result.servers)
        assertEquals(2, result.omittedCount)
    }

    @Test
    fun bypassParserValidatesNamesDeduplicatesAndExcludesThisApp() {
        val result = VpnRoutingInputParser.parseBypassPackages(
            "com.example.browser; com.example.browser\ncom.vpnproject.app bad.package. org.example.client",
            ownPackageName = "com.vpnproject.app"
        )

        assertEquals(listOf("com.example.browser", "org.example.client"), result.packages)
        assertEquals(listOf("bad.package."), result.invalidEntries)
        assertEquals(1, result.selfPackageEntries)
        assertEquals(0, result.omittedCount)
    }

    @Test
    fun bypassParserCapsListAndReportsOmittedPackages() {
        val result = VpnRoutingInputParser.parseBypassPackages(
            "one.two three.four five.six",
            ownPackageName = "com.vpnproject.app",
            maxPackages = 2
        )

        assertEquals(listOf("one.two", "three.four"), result.packages)
        assertEquals(1, result.omittedCount)
    }
}
