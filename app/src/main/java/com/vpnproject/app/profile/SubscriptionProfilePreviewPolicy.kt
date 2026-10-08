package com.vpnproject.app.profile

import kotlin.random.Random

/**
 * Chooses the small preview shown for a subscription before or after per-profile
 * results have been saved. This policy only orders existing metadata; it never
 * starts a connection or a test.
 */
object SubscriptionProfilePreviewPolicy {
    const val DEFAULT_LIMIT = 8
    private const val MAX_PRE_TEST_WEIGHT_BONUS = 20L

    data class Preview(
        val profiles: List<VpnProfile>,
        val hasSavedResults: Boolean
    )

    fun select(
        profiles: List<VpnProfile>,
        limit: Int = DEFAULT_LIMIT,
        rankingComparator: Comparator<VpnProfile>,
        random: Random = Random.Default
    ): Preview {
        val uniqueProfiles = profiles.distinctBy { it.id }
        val hasSavedResults = uniqueProfiles.any { profile ->
            profile.lastTestedEpochMs != null || profile.lastVerifiedEpochMs != null
        }
        if (limit <= 0 || uniqueProfiles.isEmpty()) {
            return Preview(emptyList(), hasSavedResults)
        }

        val selected = if (hasSavedResults) {
            uniqueProfiles.sortedWith(rankingComparator).take(limit)
        } else {
            weightedRandomSample(uniqueProfiles, limit, random)
        }
        return Preview(selected, hasSavedResults)
    }

    private fun weightedRandomSample(
        profiles: List<VpnProfile>,
        limit: Int,
        random: Random
    ): List<VpnProfile> {
        val remaining = profiles.toMutableList()
        val selected = ArrayList<VpnProfile>(minOf(limit, remaining.size))
        repeat(minOf(limit, remaining.size)) {
            val weights = remaining.map { profile ->
                1L + profile.useCount.coerceIn(0L, MAX_PRE_TEST_WEIGHT_BONUS)
            }
            val totalWeight = weights.sum()
            var ticket = random.nextLong(totalWeight)
            var selectedIndex = remaining.lastIndex
            for (index in weights.indices) {
                if (ticket < weights[index]) {
                    selectedIndex = index
                    break
                }
                ticket -= weights[index]
            }
            selected += remaining.removeAt(selectedIndex)
        }
        return selected
    }
}
