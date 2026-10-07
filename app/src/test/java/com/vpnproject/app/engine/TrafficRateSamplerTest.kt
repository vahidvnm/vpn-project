package com.vpnproject.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficRateSamplerTest {
    @Test
    fun computesObservedRatesFromMonotonicCounterSamples() {
        val sampler = TrafficRateSampler()

        assertNull(sampler.sample("XRAY:profile-a", 1_000, 500, 10_000))
        val rate = sampler.sample("XRAY:profile-a", 3_000, 1_500, 12_000)

        assertEquals(1_000.0, rate?.downloadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(500.0, rate?.uploadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(2_000L, rate?.intervalMs ?: -1L)
    }

    @Test
    fun waitsForMinimumIntervalWithoutMovingTheBaseline() {
        val sampler = TrafficRateSampler(minimumSampleIntervalMs = 1_000, maximumSampleGapMs = 5_000)

        assertNull(sampler.sample("WG:profile-a", 100, 100, 2_000))
        assertNull(sampler.sample("WG:profile-a", 300, 300, 2_500))
        val rate = sampler.sample("WG:profile-a", 1_100, 600, 3_000)

        assertEquals(1_000.0, rate?.downloadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(500.0, rate?.uploadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(1_000L, rate?.intervalMs ?: -1L)
    }

    @Test
    fun doesNotCompareCountersAcrossProfilesOrAfterResetAndLongGaps() {
        val sampler = TrafficRateSampler(minimumSampleIntervalMs = 1_000, maximumSampleGapMs = 5_000)

        assertNull(sampler.sample("XRAY:profile-a", 5_000, 2_000, 1_000))
        assertNull(sampler.sample("XRAY:profile-b", 10, 10, 2_000))
        assertNull(sampler.sample("XRAY:profile-b", 20, 20, 8_000))
        val afterGapRate = sampler.sample("XRAY:profile-b", 30, 40, 9_000)
        assertEquals(10.0, afterGapRate?.downloadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(20.0, afterGapRate?.uploadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(1_000L, afterGapRate?.intervalMs ?: -1L)

        val rate = sampler.sample("XRAY:profile-b", 130, 140, 10_000)
        assertEquals(100.0, rate?.downloadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(100.0, rate?.uploadBytesPerSecond ?: 0.0, 0.001)
    }

    @Test
    fun countersMovingBackwardsResetTheSampleBaseline() {
        val sampler = TrafficRateSampler()

        assertNull(sampler.sample("WG:profile-a", 100, 100, 1_000))
        assertNull(sampler.sample("WG:profile-a", 0, 10, 2_000))
        val rate = sampler.sample("WG:profile-a", 2_000, 1_010, 3_000)

        assertEquals(2_000.0, rate?.downloadBytesPerSecond ?: 0.0, 0.001)
        assertEquals(1_000.0, rate?.uploadBytesPerSecond ?: 0.0, 0.001)
    }
}
