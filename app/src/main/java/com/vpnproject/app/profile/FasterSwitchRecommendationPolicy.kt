package com.vpnproject.app.profile

import java.util.Locale

/** Small local-memory policy for de-prioritizing a faster-switch that did not improve latency. */
object FasterSwitchRecommendationPolicy {
    const val NO_IMPROVEMENT_PENALTY = 1
    private const val PREFERENCE_KEY_PREFIX = "faster_switch_no_improvement"

    fun preferenceKey(profileId: String, network: String?): String? {
        val profileKey = profileId.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .take(80)
            .takeIf { it.isNotBlank() }
            ?: return null
        val networkKey = normalizeNetworkLabel(network)
            ?.replace(Regex("[^a-z0-9+_.-]"), "-")
            ?.take(32)
            ?: return null
        return "$PREFERENCE_KEY_PREFIX.$profileKey.$networkKey"
    }

    fun noImprovementPenalty(
        lastAttemptEpochMs: Long?,
        nowEpochMs: Long,
        freshnessWindowMs: Long
    ): Int {
        val checkedAt = lastAttemptEpochMs ?: return 0
        val isRecent = checkedAt > 0L && checkedAt <= nowEpochMs &&
            nowEpochMs - checkedAt <= freshnessWindowMs
        return if (isRecent) NO_IMPROVEMENT_PENALTY else 0
    }

    private fun normalizeNetworkLabel(value: String?): String? {
        val parts = value.orEmpty()
            .split('+')
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() && it != "vpn" && it != "unknown" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("+")
    }
}
