package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.example.classreminder.platform.ReminderEngine
import java.util.Calendar
import java.util.Locale

/**
 * 置顶全屏提醒卡片，替代安卓的 `LockOverlayActivity`。
 *
 * 安卓靠「全屏 Intent 通知 + `setShowWhenLocked` + `setTurnScreenOn`」在锁屏上弹出来；
 * Windows 没有应用级的锁屏覆盖能力，等价物是**无边框 + 置顶 + 最大化**的窗口，
 * 配合 `toFront()` / `requestFocus()` 抢到最前面。
 *
 * 已知限制（写进设置页文案）：对独占全屏的游戏 / 播放器可能压不住，
 * 这与安卓上 ColorOS 拦截全屏弹窗是同一类平台限制。
 */
@Composable
fun OverlayWindow(
    alert: ReminderEngine.Alert,
    themeMode: ThemeMode,
    onDismiss: () -> Unit
) {
    Window(
        onCloseRequest = onDismiss,
        title = "StuMate 提醒",
        state = rememberWindowState(placement = WindowPlacement.Maximized),
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false
    ) {
        // 抢焦点：Windows 不会像安卓那样「拉起即置顶」，必须显式提到最前面
        LaunchedEffect(alert.classId) {
            runCatching {
                window.toFront()
                window.requestFocus()
            }
        }
        FluentTheme(themeMode = themeMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else false
                    }
            ) {
                OverlayContent(
                    name = alert.title,
                    start = alert.startMillis,
                    end = alert.endMillis,
                    room = alert.room,
                    ongoing = alert.ongoing,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

/** 卡片本体：半透明暗底 + 顶部居中的 Fluent 卡片 */
@Composable
fun OverlayContent(
    name: String,
    start: Long,
    end: Long,
    room: String,
    ongoing: Boolean = true,
    onDismiss: () -> Unit
) {
    val c = FluentTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .padding(top = 40.dp)
                .fillMaxWidth(0.62f)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface)
                .border(1.dp, c.outlineStrong, RoundedCornerShape(8.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    if (ongoing) "正在上课" else "即将上课",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.accent
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    name,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${formatTime(start)} – ${formatTime(end)}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.accent
                )
                if (room.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "教室 $room",
                        fontSize = 15.sp,
                        color = c.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
                FlButton("知道了", onClick = onDismiss)
                Spacer(Modifier.height(2.dp))
                Text("按 Esc 也可以关闭", fontSize = 11.5.sp, color = c.onSurfaceFaint)
            }
        }
    }
}

@Suppress("unused")
private val overlaySpacer = Modifier.size(0.dp)

@Suppress("unused")
private val overlayWidthHint = 0.dp

private fun formatTime(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        Locale.getDefault(),
        "%02d:%02d",
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}
