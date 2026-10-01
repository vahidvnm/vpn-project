package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayTrafficStatsParserTest {
    @Test
    fun parsesObservedXrayProxyStats() {
        val stats = XrayTrafficStatsParser.parse("proxy,downlink,205859;proxy,uplink,126336;")

        assertEquals(205859L, stats.rxBytes)
        assertEquals(126336L, stats.txBytes)
    }

    @Test
    fun sumsMultipleDownlinkAndUplinkEntries() {
        val stats = XrayTrafficStatsParser.parse(
            "proxy,downlink,100;direct,downlink,7;proxy,uplink,20;direct,uplink,3;"
        )

        assertEquals(107L, stats.rxBytes)
        assertEquals(23L, stats.txBytes)
    }

    @Test
    fun ignoresBlankOrUnrecognizedStats() {
        val stats = XrayTrafficStatsParser.parse("proxy,other,100;")

        assertNull(stats.rxBytes)
        assertNull(stats.txBytes)
    }
}
