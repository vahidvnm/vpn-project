package com.vpnproject.app.engine

data class NetworkRebindDecision(
    val shouldSchedule: Boolean,
    val delayMs: Long,
    val reason: String
)

class NetworkRebindPolicy(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    init {
        require(minIntervalMs >= 0L) { "minIntervalMs cannot be negative." }
        require(debounceMs >= 0L) { "debounceMs cannot be negative." }
    }

    fun evaluate(
        isRunning: Boolean,
        nowEpochMs: Long,
        lastRequestedEpochMs: Long,
        reason: String
    ): NetworkRebindDecision {
        if (!isRunning) {
            return NetworkRebindDecision(
                shouldSchedule = false,
                delayMs = 0L,
                reason = "Tunnel is not running; ignoring network change: $reason"
            )
        }

        val elapsedMs = nowEpochMs - lastRequestedEpochMs
        val canSchedule = lastRequestedEpochMs <= 0L || elapsedMs >= minIntervalMs
        return if (canSchedule) {
            NetworkRebindDecision(
                shouldSchedule = true,
                delayMs = debounceMs,
                reason = reason
            )
        } else {
            NetworkRebindDecision(
                shouldSchedule = false,
                delayMs = 0L,
                reason = "Network rebind suppressed for ${minIntervalMs - elapsedMs}ms: $reason"
            )
        }
    }

    private companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 2_000L
        const val DEFAULT_DEBOUNCE_MS = 750L
    }
}
