package com.naptimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NapAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private var generation = 0
    private var alarmFocus: AudioFocusRequest? = null
    private var isRinging = false

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                notifyFailure(this@NapAudioService, isRinging)
                stopSelf()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val ringing = intent.getBooleanExtra("ringing", false)
        isRinging = ringing
        val request = ++generation
        startForeground(if (ringing) ALARM_NOTIFICATION_ID else NOTIFICATION_ID, notification(this, id, ringing))
        if (ringing) getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        scope.launch {
            val state = NapStore(applicationContext).current()
            val expected = if (ringing) NapStatus.RINGING else NapStatus.ACTIVE
            if (request != generation) return@launch
            if (state.id != id || state.status != expected || (!ringing && !state.sound)) {
                stopSelf(startId)
                return@launch
            }
            val soundResource = if (ringing) R.raw.alarm else SleepSound.fromId(state.soundId).resourceId()
            val source = Uri.parse("android.resource://$packageName/$soundResource")
            val attributes = AudioAttributes.Builder()
                .setUsage(if (ringing) C.USAGE_ALARM else C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val audioManager = getSystemService(AudioManager::class.java)
            alarmFocus?.let(audioManager::abandonAudioFocusRequest)
            alarmFocus = null
            player.setAudioAttributes(attributes, !ringing)
            if (ringing) {
                player.stop()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> player.play()
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()
                        }
                    }
                    .build()
                var waiting = false
                var granted = false
                // 别的应用长期占着音频时不能让闹铃无限等下去：最多等 10 秒，之后照常播放。
                for (attempt in 1..MAX_FOCUS_ATTEMPTS) {
                    if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        granted = true
                        break
                    }
                    if (!waiting) {
                        waiting = true
                        getSystemService(NotificationManager::class.java).notify(
                            ALARM_NOTIFICATION_ID,
                            notification(this@NapAudioService, id, ringing = true, text = "等待其他应用释放音频"),
                        )
                    }
                    if (attempt == MAX_FOCUS_ATTEMPTS) break
                    delay(FOCUS_RETRY_MILLIS)
                    val current = NapStore(applicationContext).current()
                    if (request != generation || current.id != id || current.status != NapStatus.RINGING) return@launch
                }
                if (waiting) {
                    // 等到焦点，或者等待超时，都把通知文案恢复成正常响铃。
                    getSystemService(NotificationManager::class.java).notify(
                        ALARM_NOTIFICATION_ID,
                        notification(this@NapAudioService, id, ringing = true),
                    )
                }
                if (granted) alarmFocus = focusRequest
            }
            player.setMediaItem(MediaItem.fromUri(source))
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
        alarmFocus?.let(getSystemService(AudioManager::class.java)::abandonAudioFocusRequest)
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ALARM_NOTIFICATION_ID = 1004
        private const val SESSION_NOTIFICATION_ID = 1003
        private const val PLAYBACK_CHANNEL = "nap_playback"
        private const val ALARM_CHANNEL = "nap_alarm"
        private const val FALLBACK_CHANNEL = "nap_alarm_fallback"
        private const val INFO_CHANNEL = "nap_info"
        private const val MAX_FOCUS_ATTEMPTS = 5
        private const val FOCUS_RETRY_MILLIS = 2_000L

        fun start(context: Context, id: String, ringing: Boolean) {
            val intent = Intent(context, NapAudioService::class.java)
                .putExtra("id", id)
                .putExtra("ringing", ringing)
            ContextCompat.startForegroundService(context, intent)
        }

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

        private fun notification(
            context: Context,
            id: String,
            ringing: Boolean,
            text: String? = null,
        ): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, NapStopReceiver::class.java)
                    .setData(Uri.parse("naptimer://stop/$id"))
                    .putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, if (ringing) ALARM_CHANNEL else PLAYBACK_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (ringing) "午睡结束" else "正在午睡")
                .setContentText(text ?: if (ringing) "休息好了，慢慢醒来" else "助眠音乐正在播放")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(0, if (ringing) "关闭闹钟" else "停止午睡", stop)
            if (ringing) {
                builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setFullScreenIntent(open, true)
            }
            return builder.build()
        }

        fun notifySession(context: Context, id: String, text: String) {
            createChannels(context)
            context.getSystemService(NotificationManager::class.java).notify(
                id,
                SESSION_NOTIFICATION_ID,
                notification(context, id, ringing = false, text = text),
            )
        }

        fun cancelSessionNotification(context: Context, id: String) {
            context.getSystemService(NotificationManager::class.java).cancel(id, SESSION_NOTIFICATION_ID)
        }

        fun notifyFailure(context: Context, ringing: Boolean = true) {
            createChannels(context)
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notice = NotificationCompat.Builder(context, if (ringing) FALLBACK_CHANNEL else ALARM_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (ringing) "午睡结束" else "助眠音乐无法播放")
                .setContentText(if (ringing) "闹铃无法播放，已改用系统闹铃声音提示" else "闹钟仍已设置，请打开应用查看")
                .setContentIntent(open)
                .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notice)
        }

        /** 会话在没有响铃的情况下结束（例如重启期间已到点）时，留一条安静的提示。 */
        fun notifyMissed(context: Context, text: String) {
            createChannels(context)
            val open = PendingIntent.getActivity(
                context,
                2,
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
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 2, notice)
        }
    }
}

class NapStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
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
