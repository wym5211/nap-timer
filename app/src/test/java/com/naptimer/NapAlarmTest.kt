package com.naptimer

import android.app.NotificationManager
import android.app.AlarmManager
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NapAlarmTest {
    @Test
    fun silentNapHasAStopNotificationUntilStopped() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val notifications = context.getSystemService(NotificationManager::class.java)

        assertNull(NapAlarm.start(context, 20, false, "calm"))
        val state = NapStore(context).current()
        val notice = notifications.activeNotifications.singleOrNull()
        assertNotNull(notice)
        assertEquals("正在午睡", notice!!.notification.extras.getString("android.title"))
        assertEquals(1, notice.notification.actions.size)

        NapAlarm.stop(context, state.id)
        assertEquals(0, notifications.activeNotifications.size)
    }

    @Test
    fun newSessionWaitsForPreviousServiceToStop() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val stopping = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingContext = object : ContextWrapper(context) {
            override fun stopService(service: Intent): Boolean {
                stopping.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.stopService(service)
            }
        }
        assertNull(NapAlarm.start(context, 20, false, "calm"))
        val id = NapStore(context).current().id
        val stop = async(Dispatchers.IO) { NapAlarm.stop(blockingContext, id) }
        try {
            assertTrue(stopping.await(5, TimeUnit.SECONDS))
            val start = async(Dispatchers.IO) { NapAlarm.start(context, 20, false, "calm") }
            assertFalse(withTimeoutOrNull(500) { start.await(); true } ?: false)
            release.countDown()
            stop.await()
            assertNull(start.await())
            NapAlarm.stop(context, NapStore(context).current().id)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun rebootedNapWarnsThatMusicStoppedAndKeepsTheAlarm() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        val now = SystemClock.elapsedRealtime()
        store.prepare("rebooted", 20, true, "calm", 1L, System.currentTimeMillis() + 60_000)
        assertTrue(store.activate("rebooted"))

        context.sendBroadcast(Intent(context, NapBootReceiver::class.java).setAction(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) {
            while (store.current().endElapsed < now) delay(10)
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notice = withTimeout(5_000) {
            while (notifications.activeNotifications.isEmpty()) delay(10)
            notifications.activeNotifications.singleOrNull()
        }
        assertNotNull(notice)
        assertTrue(notice!!.notification.extras.getString("android.text")!!.contains("暂停"))
        assertEquals("active", store.current().status)
        NapAlarm.stop(context, "rebooted")
    }

    @Test
    fun rebootDuringPendingSetupRecoversTheNap() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("pending-reboot", 20, false, "calm", 1L, System.currentTimeMillis() + 60_000)

        context.sendBroadcast(Intent(context, NapBootReceiver::class.java).setAction(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) {
            while (store.current().status == "pending") delay(10)
        }
        assertEquals("active", store.current().status)
        NapAlarm.stop(context, "pending-reboot")
    }

    @Test
    fun reopeningAppRecoversPendingSessionAndSchedulesAlarm() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("pending-reopen", 20, false, "calm", 60_000L, System.currentTimeMillis() + 60_000)

        assertNull(NapAlarm.recoverPending(context))
        assertEquals("active", store.current().status)
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        NapAlarm.stop(context, "pending-reopen")
    }

    @Test
    fun reopeningAppEndsAPendingSessionThatAlreadyPassed() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        val nowElapsed = SystemClock.elapsedRealtime()
        store.prepare("stale", 20, false, "calm", nowElapsed - 60_000, System.currentTimeMillis() - 60_000)

        val message = NapAlarm.recoverPending(context)
        assertNotNull(message)
        assertEquals("idle", store.current().status)
        assertEquals(0, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test
    fun reopeningAppFallsBackToWallClockWhenElapsedIsFromAPreviousBoot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        // 单调时刻远大于 20 分钟时长，说明它来自上一次开机（elapsedRealtime 已重置）。
        store.prepare("stale-boot", 20, false, "calm", SystemClock.elapsedRealtime() + 86_400_000, System.currentTimeMillis() + 300_000)

        assertNull(NapAlarm.recoverPending(context))
        val state = store.current()
        assertEquals("active", state.status)
        val remaining = state.endElapsed - SystemClock.elapsedRealtime()
        assertTrue("remaining=$remaining", remaining in 240_000..300_000)
        NapAlarm.stop(context, state.id)
    }

    @Test
    fun rebootWithAnExpiredSessionEndsItAndLeavesANotice() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("expired-reboot", 20, false, "calm", 1L, System.currentTimeMillis() - 60_000)
        assertTrue(store.activate("expired-reboot"))

        context.sendBroadcast(Intent(context, NapBootReceiver::class.java).setAction(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) {
            while (store.current().status != "idle") delay(10)
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notice = withTimeout(5_000) {
            while (notifications.activeNotifications.isEmpty()) delay(10)
            notifications.activeNotifications.single()
        }
        assertEquals("午睡已结束", notice.notification.extras.getString("android.title"))
        assertEquals("nap_info", notice.notification.channelId)
        assertTrue(notice.notification.extras.getString("android.text")!!.contains("未能响铃"))
    }
}
