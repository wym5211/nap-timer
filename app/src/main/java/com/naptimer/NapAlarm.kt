package com.naptimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

object NapAlarm {
    private val sessionMutex = Mutex()

    /** 开机广播的来源：升级安装不会重置 elapsedRealtime，和重启用的恢复时基不同。 */
    enum class BootReason { REBOOT, PACKAGE_UPDATED }

    /** 失败时返回可直接展示的提示；[openExactAlarmSettings] 为真时界面要引导用户去开权限。 */
    data class Problem(val message: String, val openExactAlarmSettings: Boolean = false)

    private const val NO_EXACT_ALARM_START = "请允许设置精确闹钟，返回后再次点击开始"
    private const val NO_EXACT_ALARM_RECOVER = "精确闹钟权限已关闭，请重新设置午睡"

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, NapAlarmReceiver::class.java)
            .setData(Uri.parse("naptimer://alarm/$id"))
            .putExtra("id", id)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canSchedule(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    }

    private fun schedule(context: Context, id: String, endElapsed: Long) {
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            endElapsed,
            pendingIntent(context, id),
        )
    }

    private fun cancel(context: Context, id: String) {
        if (id.isNotEmpty()) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
            pendingIntent(context, id).cancel()
        }
    }

    /** 返回 null 表示已成功开始，否则返回可直接展示的提示。 */
    suspend fun start(context: Context, minutes: Int, sound: Boolean, soundId: String): Problem? =
        withContext(NonCancellable) {
            sessionMutex.withLock { startLocked(context, minutes, sound, soundId) }
        }

    private suspend fun startLocked(
        context: Context,
        minutes: Int,
        sound: Boolean,
        soundId: String,
    ): Problem? {
        if (!canSchedule(context)) return Problem(NO_EXACT_ALARM_START, openExactAlarmSettings = true)
        val store = NapStore(context)
        if (store.current().status != NapStatus.IDLE) return Problem("已有进行中的午睡")
        val duration = NapMath.clampMinutes(minutes) * 60_000L
        val id = UUID.randomUUID().toString()
        val endElapsed = SystemClock.elapsedRealtime() + duration
        return try {
            // 先落 PENDING 再排系统闹钟：闹钟独立于本进程存活，进程若在两行之间被杀，
            // recoverSession 还能凭 PENDING 补排；反过来只会留下一个没人认领的闹钟。
            store.prepare(id, minutes, sound, soundId, endElapsed, System.currentTimeMillis() + duration)
            schedule(context, id, endElapsed)
            if (!store.activate(id)) {
                cancel(context, id)
                store.finish(id)
                Problem("午睡状态发生变化，请重试")
            } else {
                resumeSession(
                    context, id, sound,
                    restoreAudio = true, silentText = "静音午睡中", prefix = "闹钟已设置，但",
                )
            }
        } catch (_: SecurityException) {
            cancel(context, id)
            store.finish(id)
            Problem(NO_EXACT_ALARM_START, openExactAlarmSettings = true)
        } catch (_: RuntimeException) {
            cancel(context, id)
            store.finish(id)
            Problem("设置闹钟失败，请重试")
        } catch (_: IOException) {
            // DataStore 写盘失败抛的是 IOException，不属于 RuntimeException；
            // 此时闹钟可能已经排上，必须同样复位，不能让界面停留在假进行中。
            cancel(context, id)
            store.finish(id)
            Problem("设置闹钟失败，请重试")
        }
    }

    suspend fun stop(context: Context, id: String) = withContext(NonCancellable) {
        sessionMutex.withLock { stopLocked(context, id) }
    }

    private suspend fun stopLocked(context: Context, id: String) {
        if (NapStore(context).finish(id)) cleanupSession(context, id)
    }

    /** 会话结束后的统一收尾：用户主动停止与恢复失败都走这里，避免漏停音乐或漏清通知。 */
    private fun cleanupSession(context: Context, id: String) {
        NapScreenWake.release()
        cancel(context, id)
        NapAudioService.cancelSessionNotification(context, id)
        NapAudioService.cancelAlarmNotifications(context)
        context.stopService(Intent(context, NapAudioService::class.java))
    }

    suspend fun ring(context: Context, id: String) = withContext(NonCancellable) {
        sessionMutex.withLock {
            if (NapStore(context).claimAlarm(id)) {
                // 必须趁广播的临时唤醒窗口还在就点亮屏幕：full-screen intent 有可能被
                // 系统降级成 heads-up，那时没有任何 Activity 会来补这一步。
                NapScreenWake.acquire(context)
                NapAudioService.cancelSessionNotification(context, id)
                // 清掉上一次响铃残留，让本次 full-screen intent 是新投递而不是更新。
                NapAudioService.cancelAlarmNotifications(context)
                try {
                    NapAudioService.start(context, id, ringing = true)
                } catch (_: RuntimeException) {
                    // startForegroundService 本身失败时，至少留一条带关闭入口的告警通知。
                    NapAudioService.notifyFailure(context, id)
                }
            }
        }
    }

    /**
     * 重新打开应用时把进行中的午睡接回来。
     *
     * 覆盖 pending（开始流程被打断）和 active（进程被杀、升级安装等导致闹钟丢失）两种情况，
     * 两种状态都重新排一次闹钟，所以 active 会话不会再出现"界面在倒计时、闹钟已经没了"。
     */
    suspend fun recoverSession(context: Context): Problem? = withContext(NonCancellable) {
        sessionMutex.withLock {
            val store = NapStore(context)
            val state = store.current()
            if (state.status != NapStatus.PENDING && state.status != NapStatus.ACTIVE) return@withLock null
            val target = pendingTarget(state, SystemClock.elapsedRealtime())
            if (target == null) {
                // 到点后才恢复：按设计标记结束并提示，不补响。
                store.finish(state.id)
                cancel(context, state.id)
                return@withLock Problem("上次的午睡已到点，本次未能响铃")
            }
            restoreAlarm(
                context = context,
                store = store,
                state = state,
                targetElapsed = target,
                failedText = "无法恢复闹钟，请重新打开应用",
                restoreAudio = true,
                silentText = "静音午睡中",
                prefix = "闹钟已恢复，但",
            )
        }
    }

    /**
     * 恢复 pending 会话时该用的到点时刻。
     *
     * 同一次开机内 endElapsed 仍然有效；剩余时间不可能超过设定时长，一旦超过就说明它来自上一次开机
     * （单调时钟已重置），此时改用墙上时钟估算剩余时长并换算成当前的单调时刻。
     */
    private fun pendingTarget(state: NapState, nowElapsed: Long): Long? {
        val duration = state.minutes * 60_000L
        val remainingByElapsed = state.endElapsed - nowElapsed
        return when {
            remainingByElapsed in 1L..duration -> state.endElapsed
            remainingByElapsed > duration -> {
                val remaining = NapMath.remainingMillis(state.endWall, System.currentTimeMillis())
                    .coerceAtMost(duration)
                if (remaining > 0) nowElapsed + remaining else null
            }
            else -> null
        }
    }

    /**
     * 恢复进行中会话的唯一编排：权限检查 -> 排钟 -> 写回 -> 恢复音频/通知。
     *
     * 重开应用、开机、升级安装三个入口都走这里，只提供各自的到点时刻与音频模式。
     * 任何一步失败都统一结束会话、取消闹钟并留下提示，避免界面继续倒数一个没排上的闹钟。
     */
    private suspend fun restoreAlarm(
        context: Context,
        store: NapStore,
        state: NapState,
        targetElapsed: Long,
        failedText: String,
        restoreAudio: Boolean,
        silentText: String,
        prefix: String,
    ): Problem? {
        if (!canSchedule(context)) {
            finishAndNotify(context, store, state.id, NO_EXACT_ALARM_RECOVER)
            return Problem(NO_EXACT_ALARM_RECOVER, openExactAlarmSettings = true)
        }
        return try {
            schedule(context, state.id, targetElapsed)
            // endWall 必须和 endElapsed 一起写回：二次恢复还会读它，留着旧值会再次污染时基。
            val endWall = System.currentTimeMillis() +
                NapMath.remainingMillis(targetElapsed, SystemClock.elapsedRealtime())
            if (!store.recover(state.id, targetElapsed, endWall)) {
                finishAndNotify(context, store, state.id, failedText)
                Problem("午睡状态发生变化，请重试")
            } else {
                // 闹钟已经排上，通知/音频失败按设计只提示、不回滚会话。
                resumeSession(context, state.id, state.sound, restoreAudio, silentText, prefix)
            }
        } catch (_: SecurityException) {
            finishAndNotify(context, store, state.id, failedText)
            Problem(NO_EXACT_ALARM_RECOVER, openExactAlarmSettings = true)
        } catch (_: RuntimeException) {
            finishAndNotify(context, store, state.id, failedText)
            Problem(failedText)
        } catch (_: IOException) {
            finishAndNotify(context, store, state.id, failedText)
            Problem(failedText)
        }
    }

    /** 让助眠音乐或静音提示跟上会话。返回 null 表示成功，否则返回可直接展示的提示。 */
    private suspend fun resumeSession(
        context: Context,
        id: String,
        sound: Boolean,
        restoreAudio: Boolean,
        silentText: String,
        prefix: String,
    ): Problem? =
        if (sound && restoreAudio) {
            try {
                NapAudioService.start(context, id, ringing = false)
                null
            } catch (_: RuntimeException) {
                Problem("${prefix}助眠音乐无法启动")
            }
        } else {
            try {
                NapAudioService.notifySession(context, id, silentText)
                null
            } catch (_: RuntimeException) {
                Problem("${prefix}无法显示午睡通知")
            }
        }

    private suspend fun finishAndNotify(context: Context, store: NapStore, id: String, text: String) {
        if (store.finish(id)) {
            cleanupSession(context, id)
            try {
                NapAudioService.notifyMissed(context, text)
            } catch (_: RuntimeException) {
                // 提示失败不影响会话已结束的状态。
            }
        }
    }

    /**
     * 开机/升级恢复到点时刻。
     *
     * 重启会重置 elapsedRealtime，只能按墙上时钟估算剩余；升级安装不会重置单调时钟，
     * endElapsed 此刻仍然精确有效，用 endWall 覆写反而会让用户提前或延后被叫醒。
     */
    private fun bootTarget(state: NapState, reason: BootReason): Long? {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (reason == BootReason.PACKAGE_UPDATED) {
            // endElapsed 明显超出时长说明它来自上一次开机，此时才退回墙上时钟。
            return pendingTarget(state, nowElapsed)
        }
        val remaining = NapMath.remainingMillis(state.endWall, System.currentTimeMillis())
            .coerceAtMost(state.minutes * 60_000L)
        return if (remaining > 0) nowElapsed + remaining else null
    }

    suspend fun recoverAfterBoot(context: Context, reason: BootReason): Unit = withContext(NonCancellable) {
        sessionMutex.withLock {
            val store = NapStore(context)
            val state = store.current()
            when (state.status) {
                NapStatus.ACTIVE, NapStatus.PENDING -> {
                    val target = bootTarget(state, reason)
                    if (target == null) {
                        finishAndNotify(context, store, state.id, "设备重启期间已到点，本次未能响铃")
                    } else {
                        restoreAlarm(
                            context = context,
                            store = store,
                            state = state,
                            targetElapsed = target,
                            failedText = "重启后未能恢复闹钟，请重新设置",
                            restoreAudio = false,
                            silentText = if (state.sound) "设备重启后助眠音乐已暂停，闹钟仍在运行" else "静音午睡中",
                            prefix = "闹钟已恢复，但",
                        )
                    }
                }
                // 响铃中的会话没有可恢复的上下文，按正常停止流程完整清理，
                // 否则通知、前台服务与唤醒锁会因为会话 id 已被清空而关不掉。
                NapStatus.RINGING -> stopLocked(context, state.id)
                NapStatus.IDLE -> Unit
            }
            Unit
        }
    }
}

class NapAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NapAlarm.ring(context, id)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 开机和升级安装都会清掉已排的闹钟，但两者的恢复时基不同，见 [NapAlarm.BootReason]。
 *
 * exported=true 是接收隐式开机广播所必需的；而显式 Intent 按 component 投递会绕过
 * intent-filter 的 action 匹配，所以这里必须自己再校验一次 action。只放行系统的
 * protected-broadcast，第三方应用无法伪造这两个 action 来驱动会话状态机。
 */
class NapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> NapAlarm.BootReason.REBOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> NapAlarm.BootReason.PACKAGE_UPDATED
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NapAlarm.recoverAfterBoot(context, reason)
            } finally {
                pending.finish()
            }
        }
    }
}
