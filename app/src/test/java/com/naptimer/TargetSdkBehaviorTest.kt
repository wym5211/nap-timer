package com.naptimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * 只跑目标 SDK：精确闹钟权限被撤、全屏权限降级、后台前台服务被拒。
 * 这些分支在 sdk=28 下根本不会执行，正是「测试全绿但锁屏不响」的盲区。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class TargetSdkBehaviorTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Before
    fun grantExactAlarms() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    @After
    fun restoreExactAlarms() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    @Test
    fun exactAlarmPermissionRevokedPreventsStartAndKeepsIdle() = runBlocking {
        val context = context()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(NapAlarm.canSchedule(context))

        val problem = NapAlarm.start(context, 20, false, "calm")

        assertEquals("请允许设置精确闹钟，返回后再次点击开始", problem?.message)
        assertTrue("权限被拒必须给出设置入口", problem?.openExactAlarmSettings == true)
        assertEquals(NapStatus.IDLE, NapStore(context).current().status)
        assertEquals(0, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test
    fun recoveryWithoutExactAlarmPermissionEndsSessionAndPostsNotice() = runBlocking {
        val context = context()
        val store = NapStore(context)
        val endElapsed = SystemClock.elapsedRealtime() + 120_000
        store.prepare("no-permission", 20, false, "calm", endElapsed, System.currentTimeMillis() + 120_000)
        assertTrue(store.activate("no-permission"))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val problem = NapAlarm.recoverSession(context)

        assertTrue(problem?.openExactAlarmSettings == true)
        assertEquals("恢复失败必须回滚会话，不能留一个没排上闹钟的假进行中", NapStatus.IDLE, store.current().status)
        assertEquals(0, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        val notice = context.getSystemService(NotificationManager::class.java).activeNotifications.singleOrNull()
        assertNotNull("失败要留下持久提示，不能只弹一次 Toast", notice)
        assertEquals("nap_info", notice!!.notification.channelId)
    }

    @Test
    fun startForegroundFailureKeepsTheFallbackWakeLockOnTargetSdk() {
        val context = context()
        NapScreenWake.release()
        val controller = Robolectric.buildService(FailingForegroundService::class.java).create()
        controller.get().onStartCommand(
            Intent(context, NapAudioService::class.java)
                .putExtra("id", "sdk-fgs-fail")
                .putExtra("ringing", true),
            0,
            1,
        )
        controller.destroy()
        assertTrue("目标 SDK 上兜底亮屏锁不能被服务销毁释放", NapScreenWake.isHeld())
        NapScreenWake.release()
    }

    @Test
    fun ringingNoticeHonorsTheFullScreenPermissionDecision() = runBlocking {
        val context = context()
        val store = NapStore(context)
        store.prepare("sdk-notice", 20, true, "calm", 1_000, 1_000)
        assertTrue(store.activate("sdk-notice"))
        assertTrue(store.claimAlarm("sdk-notice"))
        val controller = Robolectric.buildService(NapAudioService::class.java).create()
        controller.get().onStartCommand(
            Intent(context, NapAudioService::class.java)
                .putExtra("id", "sdk-notice")
                .putExtra("ringing", true),
            0,
            1,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val notice = manager.activeNotifications.first { it.id == NapAudioService.ALARM_ALERT_ID }.notification
        assertEquals(Notification.VISIBILITY_PUBLIC, notice.visibility)
        assertTrue("锁屏上必须始终有关闭入口", requireNotNull(notice.actions).isNotEmpty())
        if (NapAudioService.canUseFullScreenIntent(context)) {
            assertNotNull(notice.fullScreenIntent)
        } else {
            assertNull("API 34+ 全屏权限被拒时必须降级成 heads-up", notice.fullScreenIntent)
        }
        controller.destroy()
        Unit
    }
}
