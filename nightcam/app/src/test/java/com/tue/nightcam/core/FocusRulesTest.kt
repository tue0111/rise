package com.tue.nightcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FocusRulesTest {

    private val tap = FocusMode.Tap(Box(0, 0, 10, 10), deadlineMs = 1_000L)

    @Test
    fun afterFrame_waitsWhileNotLockedAndBeforeDeadline() {
        assertSame(tap, FocusRules.afterFrame(tap, afLocked = false, distance = 2f, nowMs = 500L))
    }

    @Test
    fun afterFrame_locksAtDistanceWhenAfSettles() {
        assertEquals(FocusMode.Locked(2.5f), FocusRules.afterFrame(tap, afLocked = true, distance = 2.5f, nowMs = 10L))
    }

    @Test
    fun afterFrame_timeoutWithDistanceLocks() {
        assertEquals(FocusMode.Locked(1f), FocusRules.afterFrame(tap, afLocked = false, distance = 1f, nowMs = 1_000L))
    }

    @Test
    fun afterFrame_timeoutWithoutDistanceFallsBackToContinuous() {
        assertEquals(FocusMode.Continuous, FocusRules.afterFrame(tap, afLocked = false, distance = null, nowMs = 2_000L))
    }

    @Test
    fun tapBox_centerOfFourK() {
        // 4000x3000: cạnh ngắn 3000 → cạnh hộp 300
        assertEquals(Box(1850, 1350, 300, 300), FocusRules.tapBox(0.5f, 0.5f, 4000, 3000))
    }

    @Test
    fun tapBox_isClampedInsideActiveArray() {
        assertEquals(Box(0, 0, 300, 300), FocusRules.tapBox(0f, 0f, 4000, 3000))
        assertEquals(Box(3700, 2700, 300, 300), FocusRules.tapBox(1f, 1f, 4000, 3000))
    }
}
