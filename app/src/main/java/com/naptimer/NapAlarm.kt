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
import java.util.UUID

object NapAlarm {
    private val sessionMutex = Mutex()

    /** 失败原因。用户看到的文案由界面映射，闹钟层不再返回可直接展示的句子。 */
    enum class Reason {
        EXACT_ALARM_DENIED,
        SESSION_RUNNING,
        STATE_CHANGED,
        AUDIO_FAILED,
        NOTIFICATION_FAILED,
        SCHEDULE_FAILED,
    }

    sealed interface StartOutcome {
        data object Started : StartOutcome

        /** [alarmStillSet] 为 true 表示闹钟已经排上，只是助眠音乐或通知没起来。 */
        data class Failed(val reason: Reason, val alarmStillSet: Boolean = false) : StartOutcome
    }

    sealed interface RecoverOutcome {
        data object Nothing : RecoverOutcome
        data object Recovered : RecoverOutcome
        data object Expired : RecoverOutcome
        data class Failed(val reason: Reason) : RecoverOutcome
    }

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

    fun schedule(context: Context, id: String, endElapsed: Long) {
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            endElapsed,
            pendingIntent(context, id),
        )
    }

    fun cancel(context: Context, id: String) {
        if (id.isNotEmpty()) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
            pendingIntent(context, id).cancel()
        }
    }

    suspend fun start(context: Context, minutes: Int, sound: Boolean, soundId: String): StartOutcome =
        withContext(NonCancellable) {
            sessionMutex.withLock { startLocked(context, minutes, sound, soundId) }
        }

    private suspend fun startLocked(
        context: Context,
        minutes: Int,
        sound: Boolean,
        soundId: String,
    ): StartOutcome {
        if (!canSchedule(context)) return StartOutcome.Failed(Reason.EXACT_ALARM_DENIED)
        val store = NapStore(context)
        if (store.current().status != NapStatus.IDLE) return StartOutcome.Failed(Reason.SESSION_RUNNING)
        val duration = NapMath.clampMinutes(minutes) * 60_000L
        val id = UUID.randomUUID().toString()
        val endElapsed = SystemClock.elapsedRealtime() + duration
        return try {
            schedule(context, id, endElapsed)
            store.prepare(id, minutes, sound, soundId, endElapsed, System.currentTimeMillis() + duration)
            if (!store.activate(id)) {
                cancel(context, id)
                StartOutcome.Failed(Reason.STATE_CHANGED)
            } else {
                when (val failure = resumeSession(context, id, sound, restoreAudio = true, silentText = "静音午睡中")) {
                    null -> StartOutcome.Started
                    else -> StartOutcome.Failed(failure, alarmStillSet = true)
                }
            }
        } catch (_: SecurityException) {
            cancel(context, id)
            store.finish(id)
            StartOutcome.Failed(Reason.EXACT_ALARM_DENIED)
        } catch (_: RuntimeException) {
            cancel(context, id)
            store.finish(id)
            StartOutcome.Failed(Reason.SCHEDULE_FAILED)
        }
    }

    suspend fun stop(context: Context, id: String) = withContext(NonCancellable) {
        sessionMutex.withLock { stopLocked(context, id) }
    }

    private suspend fun stopLocked(context: Context, id: String) {
        if (NapStore(context).finish(id)) {
            cancel(context, id)
            NapAudioService.cancelSessionNotification(context, id)
            context.stopService(Intent(context, NapAudioService::class.java))
        }
    }

    suspend fun ring(context: Context, id: String) = withContext(NonCancellable) {
        sessionMutex.withLock {
            if (NapStore(context).claimAlarm(id)) {
                NapAudioService.cancelSessionNotification(context, id)
                try {
                    NapAudioService.start(context, id, ringing = true)
                } catch (_: RuntimeException) {
                    NapAudioService.notifyFailure(context)
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
    suspend fun recoverSession(context: Context): RecoverOutcome = withContext(NonCancellable) {
        sessionMutex.withLock {
            val store = NapStore(context)
            val state = store.current()
            if (state.status != NapStatus.PENDING && state.status != NapStatus.ACTIVE) {
                return@withLock RecoverOutcome.Nothing
            }
            val target = pendingTarget(state, SystemClock.elapsedRealtime())
            if (target == null) {
                // 到点后才恢复：按设计标记结束并提示，不补响。
                store.finish(state.id)
                cancel(context, state.id)
                return@withLock RecoverOutcome.Expired
            }
            if (!canSchedule(context)) {
                // 没有精确闹钟权限时这次午睡不可能响，留着界面只会继续倒数；结束它并把权限交给用户重开。
                stopLocked(context, state.id)
                return@withLock RecoverOutcome.Failed(Reason.EXACT_ALARM_DENIED)
            }
            try {
                schedule(context, state.id, target)
                // 用 recover 而不是 activate：跨开机恢复时到点时刻已按墙上时钟重算，必须一起写回。
                if (!store.recover(state.id, target)) {
                    cancel(context, state.id)
                    return@withLock RecoverOutcome.Failed(Reason.STATE_CHANGED)
                }
                when (val failure = resumeSession(context, state.id, state.sound, restoreAudio = true, silentText = "静音午睡中")) {
                    null -> RecoverOutcome.Recovered
                    else -> RecoverOutcome.Failed(failure)
                }
            } catch (_: SecurityException) {
                RecoverOutcome.Failed(Reason.EXACT_ALARM_DENIED)
            } catch (_: RuntimeException) {
                RecoverOutcome.Failed(Reason.SCHEDULE_FAILED)
            }
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

    /** 让助眠音乐或静音提示跟上会话。返回 null 表示成功，否则返回失败原因。 */
    private suspend fun resumeSession(
        context: Context,
        id: String,
        sound: Boolean,
        restoreAudio: Boolean,
        silentText: String,
    ): Reason? =
        if (sound && restoreAudio) {
            try {
                NapAudioService.start(context, id, ringing = false)
                null
            } catch (_: RuntimeException) {
                Reason.AUDIO_FAILED
            }
        } else {
            try {
                NapAudioService.notifySession(context, id, silentText)
                null
            } catch (_: RuntimeException) {
                Reason.NOTIFICATION_FAILED
            }
        }

    private suspend fun finishAndNotify(context: Context, store: NapStore, id: String, text: String) {
        if (store.finish(id)) {
            cancel(context, id)
            try {
                NapAudioService.notifyMissed(context, text)
            } catch (_: RuntimeException) {
                // 提示失败不影响会话已结束的状态。
            }
        }
    }

    suspend fun recoverAfterBoot(context: Context) = withContext(NonCancellable) {
        sessionMutex.withLock {
            val store = NapStore(context)
            val state = store.current()
            when (state.status) {
                NapStatus.ACTIVE, NapStatus.PENDING -> {
                    val remaining = NapMath.remainingMillis(state.endWall, System.currentTimeMillis())
                        .coerceAtMost(state.minutes * 60_000L)
                    when {
                        remaining <= 0 ->
                            finishAndNotify(context, store, state.id, "设备重启期间已到点，本次未能响铃")
                        !canSchedule(context) ->
                            finishAndNotify(context, store, state.id, "重启后未能恢复闹钟，请重新设置并允许精确闹钟")
                        else -> {
                            val endElapsed = SystemClock.elapsedRealtime() + remaining
                            try {
                                schedule(context, state.id, endElapsed)
                                if (store.recover(state.id, endElapsed)) {
                                    resumeSession(
                                        context,
                                        state.id,
                                        state.sound,
                                        restoreAudio = false,
                                        silentText = if (state.sound) "设备重启后助眠音乐已暂停，闹钟仍在运行" else "静音午睡中",
                                    )
                                    // 通知失败不影响闹钟，闹钟已经排上了。
                                } else {
                                    cancel(context, state.id)
                                }
                            } catch (_: RuntimeException) {
                                finishAndNotify(context, store, state.id, "重启后未能恢复闹钟，请重新设置")
                            }
                        }
                    }
                }
                // 响铃中的会话没有可恢复的上下文，重启后直接结束。
                NapStatus.RINGING -> store.finish(state.id)
                NapStatus.IDLE -> Unit
            }
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
 * 开机和升级安装都会清掉已排的闹钟，两种回调走同一套恢复流程。
 */
class NapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDOVER_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NapAlarm.recoverAfterBoot(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDOVER_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
