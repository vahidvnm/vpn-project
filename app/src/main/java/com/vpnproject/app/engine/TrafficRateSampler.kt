package com.vpnproject.app.engine

/**
 * Estimates rates from engine-reported tunnel byte counters. It performs no
 * network request and must not be presented as a speed-test result.
 */
data class TrafficRateSample(
    val downloadBytesPerSecond: Double,
    val uploadBytesPerSecond: Double,
    val intervalMs: Long
)

class TrafficRateSampler(
    private val minimumSampleIntervalMs: Long = DEFAULT_MINIMUM_SAMPLE_INTERVAL_MS,
    private val maximumSampleGapMs: Long = DEFAULT_MAXIMUM_SAMPLE_GAP_MS
) {
    private var previousScopeKey: String? = null
    private var previousRxBytes: Long? = null
    private var previousTxBytes: Long? = null
    private var previousAtMs: Long? = null

    init {
        require(minimumSampleIntervalMs > 0L) { "minimumSampleIntervalMs must be positive." }
        require(maximumSampleGapMs >= minimumSampleIntervalMs) {
            "maximumSampleGapMs must be at least the minimum sample interval."
        }
    }

    @Synchronized
    fun sample(
        scopeKey: String?,
        rxBytes: Long?,
        txBytes: Long?,
        nowMonotonicMs: Long
    ): TrafficRateSample? {
        if (scopeKey.isNullOrBlank() || rxBytes == null || txBytes == null || rxBytes < 0L || txBytes < 0L) {
            reset()
            return null
        }

        val lastRx = previousRxBytes
        val lastTx = previousTxBytes
        val lastAt = previousAtMs
        val sameScope = previousScopeKey == scopeKey
        if (!sameScope || lastRx == null || lastTx == null || lastAt == null) {
            store(scopeKey, rxBytes, txBytes, nowMonotonicMs)
            return null
        }

        val elapsedMs = nowMonotonicMs - lastAt
        if (elapsedMs <= 0L) {
            // A non-monotonic sample is discarded without replacing a valid baseline.
            return null
        }
        if (elapsedMs < minimumSampleIntervalMs) return null

        if (elapsedMs > maximumSampleGapMs || rxBytes < lastRx || txBytes < lastTx) {
            store(scopeKey, rxBytes, txBytes, nowMonotonicMs)
            return null
        }

        store(scopeKey, rxBytes, txBytes, nowMonotonicMs)
        return TrafficRateSample(
            downloadBytesPerSecond = (rxBytes - lastRx).toDouble() * 1_000.0 / elapsedMs,
            uploadBytesPerSecond = (txBytes - lastTx).toDouble() * 1_000.0 / elapsedMs,
            intervalMs = elapsedMs
        )
    }

    @Synchronized
    fun reset() {
        previousScopeKey = null
        previousRxBytes = null
        previousTxBytes = null
        previousAtMs = null
    }

    private fun store(scopeKey: String, rxBytes: Long, txBytes: Long, nowMonotonicMs: Long) {
        previousScopeKey = scopeKey
        previousRxBytes = rxBytes
        previousTxBytes = txBytes
        previousAtMs = nowMonotonicMs
    }

    private companion object {
        const val DEFAULT_MINIMUM_SAMPLE_INTERVAL_MS = 1_000L
        const val DEFAULT_MAXIMUM_SAMPLE_GAP_MS = 30_000L
    }
}
