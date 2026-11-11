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
        assertEquals("ringing", store.current().status)
        assertFalse(store.claimAlarm("pending-session"))
    }
}
