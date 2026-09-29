package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRebindPolicyTest {
    @Test
    fun ignoresNetworkChangesWhenTunnelIsNotRunning() {
        val policy = NetworkRebindPolicy(minIntervalMs = 2_000, debounceMs = 750)

        val decision = policy.evaluate(
            isRunning = false,
            nowEpochMs = 10_000,
            lastRequestedEpochMs = 0,
            reason = "network available"
        )

        assertFalse(decision.shouldSchedule)
        assertEquals(0L, decision.delayMs)
        assertTrue(decision.reason.contains("not running"))
    }

    @Test
    fun schedulesFirstRunningNetworkChangeWithDebounce() {
        val policy = NetworkRebindPolicy(minIntervalMs = 2_000, debounceMs = 750)

        val decision = policy.evaluate(
            isRunning = true,
            nowEpochMs = 10_000,
            lastRequestedEpochMs = 0,
            reason = "network available"
        )

        assertTrue(decision.shouldSchedule)
        assertEquals(750L, decision.delayMs)
        assertEquals("network available", decision.reason)
    }

    @Test
    fun suppressesRapidDuplicateNetworkChanges() {
        val policy = NetworkRebindPolicy(minIntervalMs = 2_000, debounceMs = 750)

        val decision = policy.evaluate(
            isRunning = true,
            nowEpochMs = 10_500,
            lastRequestedEpochMs = 10_000,
            reason = "network capabilities changed"
        )

        assertFalse(decision.shouldSchedule)
        assertTrue(decision.reason.contains("suppressed"))
    }
}
