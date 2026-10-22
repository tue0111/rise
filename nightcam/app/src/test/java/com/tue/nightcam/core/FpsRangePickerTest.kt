package com.tue.nightcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsRangePickerTest {

    private val pixelRanges = listOf(7..30, 15..30, 24..30, 30..30, 15..15, 60..60)

    @Test
    fun night_picksLowestFloorAtOrAboveMinimum() {
        assertEquals(15..30, FpsRangePicker.pick(pixelRanges, RecordingConfig.PROFILE_NIGHT, 15))
        assertEquals(24..30, FpsRangePicker.pick(pixelRanges, RecordingConfig.PROFILE_NIGHT, 24))
    }

    @Test
    fun night_fallsBackToCapAt30WhenFloorTooHigh() {
        val ranges = listOf(7..30, 30..30)
        assertEquals(30..30, FpsRangePicker.pick(ranges, RecordingConfig.PROFILE_NIGHT, 28))
    }

    @Test
    fun standard_prefersFixed30() {
        assertEquals(30..30, FpsRangePicker.pick(pixelRanges, RecordingConfig.PROFILE_STANDARD, 15))
    }

    @Test
    fun standard_withoutFixed30UsesHighestFloorAt30() {
        val ranges = listOf(7..30, 15..30, 24..30)
        assertEquals(24..30, FpsRangePicker.pick(ranges, RecordingConfig.PROFILE_STANDARD, 15))
    }

    @Test
    fun noCapAt30_returnsHighestCeiling() {
        assertEquals(60..60, FpsRangePicker.pick(listOf(15..15, 60..60), RecordingConfig.PROFILE_NIGHT, 15))
    }

    @Test
    fun emptyList_isNull() {
        assertNull(FpsRangePicker.pick(emptyList(), RecordingConfig.PROFILE_NIGHT, 15))
    }
}
