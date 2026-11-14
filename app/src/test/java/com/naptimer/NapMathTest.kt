package com.naptimer

import org.junit.Assert.assertEquals
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

}
