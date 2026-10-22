package com.tue.nightcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {

    @Test
    fun gbPerHour_matchesPlanExample() {
        // 14 + 0,128 Mbps ≈ 6,36 GB/giờ (PLAN mục 14)
        assertEquals(6.36, Calc.gbPerHour(14.128), 0.01)
    }

    @Test
    fun segmentBytes_isCappedBelowFourGigabytes() {
        // 45 Mbps trong 2 giờ vượt 3,5 GB → phải kẹp
        val capped = Calc.segmentBytes(45_000_000L, 0L, 2 * 3600L)
        assertEquals(RecordingConfig.MAX_SEGMENT_BYTES_CAP, capped)
    }

    @Test
    fun segmentBytes_normalCase() {
        // 14 Mbps video + 128 kbps audio trong 600 s
        val expected = (14_000_000L + 128_000L) / 8 * 600
        assertEquals(expected, Calc.segmentBytes(14_000_000L, 128_000L, 600L))
    }

    @Test
    fun hoursRemaining_subtractsReserve() {
        // 10 GB trống, dự phòng 2 GB, 8 GB dùng được, 8 Mbps = 1 MB/s → 8000 s
        val hours = Calc.hoursRemaining(
            freeBytes = 10_000_000_000L,
            reserveBytes = 2_000_000_000L,
            totalBps = 8_000_000L,
        )
        assertEquals(8000.0 / 3600.0, hours!!, 1e-6)
    }

    @Test
    fun hoursRemaining_belowReserveIsZero() {
        val hours = Calc.hoursRemaining(1_000L, 5_000L, 8_000_000L)
        assertEquals(0.0, hours!!, 0.0)
    }

    @Test
    fun hoursRemaining_unknownBitrateIsNull() {
        assertNull(Calc.hoursRemaining(10L, 0L, 0L))
    }

    @Test
    fun orientationHint_backCameraPortraitIsNinety() {
        // Pixel: sensor 90, cầm dọc (xoay 0) → 90
        assertEquals(90, Calc.orientationHint(90, 0))
    }

    @Test
    fun orientationHint_allDisplayRotations() {
        assertEquals(90, Calc.orientationHint(90, 0))
        assertEquals(0, Calc.orientationHint(90, 90))
        assertEquals(270, Calc.orientationHint(90, 180))
        assertEquals(180, Calc.orientationHint(90, 270))
    }

    @Test
    fun roundToRightAngle_handlesWrapAround() {
        assertEquals(0, Calc.roundToRightAngle(359))
        assertEquals(0, Calc.roundToRightAngle(-10))
        assertEquals(90, Calc.roundToRightAngle(44 + 1))
        assertEquals(180, Calc.roundToRightAngle(180))
        assertEquals(270, Calc.roundToRightAngle(-90))
    }

    @Test
    fun touchToSensor_portraitSensorNinety() {
        val (sx, sy) = Calc.touchToSensor(0.2f, 0.7f, 90)
        assertEquals(0.7f, sx, 1e-6f)
        assertEquals(0.8f, sy, 1e-6f)
    }

    @Test
    fun touchToSensor_identityForZero() {
        val (sx, sy) = Calc.touchToSensor(0.25f, 0.75f, 0)
        assertEquals(0.25f, sx, 1e-6f)
        assertEquals(0.75f, sy, 1e-6f)
    }

    @Test
    fun avcFallback_isAboutFortyPercentHigher() {
        assertEquals(20, Calc.avcFallbackMbps(14))
        assertTrue(Calc.avcFallbackMbps(10) > 10)
    }

    @Test
    fun orientationFor_autoFollowsDisplay() {
        assertEquals(90, Calc.orientationFor("auto", 90, 0))
        assertEquals(0, Calc.orientationFor("auto", 90, 90))
    }

    @Test
    fun orientationFor_landscapeAndPortraitAreForced() {
        assertEquals(0, Calc.orientationFor("landscape", 90, 0))      // đang dọc → ép ngang natural
        assertEquals(180, Calc.orientationFor("landscape", 90, 270))  // đang ngang → giữ chiều
        assertEquals(90, Calc.orientationFor("portrait", 90, 90))     // đang ngang → ép dọc
    }

    @Test
    fun formatDuration_hhmmss() {
        assertEquals("00:00:00", Calc.formatDuration(-5))
        assertEquals("01:02:03", Calc.formatDuration(3_723_000))
    }
}
