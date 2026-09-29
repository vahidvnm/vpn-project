package com.vpnproject.app.core

/**
 * Early health score used for ordering candidates.
 * Lower is better: latency + recentFailurePenalty - lastSuccessBonus.
 */
object HealthScorer {
    private const val RECENT_FAILURE_PENALTY_MS = 1_000
    private const val LAST_SUCCESS_BONUS_MS = 250

    fun score(
        latencyMs: Long?,
        recentFailureCount: Int = 0,
        wasLastGood: Boolean = false
    ): Int? {
        if (latencyMs == null) return null
        val latency = latencyMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val penalty = recentFailureCount.coerceAtLeast(0) * RECENT_FAILURE_PENALTY_MS
        val bonus = if (wasLastGood) LAST_SUCCESS_BONUS_MS else 0
        return (latency + penalty - bonus).coerceAtLeast(0)
    }
}
