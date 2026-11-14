package com.naptimer

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34, 35])
class NapScreenWakeTest {
    @Before
    fun reset() = NapScreenWake.release()

    @Suppress("DEPRECATION")
    @Test
    fun screenLockReallyTurnsTheScreenOnInsteadOfOnlyKeepingTheCpu() {
        // ACQUIRE_CAUSES_WAKEUP 与 PARTIAL_WAKE_LOCK 组合会被系统忽略：
        // 这正是「响铃了但必须按电源键才能看到」的根因，别再改回去。
        assertTrue(NapScreenWake.SCREEN_WAKE_FLAGS and PowerManager.ACQUIRE_CAUSES_WAKEUP != 0)
        assertTrue(NapScreenWake.SCREEN_WAKE_FLAGS and PowerManager.FULL_WAKE_LOCK != 0)
        assertTrue(NapScreenWake.SCREEN_WAKE_FLAGS and PowerManager.ON_AFTER_RELEASE != 0)
        assertFalse(
            "PARTIAL_WAKE_LOCK 会让 ACQUIRE_CAUSES_WAKEUP 失效",
            NapScreenWake.SCREEN_WAKE_FLAGS and PowerManager.PARTIAL_WAKE_LOCK == PowerManager.PARTIAL_WAKE_LOCK,
        )
    }

    @Test
    fun acquireIsIdempotentAndReleaseDropsTheLocks() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(NapScreenWake.isHeld())
        NapScreenWake.acquire(context)
        assertTrue(NapScreenWake.isHeld())
        // Activity 被重建时会再次 acquire，不能叠加引用计数导致永远释放不掉。
        NapScreenWake.acquire(context)
        assertTrue(NapScreenWake.isHeld())
        NapScreenWake.release()
        assertFalse(NapScreenWake.isHeld())
    }
}
