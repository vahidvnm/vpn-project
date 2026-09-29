package com.vpnproject.app.engine

import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

class WireGuardBackendStatsSource(
    private val backend: GoBackend,
    private val tunnel: Tunnel,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) : WireGuardStatsSource {
    override fun snapshot(): WireGuardStatsSnapshot {
        val statistics = backend.getStatistics(tunnel)
        return WireGuardStatsSnapshot(
            rxBytes = statistics.totalRx(),
            txBytes = statistics.totalTx(),
            capturedAtEpochMs = nowEpochMs()
        )
    }
}
