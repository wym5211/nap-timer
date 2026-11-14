package com.naptimer

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
        // 前台服务通知只负责维持服务，不能挂 FSI；全屏界面靠独立 notify 的响铃通知。
        assertNull(shadowOf(service).lastForegroundNotification.fullScreenIntent)
        val manager = context.getSystemService(NotificationManager::class.java)
        val alert = manager.activeNotifications.first {
            it.id == NapAudioService.ALARM_ALERT_ID
        }.notification
        assertNotNull(alert.fullScreenIntent)
        assertEquals(Notification.VISIBILITY_PUBLIC, alert.visibility)
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
        NapAudioService.notifyFailure(context, "fallback-session", ringing = true)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.first {
            it.id == NapAudioService.ALARM_ALERT_ID
        }.notification
        assertEquals("nap_alarm_fallback", notification.channelId)

        // 渠道本身必须能出声：没声音、低优先级的兜底通知等于整次午睡静默失败。
        val channel = manager.getNotificationChannel("nap_alarm_fallback")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)

        // 兜底通知不能只是纯文字：锁屏上必须仍有关闭入口和全屏尝试。
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        val actions = requireNotNull(notification.actions)
        assertTrue(actions.isNotEmpty())
        assertFalse("通知动作必须有真实图标", actions[0].icon == 0)
        assertNotNull(notification.fullScreenIntent)
    }

    @Test
    fun ringingNotificationIsVisibleOnLockScreenWithAWorkingStopAction() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NapStore(context)
        store.prepare("ringing-notice", 20, true, "calm", 1_000, 1_000)
        assertTrue(store.activate("ringing-notice"))
        assertTrue(store.claimAlarm("ringing-notice"))
        val controller = Robolectric.buildService(NapAudioService::class.java).create()
        val service = controller.get()
        service.onStartCommand(
            Intent(context, NapAudioService::class.java)
                .putExtra("id", "ringing-notice")
                .putExtra("ringing", true),
            0,
            1,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val notice = manager.activeNotifications.first {
            it.id == NapAudioService.ALARM_ALERT_ID
        }.notification
        // FSI 只能挂在独立投递的响铃通知上，前台服务通知保持低调。
        assertNull(shadowOf(service).lastForegroundNotification.fullScreenIntent)

        assertEquals(Notification.VISIBILITY_PUBLIC, notice.visibility)
        val actions = requireNotNull(notice.actions)
        assertTrue(actions.isNotEmpty())
        assertFalse("通知动作必须有真实图标", actions[0].icon == 0)

        val fullScreen = requireNotNull(notice.fullScreenIntent)
        assertEquals(
            NapRingingActivity::class.java.name,
            shadowOf(fullScreen).savedIntent.component?.className,
        )
        // contentIntent 与 fullScreenIntent 必须是两个独立的 PendingIntent。
        assertNotEquals(
            shadowOf(notice.contentIntent).savedIntent.data,
            shadowOf(fullScreen).savedIntent.data,
        )
        controller.destroy()
        Unit
    }

    @Test
    fun cancelAlarmNotificationsClearsLeftoverRingingNotices() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        NapAudioService.notifyFailure(context, "leftover", ringing = true)
        assertTrue(manager.activeNotifications.isNotEmpty())

        NapAudioService.cancelAlarmNotifications(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun fallbackNoticeWakesTheScreenEvenWithoutAnyActivity() {
        val context = RuntimeEnvironment.getApplication()
        NapScreenWake.release()
        // 服务被拒绝启动时不会有 Activity 来帮忙亮屏，兜底通知必须自己唤醒设备。
        NapAudioService.notifyFailure(context, "fallback-wake", ringing = true)
        assertTrue("兜底路径必须点亮屏幕", NapScreenWake.isHeld())
        NapScreenWake.release()
        assertFalse(NapScreenWake.isHeld())
    }
}
