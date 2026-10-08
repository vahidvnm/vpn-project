package com.vpnproject.app.profile

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionProfilePreviewPolicyTest {
    @Test
    fun untestedGroupGetsRandomUniquePreviewWithRequestedLimit() {
        val profiles = (1..20).map { profile("profile-$it") }

        val preview = SubscriptionProfilePreviewPolicy.select(
            profiles = profiles,
            limit = 8,
            rankingComparator = compareBy { it.id },
            random = Random(17)
        )

        assertFalse(preview.hasSavedResults)
        assertEquals(8, preview.profiles.size)
        assertEquals(8, preview.profiles.map { it.id }.toSet().size)
        assertTrue(preview.profiles.any { it.id != "profile-1" })
    }

    @Test
    fun savedResultsSwitchPreviewToRankedProfiles() {
        val profiles = (1..10).map { index ->
            profile(
                id = "profile-$index",
                testedAt = if (index == 10) 100L else null,
                score = if (index == 10) 1 else 100 - index
            )
        }

        val preview = SubscriptionProfilePreviewPolicy.select(
            profiles = profiles,
            limit = 8,
            rankingComparator = compareBy<VpnProfile> { it.lastTestScore ?: Int.MAX_VALUE },
            random = Random(17)
        )

        assertTrue(preview.hasSavedResults)
        assertEquals(8, preview.profiles.size)
        assertEquals("profile-10", preview.profiles.first().id)
        assertEquals("profile-3", preview.profiles.last().id)
    }

    @Test
    fun preTestRandomPreviewFavorsFrequentlyUsedProfiles() {
        val popular = profile("popular", useCount = 20L)
        val otherProfiles = (1..4).map { profile("other-$it") }
        val profiles = listOf(popular) + otherProfiles
        var popularSelections = 0

        repeat(300) { seed ->
            val preview = SubscriptionProfilePreviewPolicy.select(
                profiles = profiles,
                limit = 1,
                rankingComparator = compareBy { it.id },
                random = Random(seed)
            )
            if (preview.profiles.single().id == popular.id) popularSelections++
        }

        assertTrue("popular profile was selected $popularSelections times", popularSelections > 200)
    }

    private fun profile(
        id: String,
        useCount: Long = 0L,
        testedAt: Long? = null,
        score: Int? = null
    ) = VpnProfile(
        id = id,
        name = id,
        kind = VpnProfileKind.XRAY,
        endpoints = emptyList(),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        lastTestedEpochMs = testedAt,
        lastTestScore = score,
        useCount = useCount
    )
}
