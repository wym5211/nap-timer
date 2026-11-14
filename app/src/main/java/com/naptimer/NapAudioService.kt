package com.naptimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NapAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private var generation = 0
    private var isRinging = false
    private var sessionId = ""

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                notifyFailure(this@NapAudioService, sessionId, isRinging)
                stopSelf()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION_ID) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val ringing = intent.getBooleanExtra(EXTRA_RINGING, false)
        isRinging = ringing
        sessionId = id
        val request = ++generation
        // full-screen intent 只在通知首次投递时触发。先撤掉上一次响铃留下的通知，
        // 保证下面这次 startForeground 是一次新的 post，而不是对旧通知的更新。
        if (ringing) cancelAlarmNotifications(this)
        val manager = getSystemService(NotificationManager::class.java)
        try {
            startForeground(
                if (ringing) ALARM_NOTIFICATION_ID else NOTIFICATION_ID,
                serviceNotification(this, id, ringing),
            )
        } catch (_: RuntimeException) {
            // Android 12+ 拒绝后台启动前台服务时，异常在 Service.startForeground() 抛出，
            // 而不是在 Context.startForegroundService()。所以兜底必须放在这里。
            notifyFailure(this, id, ringing)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (ringing) {
            manager.cancel(NOTIFICATION_ID)
            // full-screen intent 必须由 NotificationManager.notify() 单独投递：挂在
            // startForeground() 上的通知走前台服务通道，系统不会为它拉起全屏 Activity，
            // 锁屏就只剩一条不亮屏的 heads-up。先 cancel 再 notify 保证是首次投递。
            manager.notify(ALARM_ALERT_ID, alarmNotification(this, id))
        }
        scope.launch {
            val state = NapStore(applicationContext).current()
            val expected = if (ringing) NapStatus.RINGING else NapStatus.ACTIVE
            if (request != generation) return@launch
            if (state.id != id || state.status != expected || (!ringing && !state.sound)) {
                stopSelf(startId)
                return@launch
            }
            val soundResource = if (ringing) R.raw.alarm else SleepSound.fromId(state.soundId).resId
            val attributes = AudioAttributes.Builder()
                .setUsage(if (ringing) C.USAGE_ALARM else C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            // ExoPlayer 按 usage 自动申请/释放音频焦点；闹铃不等待其他应用让路。
            player.setAudioAttributes(attributes, true)
            if (ringing) player.stop()
            player.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://$packageName/$soundResource")))
            player.volume = 1f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare()
            player.play()
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        player.release()
        // 服务被系统杀掉时声音也没了，别让屏幕继续亮着；正常关闭时 NapAlarm.stop 已先释放。
        if (isRinging) NapScreenWake.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "id"
        const val EXTRA_RINGING = "ringing"

        private const val NOTIFICATION_ID = 1001
        private const val SESSION_NOTIFICATION_ID = 1003
        /** 前台服务自己的低调通知，不带 full-screen intent。 */
        private const val ALARM_NOTIFICATION_ID = 1004
        /** 独立投递、负责触发 full-screen intent 的响铃通知。 */
        internal const val ALARM_ALERT_ID = 1005
        private const val INFO_NOTIFICATION_ID = 1006
        private const val PLAYBACK_CHANNEL = "nap_playback"
        private const val ALARM_CHANNEL = "nap_alarm"
        private const val FALLBACK_CHANNEL = "nap_alarm_fallback"
        private const val INFO_CHANNEL = "nap_info"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_FULL_SCREEN = 2
        private const val REQUEST_STOP = 3
        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun start(context: Context, id: String, ringing: Boolean) {
            val intent = Intent(context, NapAudioService::class.java)
                .putExtra(EXTRA_SESSION_ID, id)
                .putExtra(EXTRA_RINGING, ringing)
            ContextCompat.startForegroundService(context, intent)
        }

        /** API 34 起 USE_FULL_SCREEN_INTENT 需要用户授权；低版本随清单声明自动生效。 */
        fun canUseFullScreenIntent(context: Context): Boolean =
            Build.VERSION.SDK_INT < 34 ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(PLAYBACK_CHANNEL, "午睡音乐", NotificationManager.IMPORTANCE_LOW),
            )
            val alarm = NotificationChannel(ALARM_CHANNEL, "午睡闹钟", NotificationManager.IMPORTANCE_HIGH)
            alarm.setSound(null, null)
            manager.createNotificationChannel(alarm)
            manager.createNotificationChannel(
                NotificationChannel(INFO_CHANNEL, "午睡提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
            // 闹铃服务可能被系统拒绝在后台启动；用系统闹铃音兜底，避免整次午睡静默失败。
            val fallback = NotificationChannel(
                FALLBACK_CHANNEL,
                "午睡闹钟（备用提示）",
                NotificationManager.IMPORTANCE_HIGH,
            )
            fallback.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            manager.createNotificationChannel(fallback)
        }

        private fun openPendingIntent(context: Context, id: String, ringing: Boolean): PendingIntent {
            val target = if (ringing) NapRingingActivity::class.java else MainActivity::class.java
            val intent = Intent(context, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("naptimer://open/$id"))
                .putExtra(EXTRA_SESSION_ID, id)
            return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PI_FLAGS)
        }

        /** 与 contentIntent 用不同的 requestCode 和 data，避免部分 SystemUI 上互相干扰。 */
        private fun fullScreenPendingIntent(context: Context, id: String): PendingIntent {
            val intent = Intent(context, NapRingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse("naptimer://ringing/$id"))
                .putExtra(EXTRA_SESSION_ID, id)
            return PendingIntent.getActivity(context, REQUEST_FULL_SCREEN, intent, PI_FLAGS)
        }

        private fun stopPendingIntent(context: Context, id: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_STOP,
                Intent(context, NapStopReceiver::class.java)
                    .setData(Uri.parse("naptimer://stop/$id"))
                    .putExtra(EXTRA_SESSION_ID, id),
                PI_FLAGS,
            )

        /** 前台服务维持运行用的通知：低调、不带 FSI，也不参与 heads-up 竞争。 */
        private fun serviceNotification(
            context: Context,
            id: String,
            ringing: Boolean,
            text: String? = null,
        ): Notification =
            NotificationCompat.Builder(context, PLAYBACK_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (ringing) "午睡闹钟正在响铃" else "正在午睡")
                .setContentText(text ?: if (ringing) "点开可关闭闹钟" else "助眠音乐正在播放")
                .setContentIntent(openPendingIntent(context, id, ringing))
                .setOngoing(true)
                .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(
                    R.drawable.ic_notification_stop,
                    if (ringing) "关闭闹钟" else "停止午睡",
                    stopPendingIntent(context, id),
                )
                .build()

        /**
         * 真正负责唤醒的响铃通知。必须由 NotificationManager.notify() 单独 post，
         * FSI 才会被系统当作一次新的高优先级投递去拉起全屏 Activity。
         */
        private fun alarmNotification(context: Context, id: String): Notification {
            val builder = NotificationCompat.Builder(context, ALARM_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("午睡结束")
                .setContentText("休息好了，慢慢醒来")
                .setContentIntent(openPendingIntent(context, id, ringing = true))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                    R.drawable.ic_notification_stop,
                    "关闭闹钟",
                    stopPendingIntent(context, id),
                )
            // 没有全屏权限时系统只会给 60 秒 heads-up；不挂 FSI，靠 VISIBILITY_PUBLIC
            // + 真实图标的动作按钮 + NapScreenWake 亮屏保证锁屏仍有关闭入口。
            if (canUseFullScreenIntent(context)) {
                builder.setFullScreenIntent(fullScreenPendingIntent(context, id), true)
            }
            return builder.build()
        }

        fun notifySession(context: Context, id: String, text: String) {
            createChannels(context)
            context.getSystemService(NotificationManager::class.java).notify(
                id,
                SESSION_NOTIFICATION_ID,
                serviceNotification(context, id, ringing = false, text = text),
            )
        }

        fun cancelSessionNotification(context: Context, id: String) {
            context.getSystemService(NotificationManager::class.java).cancel(id, SESSION_NOTIFICATION_ID)
        }

        /** 撤掉上次响铃残留的通知，避免 full-screen intent 被当成「更新」而不再触发。 */
        fun cancelAlarmNotifications(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(ALARM_NOTIFICATION_ID)
            manager.cancel(ALARM_ALERT_ID)
        }

        /** 服务起不来或播放出错时的告警：必须自带关闭入口，不能只留一条纯文字通知。 */
        fun notifyFailure(context: Context, id: String, ringing: Boolean = true) {
            createChannels(context)
            // 服务起不来时没有 Activity 会帮忙亮屏，兜底通知自己也要能点亮屏幕。
            if (ringing) NapScreenWake.acquire(context)
            val builder = NotificationCompat.Builder(context, if (ringing) FALLBACK_CHANNEL else ALARM_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (ringing) "午睡结束" else "助眠音乐无法播放")
                .setContentText(if (ringing) "闹铃无法播放，已改用系统闹铃声音提示" else "闹钟仍已设置，请打开应用查看")
                .setContentIntent(openPendingIntent(context, id, ringing))
                .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                    R.drawable.ic_notification_stop,
                    if (ringing) "关闭闹钟" else "停止午睡",
                    stopPendingIntent(context, id),
                )
            if (ringing && canUseFullScreenIntent(context)) {
                builder.setFullScreenIntent(fullScreenPendingIntent(context, id), true)
            }
            context.getSystemService(NotificationManager::class.java).notify(ALARM_ALERT_ID, builder.build())
        }

        /** 会话在没有响铃的情况下结束（例如重启期间已到点）时，留一条安静的提示。 */
        fun notifyMissed(context: Context, text: String) {
            createChannels(context)
            val open = PendingIntent.getActivity(
                context,
                4,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notice = NotificationCompat.Builder(context, INFO_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("午睡已结束")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(INFO_NOTIFICATION_ID, notice)
        }
    }
}

class NapStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(NapAudioService.EXTRA_SESSION_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NapAlarm.stop(context, id)
            } finally {
                pending.finish()
            }
        }
    }
}
