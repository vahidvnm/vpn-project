package com.vpnproject.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FasterSwitchRecommendationPolicyTest {
    @Test
    fun preferenceMemoryIsScopedToProfileAndUnderlyingNetwork() {
        val wifiKey = FasterSwitchRecommendationPolicy.preferenceKey("profile-123", "wifi+vpn")
        val cellularKey = FasterSwitchRecommendationPolicy.preferenceKey("profile-123", "cellular")
        val otherProfileKey = FasterSwitchRecommendationPolicy.preferenceKey("profile-456", "wifi")

        assertEquals("faster_switch_no_improvement.profile-123.wifi", wifiKey)
        assertNotEquals(wifiKey, cellularKey)
        assertNotEquals(wifiKey, otherProfileKey)
        assertNull(FasterSwitchRecommendationPolicy.preferenceKey("profile-123", "vpn"))
    }

    @Test
    fun onlyRecentNonImprovementIsPenalized() {
        val now = 100_000L
        val freshnessWindow = 1_000L

        assertEquals(
            FasterSwitchRecommendationPolicy.NO_IMPROVEMENT_PENALTY,
            FasterSwitchRecommendationPolicy.noImprovementPenalty(now - freshnessWindow, now, freshnessWindow)
        )
        assertEquals(0, FasterSwitchRecommendationPolicy.noImprovementPenalty(now - freshnessWindow - 1, now, freshnessWindow))
        assertEquals(0, FasterSwitchRecommendationPolicy.noImprovementPenalty(now + 1, now, freshnessWindow))
        assertEquals(0, FasterSwitchRecommendationPolicy.noImprovementPenalty(null, now, freshnessWindow))
    }
}
