package com.naptimer

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NapAudioServiceTest {
    @Test
    fun ringingUsesANewFullScreenNotificationAfterSleepAudio() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("transition", 20, true, "calm", 1_000, 1_000)
        assertTrue(store.activate("transition"))
        val controller = Robolectric.buildService(NapAudioService::class.java).create()
        val service = controller.get()
        service.onStartCommand(
            Intent(context, NapAudioService::class.java)
                .putExtra("id", "transition")
                .putExtra("ringing", false),
            0,
            1,
        )
        val sleepNotificationId = shadowOf(service).lastForegroundNotificationId

        assertTrue(store.claimAlarm("transition"))
        service.onStartCommand(
            Intent(context, NapAudioService::class.java)
                .putExtra("id", "transition")
                .putExtra("ringing", true),
            0,
            2,
        )
        assertTrue(sleepNotificationId != shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(service).lastForegroundNotification.fullScreenIntent)
        controller.destroy()
        Unit
    }

    @Test
    fun sleepAudioUsesFullPlayerVolumeSoSystemVolumeControlsIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("sleeping", 20, true, "calm", 1_000, 1_000)
        assertTrue(store.activate("sleeping"))
        val controller = Robolectric.buildService(NapAudioService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(context, NapAudioService::class.java)
            .putExtra("id", "sleeping")
            .putExtra("ringing", false), 0, 1)
        val field = NapAudioService::class.java.getDeclaredField("player")
        field.isAccessible = true
        val player = field.get(service) as ExoPlayer

        withTimeout(5_000) {
            while (player.mediaItemCount == 0) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1f, player.volume, 0f)
        controller.destroy()
        Unit
    }

    @Test
    fun alarmFailureFallsBackToANotificationWithTheSystemAlarmSound() {
        val context = RuntimeEnvironment.getApplication()
        NapAudioService.notifyFailure(context, ringing = true)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single()
        assertEquals("nap_alarm_fallback", notification.notification.channelId)

        // 渠道本身必须能出声：没声音、低优先级的兜底通知等于整次午睡静默失败。
        val channel = manager.getNotificationChannel("nap_alarm_fallback")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
    }

    @Test
    fun rejectedAlarmAudioFocusDoesNotStopTheRingingService() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("ringing", 20, false, "calm", 1_000, 1_000)
        assertTrue(store.activate("ringing"))
        assertTrue(store.claimAlarm("ringing"))
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val controller = Robolectric.buildService(NapAudioService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(context, NapAudioService::class.java)
            .putExtra("id", "ringing")
            .putExtra("ringing", true), 0, 1)

        withTimeout(5_000) {
            while (audio.lastAudioFocusRequest == null) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notice = withTimeout(5_000) {
            while (notifications.activeNotifications.single().notification.extras.getString("android.text") != "休息好了，慢慢醒来") {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            notifications.activeNotifications.single()
        }
        assertEquals("休息好了，慢慢醒来", notice.notification.extras.getString("android.text"))
        controller.destroy()
        Unit
    }

    @Test
    fun ringingStopsWaitingForAudioFocusAfterTheRetryBudget() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("blocked", 20, false, "calm", 1_000, 1_000)
        assertTrue(store.activate("blocked"))
        assertTrue(store.claimAlarm("blocked"))
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        // 另一个应用一直占着音频：闹铃不能永远停在“等待其他应用释放音频”。
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val controller = Robolectric.buildService(NapAudioService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(context, NapAudioService::class.java)
            .putExtra("id", "blocked")
            .putExtra("ringing", true), 0, 1)

        val notifications = context.getSystemService(NotificationManager::class.java)
        withTimeout(5_000) {
            while (audio.lastAudioFocusRequest == null) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            notifications.activeNotifications.none {
                it.notification.extras.getString("android.text") == "休息好了，慢慢醒来"
            }
        ) {
            shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
            delay(20)
        }

        assertTrue(
            "ringing notice never came back: " + notifications.activeNotifications.map {
                it.notification.extras.getString("android.text")
            },
            notifications.activeNotifications.any {
                it.notification.extras.getString("android.text") == "休息好了，慢慢醒来"
            },
        )
        assertFalse(shadowOf(service).isStoppedBySelf)
        controller.destroy()
        Unit
    }
}
