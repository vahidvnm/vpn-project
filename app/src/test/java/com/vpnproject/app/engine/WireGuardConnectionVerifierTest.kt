package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConnectionVerifierTest {
    @Test
    fun verifiesWhenStatsMoveAndPublicEgressIpExists() {
        val stats = ArrayDeque(
            listOf(
                WireGuardStatsSnapshot(rxBytes = 0, txBytes = 0, capturedAtEpochMs = 1),
                WireGuardStatsSnapshot(rxBytes = 0, txBytes = 0, capturedAtEpochMs = 2),
                WireGuardStatsSnapshot(rxBytes = 140, txBytes = 80, capturedAtEpochMs = 3)
            )
        )
        val verifier = WireGuardConnectionVerifier(
            statsSource = WireGuardStatsSource { stats.removeFirst() },
            egressIpResolver = EgressIpResolver { "8.8.8.8" },
            policy = WireGuardVerificationPolicy(maxAttempts = 3, intervalMs = 0, minTrafficDeltaBytes = 1)
        )

        val result = verifier.verify()

        assertTrue(result.verified)
        assertTrue(result.statsMoved)
        assertTrue(result.egressVerified)
        assertEquals("8.8.8.8", result.egressIp)
        assertEquals(140L, result.rxBytes)
        assertEquals(80L, result.txBytes)
    }

    @Test
    fun doesNotVerifyWhenOnlyStatsMoveWithoutEgressIp() {
        val stats = ArrayDeque(
            listOf(
                WireGuardStatsSnapshot(rxBytes = 10, txBytes = 10, capturedAtEpochMs = 1),
                WireGuardStatsSnapshot(rxBytes = 20, txBytes = 20, capturedAtEpochMs = 2),
                WireGuardStatsSnapshot(rxBytes = 30, txBytes = 30, capturedAtEpochMs = 3)
            )
        )
        val verifier = WireGuardConnectionVerifier(
            statsSource = WireGuardStatsSource { stats.removeFirst() },
            egressIpResolver = EgressIpResolver { null },
            policy = WireGuardVerificationPolicy(maxAttempts = 2, intervalMs = 0, minTrafficDeltaBytes = 1)
        )

        val result = verifier.verify()

        assertFalse(result.verified)
        assertTrue(result.statsMoved)
        assertFalse(result.egressVerified)
        assertTrue(result.reason.contains("egress IP"))
    }

    @Test
    fun doesNotVerifyPrivateEgressIp() {
        val stats = ArrayDeque(
            listOf(
                WireGuardStatsSnapshot(rxBytes = 0, txBytes = 0, capturedAtEpochMs = 1),
                WireGuardStatsSnapshot(rxBytes = 50, txBytes = 50, capturedAtEpochMs = 2)
            )
        )
        val verifier = WireGuardConnectionVerifier(
            statsSource = WireGuardStatsSource { stats.removeFirst() },
            egressIpResolver = EgressIpResolver { "192.168.1.1" },
            policy = WireGuardVerificationPolicy(maxAttempts = 1, intervalMs = 0, minTrafficDeltaBytes = 1)
        )

        val result = verifier.verify()

        assertFalse(result.verified)
        assertTrue(result.statsMoved)
        assertFalse(result.egressVerified)
    }
}
