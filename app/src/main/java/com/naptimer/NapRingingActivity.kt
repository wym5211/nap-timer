package com.naptimer

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 闹铃响起时覆盖锁屏的全屏界面。
 *
 * 只由 full-screen intent / 通知点击拉起，不参与首页返回栈。会话一旦不再是 RINGING
 * （用户点了关闭、通知里的关闭动作、或被别处取消），界面立即自行结束，避免停在锁屏上。
 */
class NapRingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val barColor = Background.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(barColor, barColor),
            navigationBarStyle = SystemBarStyle.light(barColor, barColor),
        )
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        // 锁在广播里已经获取，这里只续期；释放交给 NapAlarm.stop() / Service.onDestroy()，
        // 否则息屏再点亮会重建 Activity 时误把整次响铃的锁放掉。
        NapScreenWake.acquire(this)
        val id = intent.getStringExtra(NapAudioService.EXTRA_SESSION_ID).orEmpty()
        setContent {
            MaterialTheme {
                RingingScreen(applicationContext, id) { finish() }
            }
        }
    }

    private fun showOverLockScreen() {
        // 官方推荐用 FLAG_KEEP_SCREEN_ON 保持亮屏；setTurnScreenOn 只负责启动这一刻。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }
}

@Composable
private fun RingingScreen(context: Context, intentId: String, onFinished: () -> Unit) {
    val store = remember { NapStore(context.applicationContext) }
    val snapshot = store.state.collectAsState(initial = null).value
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val id = intentId.ifEmpty { snapshot?.id.orEmpty() }

    LaunchedEffect(snapshot) {
        if (snapshot != null && snapshot.status != NapStatus.RINGING) onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("午睡结束", fontSize = 32.sp, color = Ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Text("休息好了，慢慢醒来", color = Green)
        Spacer(Modifier.height(50.dp))
        PrimaryButton("关闭闹钟", !busy && id.isNotEmpty()) {
            busy = true
            scope.launch {
                NapAlarm.stop(context.applicationContext, id)
                onFinished()
            }
        }
    }
}
