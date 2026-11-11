package com.naptimer

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Background = Color(0xFFF7F5EB)
private val Ink = Color(0xFF25382D)
private val Green = Color(0xFF43634A)
private val Rope = Color(0xFF927D5D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 起系统强制边到边；更早的版本也统一开启，让背景延伸到状态栏和手势条。
        val barColor = Background.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(barColor, barColor),
            navigationBarStyle = SystemBarStyle.light(barColor, barColor),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NapScreen(this)
            }
        }
    }
}

@Composable
private fun NapScreen(activity: MainActivity) {
    val store = remember { NapStore(activity.applicationContext) }
    val stored by store.state.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var minutes by rememberSaveable { mutableIntStateOf(20) }
    var sound by rememberSaveable { mutableStateOf(true) }
    var soundId by rememberSaveable { mutableStateOf(SleepSound.CALM.id) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showSoundSettings by remember { mutableStateOf(false) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var nowWall by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(
            activity,
            if (granted) "通知已开启，请再次点击开始午睡" else "请允许通知，否则可能无法看到闹钟提醒",
            Toast.LENGTH_LONG,
        ).show()
    }
    val state = stored
    LaunchedEffect(Unit) {
        val interrupted = store.current()
        if (interrupted.status == "pending") {
            val message = NapAlarm.recoverPending(activity.applicationContext)
            if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        } else if (interrupted.status == "active" && !NapAlarm.canSchedule(activity)) {
            NapAlarm.stop(activity.applicationContext, interrupted.id)
            Toast.makeText(activity, "精确闹钟权限已关闭，请重新设置午睡", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(state) {
        if (state != null && !initialized) {
            minutes = state.minutes
            sound = state.sound
            soundId = state.soundId
            initialized = true
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowElapsed = SystemClock.elapsedRealtime()
            nowWall = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun beginNap() {
        if (busy || state == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
            Toast.makeText(activity, "请开启通知，以便接收闹钟提醒", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
            return
        }
        if (!NapAlarm.canSchedule(activity)) {
            Toast.makeText(activity, "请允许设置精确闹钟，返回后再次点击开始", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= 31) {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${activity.packageName}")),
                )
            }
            return
        }
        busy = true
        scope.launch {
            try {
                val message = NapAlarm.start(activity.applicationContext, minutes, sound, soundId)
                if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(activity, "设置失败，请重试", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun stopNap(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                NapAlarm.stop(activity.applicationContext, id)
            } catch (_: Exception) {
                Toast.makeText(activity, "停止失败，请重试", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state == null) {
            Spacer(Modifier.height(100.dp))
            Text("正在准备午睡...", color = Ink)
        } else if (state.status == "ringing") {
            Spacer(Modifier.height(100.dp))
            Text("午睡结束", fontSize = 32.sp, color = Ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Text("休息好了，慢慢醒来", color = Green)
            Spacer(Modifier.height(50.dp))
            PrimaryButton("关闭闹钟", !busy) { stopNap(state.id) }
        } else if (state.status == "active" || state.status == "pending") {
            Spacer(Modifier.height(45.dp))
            Text("安心休息", fontSize = 27.sp, color = Ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Text("正在为你守着时间", color = Green)
            Spacer(Modifier.height(80.dp))
            val remaining = NapMath.remainingMillis(state.endElapsed, nowElapsed)
            val seconds = ceil(remaining / 1000.0).toLong()
            Text(
                "%02d:%02d".format(seconds / 60, seconds % 60),
                fontSize = 64.sp,
                color = Ink,
            )
            Text("预计 ${timeText(nowWall + remaining)} 唤醒", color = Green)
            Spacer(Modifier.height(80.dp))
            Text(
                if (state.sound) "助眠声音 · ${SleepSound.fromId(state.soundId).label}" else "助眠声音 · 静音",
                color = Green,
            )
            Spacer(Modifier.height(28.dp))
            PrimaryButton("停止午睡", !busy) { stopNap(state.id) }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Text("A LITTLE TIME FOR YOU", fontSize = 10.sp, letterSpacing = 2.sp, color = Green)
                Spacer(Modifier.height(12.dp))
                Text("午安，休息一下", fontSize = 27.sp, color = Ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("让接下来的时间慢一点。", color = Green, fontSize = 14.sp)
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$minutes", color = Ink, fontSize = 64.sp, lineHeight = 68.sp)
                Text("分钟", color = Ink, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            }
            Text("预计 ${timeText(nowWall + minutes * 60_000L)} 唤醒", color = Green, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            RopeSelector(minutes) { minutes = it }
            Text("上下拉动绳结，调整午睡时长", color = Green, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { minutes = NapMath.clampMinutes(minutes - 1) }) { Text("− 1 分钟", color = Green) }
                TextButton(onClick = { minutes = NapMath.clampMinutes(minutes + 1) }) { Text("+ 1 分钟", color = Green) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("助眠声音", modifier = Modifier.weight(1f), color = Ink)
                TextButton(onClick = { showSoundSettings = true }) {
                    Text(if (sound) "${SleepSound.fromId(soundId).label}  ›" else "静音  ›", color = Green)
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("开始午睡", !busy && initialized) { beginNap() }
        }
    }

    if (showSoundSettings) {
        SoundSettingsDialog(
            activity = activity,
            sound = sound,
            soundId = soundId,
            onSoundChange = { enabled, id ->
                sound = enabled
                soundId = id
            },
            onDismiss = { showSoundSettings = false },
        )
    }
}

@Composable
private fun SoundSettingsDialog(
    activity: MainActivity,
    sound: Boolean,
    soundId: String,
    onSoundChange: (Boolean, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val player = remember {
        ExoPlayer.Builder(activity).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(soundId) {
        player.stop()
        previewing = false
        val resourceId = SleepSound.fromId(soundId).resourceId()
        player.setMediaItem(MediaItem.fromUri("android.resource://${activity.packageName}/$resourceId"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("助眠声音") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    SleepSound.entries.forEach { option ->
                        TextButton(onClick = {
                            player.pause()
                            previewing = false
                            onSoundChange(true, option.id)
                        }) {
                            Text(if (sound && soundId == option.id) "✓ ${option.label}" else option.label)
                        }
                    }
                    TextButton(onClick = {
                        onSoundChange(false, soundId)
                        player.pause()
                        previewing = false
                    }) { Text(if (!sound) "✓ 静音" else "静音") }
                }
                if (sound) {
                    Text("使用手机音量键调节音量", color = Green, fontSize = 12.sp)
                    TextButton(onClick = {
                        if (previewing) {
                            player.pause()
                        } else {
                            player.prepare()
                            player.play()
                        }
                        previewing = !previewing
                    }) { Text(if (previewing) "停止预听" else "预听") }
                    SleepSound.fromId(soundId).attribution?.let { attribution ->
                        Text(
                            attribution,
                            color = Green,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green),
    ) {
        Text(text, fontSize = 15.sp)
    }
}

@Composable
private fun RopeSelector(minutes: Int, onChange: (Int) -> Unit) {
    val travel = 150.dp
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    var draggedValue by remember { mutableFloatStateOf(minutes.toFloat()) }
    val currentMinutes by rememberUpdatedState(minutes)
    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 206.dp)
            .semantics {
                contentDescription = "午睡时长"
                progressBarRangeInfo = ProgressBarRangeInfo(minutes.toFloat(), 1f..45f, 43)
                setProgress {
                    onChange(NapMath.clampMinutes(it.roundToInt()))
                    true
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.size(width = 4.dp, height = 190.dp)) {
            drawLine(Rope, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2.dp.toPx())
        }
        Box(
            modifier = Modifier
                .offset(y = travel * ((minutes - 1) / 44f))
                .size(48.dp)
                .pointerInput(travelPx) {
                    detectDragGestures(
                        onDragStart = { draggedValue = currentMinutes.toFloat() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            draggedValue = (draggedValue + dragAmount.y / travelPx * 44f).coerceIn(1f, 45f)
                            onChange(draggedValue.roundToInt())
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 20.dp, height = 30.dp).background(Rope, CircleShape))
        }
    }
}

private fun timeText(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
