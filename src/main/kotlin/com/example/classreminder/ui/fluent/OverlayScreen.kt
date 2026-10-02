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
import com.example.classreminder.platform.WindowEffects
import java.util.Calendar
import java.util.Locale

/**
 * 置顶提醒卡片用的「透明魔术色」，`0xRRGGBB`。
 *
 * 画成这个颜色的像素会被 Windows 当成全透明（见 [WindowEffects.makeColorKeyTransparent]），
 * 于是卡片以外的区域既看得见底下的画面，也**不挡鼠标点击**。
 *
 * 选品红：它离 Fluent 调色板（灰 / 蓝）最远，卡片边缘抗锯齿混出来的 1px 边最不显眼。
 * ⚠️ **卡片自身的任何内容都不能用这个颜色**。
 */
const val OVERLAY_KEY_ARGB: Int = 0xFF00FF

/**
 * 置顶全屏提醒卡片，替代安卓的 `LockOverlayActivity`。
 *
 * 安卓靠「全屏 Intent 通知 + `setShowWhenLocked` + `setTurnScreenOn`」在锁屏上弹出来；
 * Windows 没有应用级的锁屏覆盖能力，等价物是**无边框 + 置顶 + 最大化**的窗口，
 * 配合 `toFront()` / `requestFocus()` 抢到最前面。
 *
 * ## 背景必须真透明，而且不能挡住用户点击
 *
 * 提醒的真实场景是「用户正在看课件 / 视频 / 浏览器」—— 屏幕其余部分必须原样可见、
 * 原样可点。⚠️ 这里**不能**用 `transparent = true`：实测它并没有真的建出分层窗口
 * （详见 [WindowEffects.makeColorKeyTransparent] 的 KDoc），卡片以外会是不透明的纯黑。
 * 改用色键透明，透明与鼠标穿透一起拿到。
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
        // ⚠️ 刻意**不**用 `transparent = true`：见上面的 KDoc。
        // 透明交给色键做，窗口自身保持普通窗口，Skia 照常画满整屏。
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
        // 色键透明要在窗口 realize 之后才拿得到 HWND，所以放在这里而不是构造函数里。
        // 失败（非 Windows / 拿不到 HWND）时保持不透明窗口，功能不受影响。
        LaunchedEffect(Unit) {
            runCatching { WindowEffects.makeColorKeyTransparent(window, OVERLAY_KEY_ARGB) }
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

/** 卡片本体：**背景透明** + 顶部居中的 Fluent 卡片 */
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
        // ⚠️ 整屏铺的是「透明魔术色」，不是黑底、也不是半透明黑底。
        // 这些像素会被 Windows 当作全透明：底下的画面原样可见，鼠标事件也直接穿透。
        // 卡片画在它上面，所以卡片本身照常接收点击。
        //
        // 曾经这里写的是 `Color(0xCC000000)`（80% 黑遮罩）。那是错的：
        // 提醒的场景是用户正在看课件 / 视频，把屏幕压暗等于在他最需要看画面时盖掉它。
        modifier = Modifier.fillMaxSize().background(Color(OVERLAY_KEY_ARGB)),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .padding(top = 40.dp)
                .fillMaxWidth(0.62f)
                // 不加 Compose 的 shadow：投影是 alpha 混合出来的，那些像素**不是**魔术色，
                // 会被当成不透明保留下来 —— 结果是在卡片四周糊一圈暗色（还带品红偏色）。
                // 卡片自己有 1px 描边，压在任意底色上都够清楚。
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
