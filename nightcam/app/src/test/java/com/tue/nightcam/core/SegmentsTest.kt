package com.tue.nightcam.core

import com.tue.nightcam.core.Segments.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentsTest {

    @Test
    fun fileName_padsIndexToThreeDigits() {
        assertEquals("NC_20261022_031500_part007.mp4", Segments.fileName("20261022_031500", 7))
        assertEquals("NC_x_part120.mp4", Segments.fileName("x", 120))
    }

    @Test
    fun classify_emptyFileIsDeleted() {
        assertEquals(RecoveryAction.DELETE, Segments.classifyPending(0L, 5_000L))
    }

    @Test
    fun classify_playableFileIsPublished() {
        assertEquals(RecoveryAction.PUBLISH, Segments.classifyPending(1_000L, 60_000L))
    }

    @Test
    fun classify_unreadableDurationIsMarkedBroken() {
        assertEquals(RecoveryAction.MARK_BROKEN, Segments.classifyPending(1_000L, null))
        assertEquals(RecoveryAction.MARK_BROKEN, Segments.classifyPending(1_000L, 0L))
    }

    @Test
    fun brokenName_appendsSuffix() {
        assertEquals("NC_a_part001.mp4_BROKEN", Segments.brokenName("NC_a_part001.mp4"))
    }
}
