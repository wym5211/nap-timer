package com.naptimer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NapStoreTest {
    @Test
    fun scheduledPendingSessionCanRingAfterInterruptedSetup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("pending-session", 20, false, "calm", 1_000L, 1_000L)

        assertFalse(store.claimAlarm("old-session"))
        assertTrue(store.claimAlarm("pending-session"))
        assertEquals(NapStatus.RINGING, store.current().status)
        assertFalse(store.claimAlarm("pending-session"))
    }

    @Test
    fun statusStoredByThePreviousVersionStillLoads() {
        // 1.1 之前写入的是小写名字，升级安装后不能把进行中的午睡读成空闲。
        assertEquals(NapStatus.ACTIVE, NapStatus.fromStored("active"))
        assertEquals(NapStatus.PENDING, NapStatus.fromStored("pending"))
        assertEquals(NapStatus.RINGING, NapStatus.fromStored("ringing"))
        assertEquals(NapStatus.IDLE, NapStatus.fromStored("idle"))
        assertEquals(NapStatus.IDLE, NapStatus.fromStored(null))
        assertEquals(NapStatus.IDLE, NapStatus.fromStored("something-else"))
    }
}
