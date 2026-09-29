package com.vpnproject.app.engine

import com.vpnproject.app.core.IpClassifier

class WireGuardConnectionVerifier(
    private val statsSource: WireGuardStatsSource,
    private val egressIpResolver: EgressIpResolver,
    private val policy: WireGuardVerificationPolicy = WireGuardVerificationPolicy(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun verify(): WireGuardVerificationResult {
        val baseline = safeSnapshot() ?: return WireGuardVerificationResult(
            verified = false,
            statsMoved = false,
            egressVerified = false,
            reason = "Could not read initial WireGuard statistics.",
            rxBytes = 0L,
            txBytes = 0L,
            attempts = 0
        )

        var lastSnapshot = baseline
        var lastPublicEgressIp: String? = null
        var lastReason = "WireGuard verification did not complete."

        for (attempt in 1..policy.maxAttempts) {
            val egressIp = runCatching { egressIpResolver.fetchPublicIp() }.getOrNull()
            if (egressIp != null && IpClassifier.isPublicIpv4(egressIp)) {
                lastPublicEgressIp = egressIp
            }

            lastSnapshot = safeSnapshot() ?: lastSnapshot
            val statsMoved = trafficDelta(baseline, lastSnapshot) >= policy.minTrafficDeltaBytes
            val egressVerified = lastPublicEgressIp != null

            if (statsMoved && egressVerified) {
                return WireGuardVerificationResult(
                    verified = true,
                    statsMoved = true,
                    egressVerified = true,
                    reason = "WireGuard transferred traffic and external egress IP was verified.",
                    rxBytes = lastSnapshot.rxBytes,
                    txBytes = lastSnapshot.txBytes,
                    egressIp = lastPublicEgressIp,
                    attempts = attempt
                )
            }

            lastReason = when {
                statsMoved && !egressVerified ->
                    "WireGuard traffic moved, but no public egress IP endpoint answered."
                !statsMoved && egressVerified ->
                    "Public egress IP answered, but WireGuard statistics did not move. WireGuard/UDP may be blocked or the endpoint handshake failed."
                else ->
                    "No verified WireGuard traffic or public egress IP yet. WireGuard/UDP may be blocked on this network."
            }

            if (attempt < policy.maxAttempts && policy.intervalMs > 0L) {
                sleeper(policy.intervalMs)
            }
        }

        val finalStatsMoved = trafficDelta(baseline, lastSnapshot) >= policy.minTrafficDeltaBytes
        val finalEgressVerified = lastPublicEgressIp != null
        return WireGuardVerificationResult(
            verified = false,
            statsMoved = finalStatsMoved,
            egressVerified = finalEgressVerified,
            reason = lastReason,
            rxBytes = lastSnapshot.rxBytes,
            txBytes = lastSnapshot.txBytes,
            egressIp = lastPublicEgressIp,
            attempts = policy.maxAttempts
        )
    }

    private fun safeSnapshot(): WireGuardStatsSnapshot? = runCatching { statsSource.snapshot() }.getOrNull()

    private fun trafficDelta(initial: WireGuardStatsSnapshot, current: WireGuardStatsSnapshot): Long =
        (current.totalBytes - initial.totalBytes).coerceAtLeast(0L)
}
