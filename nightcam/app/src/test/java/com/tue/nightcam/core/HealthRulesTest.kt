package com.tue.nightcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthRulesTest {

    @Test
    fun storage_stopsBelowReserve() {
        assertTrue(HealthRules.storage(500_000_000L, 1_000_000_000L) is HealthRules.Decision.Stop)
        assertEquals(HealthRules.Decision.Continue, HealthRules.storage(2_000_000_000L, 1_000_000_000L))
    }

    @Test
    fun battery_stopsLowAndUnplugged_only() {
        assertTrue(HealthRules.battery(4, plugged = false) is HealthRules.Decision.Stop)
        assertEquals(HealthRules.Decision.Continue, HealthRules.battery(4, plugged = true))
        assertEquals(HealthRules.Decision.Continue, HealthRules.battery(5, plugged = false))
    }

    @Test
    fun thermal_severeDowngradesOnlyWhenEnabled() {
        assertTrue(HealthRules.thermal(3, downgradeEnabled = true) is HealthRules.Decision.Downgrade)
        assertEquals(HealthRules.Decision.Continue, HealthRules.thermal(3, downgradeEnabled = false))
    }

    @Test
    fun thermal_criticalStopsRegardless() {
        assertTrue(HealthRules.thermal(4, downgradeEnabled = false) is HealthRules.Decision.Stop)
        assertEquals(HealthRules.Decision.Continue, HealthRules.thermal(2, downgradeEnabled = true))
    }

    @Test
    fun stalled_needsNoGrowthForAMinute() {
        assertTrue(HealthRules.stalled(100L, 100L, 60L))
        assertFalse(HealthRules.stalled(100L, 100L, 59L))
        assertFalse(HealthRules.stalled(100L, 101L, 600L))
    }

    @Test
    fun restartBudget_threePerHour() {
        val now = 10_000_000L
        assertTrue(HealthRules.restartAllowed(listOf(now - 1_000L, now - 2_000L), now))
        assertFalse(HealthRules.restartAllowed(listOf(now - 1_000L, now - 2_000L, now - 3_000L), now))
        // Lần cũ hơn một giờ không tính
        assertTrue(HealthRules.restartAllowed(listOf(now - 3_600_001L, now - 2_000L, now - 1_000L), now))
    }

    @Test
    fun downgrade_fourKBecomesFullHd_otherwiseCutsBitrate() {
        assertEquals(RecordingConfig.RESOLUTION_1080P, HealthRules.downgrade(
            RecordingConfig(resolutionId = RecordingConfig.RESOLUTION_2160P)).resolutionId)
        assertEquals(10, HealthRules.downgrade(RecordingConfig(videoMbps = 14)).videoMbps)
        assertEquals(6, HealthRules.downgrade(RecordingConfig(videoMbps = 6)).videoMbps)
    }
}
