package com.naptimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NapMathTest {
    @Test
    fun durationIsClampedToOneThroughFortyFiveMinutes() {
        assertEquals(1, NapMath.clampMinutes(-3))
        assertEquals(20, NapMath.clampMinutes(20))
        assertEquals(45, NapMath.clampMinutes(46))
    }

    @Test
    fun remainingTimeNeverBecomesNegative() {
        assertEquals(15_000L, NapMath.remainingMillis(50_000L, 35_000L))
        assertEquals(0L, NapMath.remainingMillis(50_000L, 51_000L))
    }

    @Test
    fun onlyActiveMatchingSessionCanRing() {
        assertTrue(NapMath.shouldRing("active", "active", true))
        assertFalse(NapMath.shouldRing("active", "old", true))
        assertFalse(NapMath.shouldRing("active", "active", false))
        assertFalse(NapMath.shouldRing("", "", true))
    }

    @Test
    fun staleStopActionCannotStopTheCurrentSession() {
        assertTrue(NapMath.isCurrentSession("current", "current"))
        assertFalse(NapMath.isCurrentSession("current", "old"))
        assertFalse(NapMath.isCurrentSession("", ""))
    }

    @Test
    fun restartRecoveryNeverSchedulesAnExpiredSession() {
        assertEquals(120_000L, NapMath.restartRemainingMillis(220_000L, 100_000L))
        assertEquals(0L, NapMath.restartRemainingMillis(90_000L, 100_000L))
    }
}
