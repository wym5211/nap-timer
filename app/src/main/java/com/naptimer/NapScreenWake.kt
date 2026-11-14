package com.naptimer

import android.content.Context
import android.os.PowerManager

/**
 * 闹铃到点时点亮屏幕并保持唤醒。
 *
 * ELAPSED_REALTIME_WAKEUP 只保证广播执行期间 CPU 不睡；PARTIAL_WAKE_LOCK 也只保 CPU，
 * 而且官方文档明确 ACQUIRE_CAUSES_WAKEUP 搭配 PARTIAL_WAKE_LOCK 时会被忽略。
 * 所以亮屏必须用 FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP，另配一把 PARTIAL 锁保播放，
 * ON_AFTER_RELEASE 让释放后屏幕按系统超时正常熄灭。
 *
 * 锁在广播里（NapAlarm.ring）就获取，不能等到 Activity.onCreate：full-screen intent
 * 可能被系统降级成 heads-up，那时根本没有 Activity 被拉起。
 */
object NapScreenWake {
    /** 别改成 PARTIAL_WAKE_LOCK：官方明确 ACQUIRE_CAUSES_WAKEUP 对 PARTIAL 会被忽略。 */
    @Suppress("DEPRECATION")
    internal val SCREEN_WAKE_FLAGS = PowerManager.FULL_WAKE_LOCK or
        PowerManager.ACQUIRE_CAUSES_WAKEUP or
        PowerManager.ON_AFTER_RELEASE

    private const val SCREEN_TIMEOUT_MILLIS = 5 * 60 * 1000L
    private const val CPU_TIMEOUT_MILLIS = 10 * 60 * 1000L

    @Volatile private var owner: Context? = null
    @Volatile private var screenLock: PowerManager.WakeLock? = null
    @Volatile private var cpuLock: PowerManager.WakeLock? = null

    /** 幂等：重复调用只是续期，不会叠加引用计数。 */
    @Synchronized
    fun acquire(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // 进程被重建（或 Robolectric 换了 Application）时旧锁已失效，先清掉再建新的。
        if (owner !== app) release()
        owner = app

        val cpu = cpuLock ?: manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "naptimer:ringing-cpu")
            .apply { setReferenceCounted(false) }
        cpu.acquire(CPU_TIMEOUT_MILLIS)
        cpuLock = cpu

        val screen = screenLock ?: manager.newWakeLock(SCREEN_WAKE_FLAGS, "naptimer:ringing-screen")
            .apply { setReferenceCounted(false) }
        // 真正把屏幕点亮的就在这一句：ACQUIRE_CAUSES_WAKEUP 在 acquire 时唤醒设备。
        screen.acquire(SCREEN_TIMEOUT_MILLIS)
        screenLock = screen
    }

    @Synchronized
    fun release() {
        listOf(screenLock, cpuLock).forEach { lock ->
            if (lock != null && lock.isHeld) lock.release()
        }
        screenLock = null
        cpuLock = null
        owner = null
    }

    @Synchronized
    fun isHeld(): Boolean = screenLock?.isHeld == true
}
