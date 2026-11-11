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

    suspend fun start(context: Context, minutes: Int, sound: Boolean, soundId: String): String? =
        withContext(NonCancellable) {
            sessionMutex.withLock { startLocked(context, minutes, sound, soundId) }
        }

    private suspend fun startLocked(context: Context, minutes: Int, sound: Boolean, soundId: String): String? {
        if (!canSchedule(context)) return "请先允许设置精确闹钟"
        val store = NapStore(context)
        if (store.current().status != "idle") return "已有进行中的午睡"
        val duration = NapMath.clampMinutes(minutes) * 60_000L
        val id = UUID.randomUUID().toString()
        val endElapsed = SystemClock.elapsedRealtime() + duration
        return try {
            schedule(context, id, endElapsed)
            store.prepare(id, minutes, sound, soundId, endElapsed, System.currentTimeMillis() + duration)
            if (!store.activate(id)) {
                cancel(context, id)
                "午睡状态发生变化，请重试"
            } else {
                if (sound) {
                    try {
                        NapAudioService.start(context, id, ringing = false)
                    } catch (_: RuntimeException) {
                        return "闹钟已设置，但助眠音乐无法启动"
                    }
                } else {
                    try {
                        NapAudioService.notifySession(context, id, "静音午睡中")
                    } catch (_: RuntimeException) {
                        return "闹钟已设置，但无法显示午睡通知"
                    }
                }
                null
            }
        } catch (_: SecurityException) {
            cancel(context, id)
            store.finish(id)
            "无法设置闹钟，请检查系统权限"
        } catch (_: RuntimeException) {
            cancel(context, id)
            store.finish(id)
            "设置闹钟失败，请重试"
        }
    }

    suspend fun stop(context: Context, id: String) = withContext(NonCancellable) {
        sessionMutex.withLock {
            if (NapStore(context).finish(id)) {
                cancel(context, id)
                NapAudioService.cancelSessionNotification(context, id)
                context.stopService(Intent(context, NapAudioService::class.java))
            }
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

    suspend fun recoverPending(context: Context): String? = withContext(NonCancellable) {
        sessionMutex.withLock {
            val store = NapStore(context)
            val state = store.current()
            if (state.status != "pending") return@withLock null
            val target = pendingTarget(state, SystemClock.elapsedRealtime())
            if (target == null) {
                // 到点后才恢复：按设计标记结束并提示，不补响。
                store.finish(state.id)
                cancel(context, state.id)
                return@withLock "上次的午睡已到点，本次未能响铃"
            }
            try {
                if (!canSchedule(context)) return@withLock "请先允许设置精确闹钟"
                schedule(context, state.id, target)
                // 用 recover 而不是 activate：跨开机恢复时到点时刻已按墙上时钟重算，必须一起写回。
                if (store.recover(state.id, target)) {
                    if (state.sound) {
                        try {
                            NapAudioService.start(context, state.id, ringing = false)
                        } catch (_: RuntimeException) {
                            return@withLock "闹钟已恢复，但助眠音乐无法启动"
                        }
                    } else {
                        try {
                            NapAudioService.notifySession(context, state.id, "静音午睡中")
                        } catch (_: RuntimeException) {
                            return@withLock "闹钟已恢复，但无法显示午睡通知"
                        }
                    }
                }
                null
            } catch (_: SecurityException) {
                "无法恢复闹钟，请检查系统权限"
            } catch (_: RuntimeException) {
                "无法恢复闹钟，请重新打开应用"
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
                val remaining = NapMath.restartRemainingMillis(state.endWall, System.currentTimeMillis())
                    .coerceAtMost(duration)
                if (remaining > 0) nowElapsed + remaining else null
            }
            else -> null
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
            if (state.status == "active" || state.status == "pending") {
                val remaining = NapMath.restartRemainingMillis(state.endWall, System.currentTimeMillis())
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
                                try {
                                    NapAudioService.notifySession(
                                        context,
                                        state.id,
                                        if (state.sound) "设备重启后助眠音乐已暂停，闹钟仍在运行" else "静音午睡中",
                                    )
                                } catch (_: RuntimeException) {
                                    // Keep the scheduled alarm even when notifications are unavailable.
                                }
                            } else {
                                cancel(context, state.id)
                            }
                        } catch (_: RuntimeException) {
                            finishAndNotify(context, store, state.id, "重启后未能恢复闹钟，请重新设置")
                        }
                    }
                }
            } else if (state.status != "idle") {
                store.finish(state.id)
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

class NapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NapAlarm.recoverAfterBoot(context)
            } finally {
                pending.finish()
            }
        }
    }
}
