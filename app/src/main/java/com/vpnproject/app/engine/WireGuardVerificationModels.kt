package com.vpnproject.app.engine

data class WireGuardStatsSnapshot(
    val rxBytes: Long,
    val txBytes: Long,
    val capturedAtEpochMs: Long
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class WireGuardVerificationPolicy(
    val maxAttempts: Int = 6,
    val intervalMs: Long = 1_500L,
    val minTrafficDeltaBytes: Long = 1L
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive." }
        require(intervalMs >= 0L) { "intervalMs cannot be negative." }
        require(minTrafficDeltaBytes >= 0L) { "minTrafficDeltaBytes cannot be negative." }
    }
}

data class WireGuardVerificationResult(
    val verified: Boolean,
    val statsMoved: Boolean,
    val egressVerified: Boolean,
    val reason: String,
    val rxBytes: Long,
    val txBytes: Long,
    val egressIp: String? = null,
    val attempts: Int
)

fun interface WireGuardStatsSource {
    fun snapshot(): WireGuardStatsSnapshot
}

fun interface EgressIpResolver {
    fun fetchPublicIp(): String?
}
